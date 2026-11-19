// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class VoiceRequest(val text: String, val context: String, val source: ConversationSource,
    val wakeId: String? = null, val forceDsh: Boolean = true, val recordId: String? = null, val sourceRevision: Int = 1, val backfill: Boolean = false)

/** Original channels share one frame clock; each analysis lane owns its ASR and committed cursor. */
internal class ConversationController(private val context: Context, private val settings: AppSettings,
    private val changed: () -> Unit, private val capture: (ConversationSource, Boolean) -> Unit,
    private val request: (VoiceRequest) -> Boolean?, private val transcript: (SpeechSegment, ConversationSource) -> Unit,
    private val stopped: () -> Unit) {
    /** Unfinished ASR text of the current native wake (text, wake id), on the worker thread. */
    var partial: ((String, String) -> Unit)? = null
    private val gate = VoiceGate()
    @Volatile private var gating = false
    /** Glasses speech flags (one per 20 ms frame) for always-on audio; ignored unless gating is on. */
    fun voiceActivity(frames: List<Boolean>) { if (gating && state.running && !closing && !replaying) gate.offer(frames) }
    /** Main thread, when an always-on round starts or ends: queued audio is discarded there, so drop its flags too. */
    fun voiceRoundBoundary() = gate.clearFlags()
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private val battery = context.getSystemService(android.os.BatteryManager::class.java)
    private var resourcePauseReason: String? = null
    private fun resourceConstraint(): String? = when {
        power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> "기기 온도가 높음"
        power.isPowerSaveMode -> "시스템 절전 모드"
        battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..10 && !battery.isCharging -> "배터리 10% 미만"
        else -> null
    }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("conversation-processing").apply { start() }
    private val worker = Handler(thread.looper)
    private val writerThread = HandlerThread("conversation-audio-store").apply { start() }
    private val writer = Handler(writerThread.looper)
    private val inference = Executors.newSingleThreadExecutor()
    private val queuedSamples = AtomicInteger()
    val repository = ConversationRepository(context)
    val recordings = MeetingAudioStore(context)
    val modePolicy = VoiceModePolicy(context)
    @Volatile var state = ConversationState(people = repository.people, records = repository.records,
        error = repository.unreadable.takeIf { it.isNotEmpty() }?.let { "읽을 수 없는 기록 ${it.size}개는 원본 파일을 그대로 두고 목록에서 뺐습니다: ${it.joinToString()}" }); private set
    @Volatile private var generation = 0
    @Volatile private var closing = false
    @Volatile private var deferredProcessing = false
    @Volatile private var savingAudio = false
    @Volatile private var replaying = false
    @Volatile private var liveBackfill = false
    @Volatile private var samples = 0L // Frames per channel, never interleaved short count.
    private var captureChannels = 0
    private var enrollmentChannel = 1
    private var resamplers = List(2) { ConversationAudio() }
    private val engines = mutableMapOf<Int, SpeakerEngine>()
    private val activeASR = mutableMapOf<Int, SpeechRound>()
    private data class Round(val channel: Int, val baseFrame: Long, var endFrame: Long? = null)
    private val rounds = linkedMapOf<SpeechRound, Round>()
    private val laneStarts = mutableMapOf<Int, Long>()
    private val inputThrough = mutableMapOf<Int, Long>()
    private var asrEpoch = 0
    private val summarizer = ConversationSummarizer()
    private var config = JSONObject()
    /** Realtime two-channel only: silences channel 2 while channel 1 clearly carries the wearer's voice. Worker thread. */
    @Volatile private var selfGate: SelfVoiceGate? = null
    private var record: ConversationRecord? = null
    private var roundBaseMS = 0L
    private var roundId: String? = null
    private val pendingSentences = mutableListOf<SpeechSegment>()
    private val pendingAgent = mutableListOf<SpeechSegment>()
    @Volatile private var agentContextGeneration = 0
    private var previous = emptyList<String>()
    private var previousReply = ""
    private var dispatching = false
    private var replayFailed = false
    private var replayReadComplete = false
    private var tail: MeetingAudioStore.GrowingReader? = null
    private var tailSamples = 0L
    private var drainTimeout: Runnable? = null
    private var lastSummaryAt = 0L
    private var lastPublished = 0L
    private var nextBackfillAt = 0L
    private var enrollmentTarget: VoicePerson? = null
    private val manualSpeakers = mutableMapOf<String, String>()
    private var activeSummary: Triple<String, String, String>? = null
    private val summaryJobs = ArrayDeque<Triple<String, String, String>>()
    private fun engine(channel: Int): SpeakerEngine = engines.getOrPut(channel) {
        SpeakerEngine(context, { repository.people.filter { it.mergedInto == null } },
            { settings.read().optDouble("speakerThreshold", 0.7).toFloat() }, { begin, end -> classified(channel, begin, end) })
    }
    private fun fixedSelf(channel: Int) = SpeakerChannelPolicy.fixedSelf(record!!.channelPolicy, channel) && state.mode != ConversationMode.ENROLL
    private fun lanes() = 1..(record?.audioChannels ?: 1)
    private val maintenance = object : Runnable {
        override fun run() {
            if (state.running && !closing) {
                val constraint = if (savingAudio || replaying) resourceConstraint() else null
                if (constraint != null && replaying) { replayFailed = true; deferredProcessing = true; stop() }
                else if (constraint != null) {
                    if (!deferredProcessing || liveBackfill) pauseLive(constraint)
                    resourcePauseReason = constraint
                    publish(state.copy(status = "$constraint. 원본 오디오의 모든 채널은 계속 저장되며 복구 후 채널별로 보충합니다"))
                } else if (resourcePauseReason != null) { resourcePauseReason = null; nextBackfillAt = 0 }
                if (System.currentTimeMillis() - lastSummaryAt > 60_000 && config.optString("memoryAutoSummary", "true") != "false") {
                    record?.takeIf { it.segments.isNotEmpty() && !it.processingPending }?.let { lastSummaryAt = System.currentTimeMillis(); enqueueSummary(it.id, "rolling") }
                }
                if (constraint == null && deferredProcessing && !liveBackfill && savingAudio && captureChannels > 0 && queuedSamples.get() == 0 && System.currentTimeMillis() >= nextBackfillAt && SpeechRounds.ready(context, settings.read())) resumeLive()
                drainAgent()
            } else if (!closing && state.modelReady) resumePending()
            scheduleDaySummaries(false); worker.postDelayed(this, 15_000)
        }
    }
    init { worker.post {
        try {
            engine(1)
            repository.records.filter { it.audioPending }.forEach { interrupted ->
                val id = interrupted.audioId ?: interrupted.id
                val count = recordings.recover(id)
                val channels = if (count > 0) recordings.channelCount(id) else interrupted.audioChannels
                repository.save(interrupted.copy(audioSamples = count, audioChannels = channels,
                    channelPolicy = if (interrupted.channelPolicy == "pending_actual_channels") SpeakerChannelPolicy.policy(interrupted.source, channels) else interrupted.channelPolicy,
                    audioPending = false, processingPending = count > 0,
                    endedAt = interrupted.endedAt ?: interrupted.timelineAt((count * 1000 / 16000 - 1).coerceAtLeast(0)) + 1))
            }
            publish(state.copy(modelReady = true, records = repository.records)); worker.post(maintenance)
        } catch (error: Exception) { publish(state.copy(error = "음성 모델 로드 실패: ${error.message}")) }
    } }
    fun start(mode: ConversationMode, source: ConversationSource, target: VoicePerson? = null, wakeId: String? = null) {
        check(!state.running && !closing) { "먼저 현재 기록을 중지하세요" }; check(state.modelReady) { state.error ?: "음성 모델이 아직 준비되지 않음" }
        val cfg = settings.read(); val token = ++generation
        gating = source == ConversationSource.ALWAYS_ON && cfg.optString("glassesVoiceGate", "true") != "false"
        writer.post { gate.reset() }
        publish(state.copy(activeRecord = null, running = true, stopping = false, mode = mode, source = source, segments = emptyList(), error = null,
            status = if (mode == ConversationMode.ENROLL) "등록은 지정 채널의 본인 목소리만 수집합니다" else if (source == ConversationSource.REALTIME) "2채널 유지: 채널 1은 기본 본인, 채널 2는 별도 식별" else "혼합/단일 채널 오디오는 전체 화자 식별",
            enrollmentSeconds = 0f, enrollmentReady = false, enrollmentCanConfirm = false, enrollmentTotalSeconds = 0f, decodedSeconds = 0.0))
        worker.post {
            config = cfg; samples = 0; captureChannels = 0; roundBaseMS = 0; roundId = wakeId; closing = false
            selfGate = selfGateFor(source, mode, cfg)
            liveBackfill = false; replaying = false; replayFailed = false; tail?.close(); tail = null; cancelASR()
            resourcePauseReason = if (mode == ConversationMode.ENROLL) null else resourceConstraint()
            deferredProcessing = mode != ConversationMode.ENROLL && (!SpeechRounds.ready(context, cfg) || resourcePauseReason != null)
            pendingSentences.clear(); pendingAgent.clear(); dispatching = false; manualSpeakers.clear(); laneStarts.clear(); inputThrough.clear()
            resamplers = List(2) { ConversationAudio() }; enrollmentTarget = target
            record = ConversationRecord(mode = mode, source = source, audioAnchors = listOf(AudioAnchor(0, System.currentTimeMillis())),
                channelPolicy = "pending_actual_channels", audioPending = mode != ConversationMode.ENROLL, processingPending = deferredProcessing)
            savingAudio = mode != ConversationMode.ENROLL
            if (savingAudio) record = repository.save(record!!)
            main.post { if (token == generation && state.running) {
                try { capture(source, true) } catch (error: Exception) { fail(error.message ?: "음성 수신 시작 실패", token) }
            } }
        }
    }
    private fun prepareChannels(channels: Int, recovering: Boolean = false) {
        enrollmentChannel = if (config.optInt("audioChannel", 1) == 2 && channels == 2) 2 else 1
        val old = record!!
        val policy = if (recovering) old.channelPolicy else SpeakerChannelPolicy.policy(state.source, channels)
        record = old.copy(audioChannels = channels, channelPolicy = policy)
        if (policy == SpeakerChannelPolicy.DUAL_SELF && state.mode != ConversationMode.ENROLL) repository.ensureSelf()
        val needed = if (state.mode == ConversationMode.ENROLL) listOf(enrollmentChannel) else (1..channels).filterNot(::fixedSelf)
        engines.keys.filter { it !in needed }.toList().forEach { engines.remove(it)?.close() }
        needed.forEach { channel -> engine(channel).reset(laneStarts[channel]?.times(1000)?.div(16000) ?: 0,
            meeting = true, enrollment = state.mode == ConversationMode.ENROLL, labelPrefix = "c$channel:R$asrEpoch-") }
        saveRecord()
    }
    fun accept(left: ShortArray, right: ShortArray) {
        if (!state.running || closing || replaying) return
        val token = generation
        writer.post {
            if (token != generation) return@post
            val count = if (right.isEmpty()) 1 else 2
            try {
                if (captureChannels == 0) {
                    captureChannels = count
                    if (savingAudio) recordings.start(record!!.id, count)
                    worker.post { if (token == generation) prepareChannels(count) }
                }
                check(captureChannels == count) { "같은 기록 안에서 수신 채널이 바뀌었습니다. 기록을 다시 시작하세요" }
                val pcm = ConversationPCM(if (count == 2) listOf(resamplers[0].convert(left), resamplers[1].convert(right)) else listOf(resamplers[0].convert(left)))
                val start = samples
                if (savingAudio) recordings.appendChannels(pcm)
                // Only the live ASR/speaker path is gated; the saved recording stays raw for later backfill.
                val live = if (gating) gate.apply(pcm) else pcm
                samples += pcm.frames
                val queued = queuedSamples.addAndGet(pcm.frames)
                if (queued > 240_000 && savingAudio) deferredProcessing = true
                if (deferredProcessing || liveBackfill) {
                    queuedSamples.addAndGet(-pcm.frames)
                    if (!liveBackfill && System.currentTimeMillis() - lastPublished > 1_000) worker.post { markPending("모든 채널의 원본 오디오를 저장했습니다. 나중에 채널별로 보충합니다") }
                } else worker.post {
                    try { if (token == generation && !deferredProcessing) process(live, start) }
                    catch (error: Exception) { if (savingAudio) pauseLive(error.message ?: "음성 처리 보충 대기") else fail(error.message ?: "등록 실패", token) }
                    finally { queuedSamples.addAndGet(-pcm.frames) }
                }
                if (System.currentTimeMillis() - lastPublished > 1_000) { lastPublished = System.currentTimeMillis()
                    publish(state.copy(decodedSeconds = samples / 16000.0, voiceGate = gate.summary().ifEmpty { selfGate?.summary().orEmpty() })) }
            } catch (error: Exception) { fail(error.message ?: "원본 오디오 저장 실패", token) }
        }
    }
    private fun process(input: ConversationPCM, startFrame: Long) {
        val pcm = selfGate?.takeIf { input.channels.size == 2 }?.apply(input) ?: input
        if (state.mode == ConversationMode.ENROLL) {
            engine(enrollmentChannel).accept(pcm.channels[enrollmentChannel - 1])
            val result = engine(enrollmentChannel).enrollment()
            publish(state.copy(enrollmentSeconds = result.seconds, enrollmentReady = result.ready, enrollmentCanConfirm = result.confirmationVectors.isNotEmpty(),
                enrollmentTotalSeconds = result.totalSeconds, status = result.message)); return
        }
        pcm.channels.forEachIndexed { index, data ->
            val channel = index + 1
            val first = ((laneStarts[channel] ?: (roundBaseMS * 16)) - startFrame).coerceIn(0, data.size.toLong()).toInt()
            if (first == data.size) return@forEachIndexed
            val begin = startFrame + first; val selected = if (first == 0) data else data.copyOfRange(first, data.size)
            if (activeASR[channel] == null) startASR(channel, begin)
            activeASR[channel]?.append(selected)
            inputThrough[channel] = startFrame + data.size
            if (!fixedSelf(channel)) engine(channel).accept(selected)
        }
        drainSentences()
    }
    private fun cancelASR() {
        asrEpoch++; rounds.keys.forEach { it.cancel() }; rounds.clear(); activeASR.clear()
    }
    private fun startASR(channel: Int, baseFrame: Long) {
        val token = generation; val epoch = asrEpoch; val wake = roundId
        val info = Round(channel, baseFrame)
        lateinit var round: SpeechRound
        round = SpeechRounds.create(context, config, { text, begin, end, _, words -> worker.post {
            if (token == generation && epoch == asrEpoch && state.running) {
                val base = baseFrame * 1000 / 16000
                val id = "${record!!.id}:c$channel:${base + begin}:${base + end}"
                pendingSentences.removeAll { it.id == id }
                pendingSentences += SpeechSegment(id, base + begin, base + end, text, roundId = wake, sourceChannel = channel,
                    words = words.map { it.copy(beginMS = base + it.beginMS, endMS = base + it.endMS) })
                drainSentences()
            }
        } }, { message -> worker.post {
            rounds.remove(round)
            if (token != generation || epoch != asrEpoch) return@post
            if (activeASR[channel] === round) activeASR.remove(channel)
            if (replaying) { replayFailed = true; deferredProcessing = true; nextBackfillAt = System.currentTimeMillis() + 60_000; stop() }
            else { pauseLive("채널 $channel: $message"); if (closing) completeStop() }
        } }, { worker.post {
            rounds.remove(round)
            if (token == generation && epoch == asrEpoch) {
                drainSentences()
                val end = info.endFrame
                val localEnd = if (fixedSelf(channel)) inputThrough[channel] ?: 0 else engine(channel).analyzedThroughMS * 16
                if (end != null && localEnd >= end && pendingSentences.none { it.sourceChannel == channel && it.endMS * 16 <= end }) commitCursor(channel, end)
                if (closing && rounds.isEmpty()) completeStop()
                else if (activeASR[channel] === round) { activeASR.remove(channel); if (liveBackfill) pauseLive("채널 $channel 이번 라운드 종료, 잠시 후 계속 따라잡습니다") }
                drainAgent()
            }
        } }, if (state.source != ConversationSource.NATIVE || wake == null) null else { text -> worker.post {
            // Live partial for the native assistant page; the final sentence still goes through drainSentences.
            if (token == generation && epoch == asrEpoch && state.running) partial?.invoke(text, wake)
        } })
        rounds[round] = info; activeASR[channel] = round; round.start()
    }
    private fun commitCursor(channel: Int, frames: Long) {
        val old = record ?: return
        record = old.copy(processingCursors = old.processingCursors + (channel.toString() to maxOf(old.completedFrames(channel), frames)))
        saveRecord()
    }
    private fun finishRounds() {
        activeASR.forEach { (channel, round) -> rounds[round]?.let { it.endFrame = inputThrough[channel] ?: it.baseFrame }; round.finish() }
        activeASR.clear()
    }
    fun nextRound(wakeId: String? = null) {
        val token = generation; val receivedAt = System.currentTimeMillis()
        writer.post { val base = samples; worker.post {
            if (token != generation || !state.running || closing) return@post
            record = record?.let { it.copy(audioAnchors = it.audioAnchors.filter { a -> a.offsetMS != base / 16 } + AudioAnchor(base / 16, receivedAt)) }; saveRecord()
            if (liveBackfill) return@post
            engines.values.forEach { it.beginRound(base / 16) }; drainSentences(); finishRounds(); roundId = wakeId; roundBaseMS = base / 16
            lanes().forEach { laneStarts[it] = base }
        } }
    }
    fun endRound() { writer.post { worker.post {
        if (state.running && !closing && !liveBackfill) { engines.values.forEach { it.flush() }; drainSentences(); finishRounds(); drainAgent() }
    } } }
    fun transportError(message: String) = fail(message, generation)
    fun report(message: String) { publish(state.copy(error = message)) }
    private fun classified(channel: Int, begin: Long, end: Long) {
        if (state.mode == ConversationMode.ENROLL) return
        val old = record ?: return
        val source = engines[channel] ?: return
        val mapping = old.speakerPeople.toMutableMap()
        source.speakerSamples().forEach { (label, vectors) -> if (label !in mapping) {
            val evidence = source.evidence.firstOrNull { it.speaker == label && it.embedding != null && !it.mixed }
            val match = evidence?.let { source.assignment(it.beginMS, it.endMS) }
            mapping[label] = match?.personId ?: repository.rememberUnnamed(vectors).id
        } }
        record = old.copy(speakerPeople = mapping, speakerSamples = old.speakerSamples + source.speakerSamples())
    }
    private fun drainSentences() {
        val ready = pendingSentences.filter { sentence ->
            val channel = sentence.sourceChannel
            sentence.endMS * 16 <= if (fixedSelf(channel)) inputThrough[channel] ?: 0 else engine(channel).analyzedThroughMS * 16
        }.sortedWith(compareBy({ it.beginMS }, { it.sourceChannel }))
        pendingSentences.removeAll(ready.toSet())
        ready.forEach { sentence ->
            val channel = sentence.sourceChannel; val channelSelf = fixedSelf(channel)
            val self = if (channelSelf) repository.ensureSelf() else null
            val groups = mutableListOf<SpeechSegment>()
            val precise = sentence.words.isNotEmpty() && sentence.words.joinToString("") { it.text } == sentence.text && sentence.words.all { it.endMS > it.beginMS }
            val units = if (precise) sentence.words.mapIndexed { i, word -> sentence.copy(id = "${sentence.id}:$i", text = word.text, beginMS = word.beginMS, endMS = word.endMS, words = listOf(word)) } else listOf(sentence)
            units.forEach { unit ->
                val match = if (channelSelf) SpeakerEngine.Assignment(speaker = "c1:$SELF_SPEAKER", personId = self!!.id, stable = true, reason = "2채널 정책: 채널 1은 기본 본인")
                    else engine(channel).assignment(unit.beginMS, unit.endMS, wordLevel = precise)
                val manual = manualSpeakers[match.speaker]
                val person = manual ?: match.personId ?: record!!.speakerPeople[match.speaker].takeIf { match.stable && !match.mixed }
                val basis = when { manual != null -> "manual"; channelSelf -> "channel1_default_self"; match.mixed -> "mixed"; person != null -> "voiceprint"; else -> "unknown" }
                val attributed = unit.copy(speaker = if (match.mixed) "c$channel:$MIXED_SPEAKER" else match.speaker, personId = if (match.mixed) null else person,
                    stable = match.stable, mixed = match.mixed, candidates = match.candidates, matchScore = match.score, matchReason = match.reason, identityBasis = basis,
                    timelineAt = record!!.timelineAt(unit.beginMS), timelineEndAt = record!!.timelineAt((unit.endMS - 1).coerceAtLeast(unit.beginMS)) + 1,
                    timeBasis = if (record!!.audioAnchors.isEmpty()) "legacy-estimate" else "received", processingMode = if (replaying || liveBackfill) "backfill" else "live")
                val last = groups.lastOrNull()
                if (precise && last != null && last.speaker == attributed.speaker && last.personId == attributed.personId && last.mixed == attributed.mixed && record!!.anchorAt(last.beginMS) == record!!.anchorAt(attributed.beginMS))
                    groups[groups.lastIndex] = last.copy(text = last.text + attributed.text, endMS = attributed.endMS, words = last.words + attributed.words, timelineEndAt = attributed.timelineEndAt)
                else groups += attributed
            }
            groups.forEach { segment ->
                val old = record!!
                if (old.segments.none { it.id == segment.id }) {
                    record = old.copy(segments = (old.segments + segment).sortedWith(compareBy({ it.beginMS }, { it.sourceChannel })), summaryStale = true,
                        processingPending = replaying || old.processingPending,
                        processingCursors = old.processingCursors + (channel.toString() to maxOf(old.completedFrames(channel), segment.endMS * 16)))
                    saveRecord()
                    val token = generation; val source = state.source
                    if (!replaying) main.post { if (token == generation && state.running) transcript(segment, source) }
                    if (source != ConversationSource.NATIVE || segment.roundId == roundId) pendingAgent += segment
                }
            }
        }
        drainAgent()
    }
    private fun saveRecord() {
        val old = record ?: return
        if (old.mode != ConversationMode.ENROLL) record = repository.save(old)
        publish(state.copy(segments = record!!.segments, records = repository.records, people = repository.people, activeRecord = record))
    }
    private fun drainAgent() {
        if (dispatching || pendingAgent.isEmpty() || !state.running) return
        if (state.source == ConversationSource.ALWAYS_ON && !liveBackfill && !replaying && rounds.isNotEmpty()) return
        val input = pendingAgent.toList(); val token = generation; val source = state.source; val contextGeneration = agentContextGeneration
        val payload = modePolicy.input(source, input, repository.people, previous, previousReply).put("recordId", record?.id).put("recordRevision", record?.revision)
        val voice = VoiceRequest(input.joinToString(" ") { it.text }, modePolicy.agentPrompt + "\n" + payload, source, input.lastOrNull()?.roundId,
            true, record?.id, record?.revision ?: 1, input.any { it.processingMode == "backfill" })
        dispatching = true; val ids = input.map { it.id }.toSet(); pendingAgent.removeAll { it.id in ids }
        main.post {
            val accepted = if (token == generation && contextGeneration == agentContextGeneration) request(voice) else null
            worker.post {
                if (token != generation || contextGeneration != agentContextGeneration) return@post
                dispatching = false
                if (accepted == true) { previous = input.map { it.text }; publish(state.copy(status = "소스 채널별로 로컬 Agent에 전달함")); drainAgent() }
                else if (accepted == false) { pendingAgent.addAll(0, input); worker.postDelayed({ drainAgent() }, 1_000) }
            }
        }
    }
    private fun pauseLive(message: String) {
        cancelASR(); liveBackfill = false; deferredProcessing = true; tail?.close(); tail = null
        nextBackfillAt = System.currentTimeMillis() + 60_000; markPending("$message. 모든 채널의 원본 오디오는 보존되며 나중에 채널별로 보충합니다")
    }
    private fun markPending(message: String) {
        record?.let { if (!it.processingPending) { record = it.copy(processingPending = true); saveRecord() } }
        publish(state.copy(status = message))
    }
    private fun recoveryStart(): Long = lanes().minOf { record!!.completedFrames(it) }.coerceAtMost(samples)
    private fun beginBackfill(live: Boolean) {
        val old = record!!; config = settings.read(); cancelASR(); pendingSentences.clear()
        lanes().forEach { laneStarts[it] = old.completedFrames(it).coerceAtMost(old.audioSamples.takeIf { !old.audioPending } ?: samples); inputThrough[it] = laneStarts.getValue(it) }
        tailSamples = recoveryStart(); roundBaseMS = tailSamples / 16
        prepareChannels(old.audioChannels, recovering = true)
        tail?.close(); tail = recordings.growing(old.audioId ?: old.id, tailSamples)
        liveBackfill = live; deferredProcessing = false
        val token = generation; val epoch = asrEpoch
        val pump = object : Runnable {
            override fun run() {
                if (token != generation || epoch != asrEpoch || closing || !state.running) return
                try {
                    if (activeASR.values.any { !it.canAcceptAudio() }) { worker.postDelayed(this, 100); return }
                    val pcm = tail?.nextBlock()
                    if (pcm != null) { val begin = tailSamples; tailSamples += pcm.frames; if (!live) samples = tailSamples; process(pcm, begin) }
                    else if (!live && tail?.finished == true) { replayReadComplete = true; stop(); return }
                    if (live) {
                        val pending = samples - tailSamples > 16000
                        if (record?.processingPending != pending) { record = record?.copy(processingPending = pending); saveRecord() }
                    }
                    worker.postDelayed(this, if (pcm == null) 100 else if (live) 10 else 100)
                } catch (error: Exception) {
                    if (live) pauseLive(error.message ?: "복구 처리 실패") else { replayFailed = true; deferredProcessing = true; nextBackfillAt = System.currentTimeMillis() + 60_000; fail(error.message ?: "보충 실패", token) }
                }
            }
        }; worker.post(pump)
    }
    private fun resumeLive() { try { beginBackfill(true) } catch (error: Exception) { pauseLive(error.message ?: "복구 초기화 실패") } }
    fun resetAgentConversation() {
        agentContextGeneration++
        worker.post { previous = emptyList(); previousReply = ""; pendingAgent.clear(); dispatching = false }
    }
    fun rememberReply(question: String, reply: String) {
        val contextGeneration = agentContextGeneration
        worker.post { if (contextGeneration == agentContextGeneration) { previous = listOf(question); previousReply = reply } }
    }
    fun handoffForWake(ready: () -> Unit) { if (!state.running) main.post(ready) else { stopAfter = ready; stop() } }
    private var stopAfter: (() -> Unit)? = null
    fun stop() {
        if (!state.running || closing) return
        closing = true; publish(state.copy(stopping = true, status = "모든 채널을 저장하고 채널별 전사 마무리를 기다리는 중…"))
        if (!replaying) main.post { capture(state.source, false) }
        writer.post {
            val count = if (savingAudio && captureChannels > 0) recordings.finish() else if (replaying) record!!.audioSamples else samples
            savingAudio = false
            worker.post {
                if (liveBackfill && !deferredProcessing) runCatching {
                    while (activeASR.values.all { it.canAcceptAudio() }) { val pcm = tail?.nextBlock() ?: break; val begin = tailSamples; tailSamples += pcm.frames; process(pcm, begin) }
                }.onFailure { pauseLive(it.message ?: "마무리 후 보충 대기") }
                val pending = if (liveBackfill) deferredProcessing || tailSamples < count else deferredProcessing || record?.processingPending == true
                record = record?.copy(audioSamples = count, audioPending = false, endedAt = record?.endedAt ?: System.currentTimeMillis(), processingPending = pending)
                tail?.close(); tail = null; liveBackfill = false
                engines.values.forEach { it.flush() }; drainSentences(); finishRounds()
                if (rounds.isEmpty()) completeStop() else {
                    val token = generation
                    drainTimeout = Runnable { if (token == generation && closing) { cancelASR(); replayFailed = replaying; deferredProcessing = true; record = record?.copy(processingPending = true); completeStop("미완료 채널은 나중에 원본 오디오에서 보충합니다") } }
                        .also { worker.postDelayed(it, 30_000) }
                }
            }
        }
    }
    private fun completeStop(message: String = "모든 원본 오디오와 채널별 완료된 발화를 저장함") {
        drainTimeout?.let(worker::removeCallbacks); drainTimeout = null
        tail?.close(); tail = null; engines.values.forEach { it.flush() }; drainSentences()
        if (replaying && replayReadComplete && !replayFailed && !deferredProcessing) record = record?.copy(processingPending = false)
        else if (replaying) { record = record?.copy(processingPending = true); nextBackfillAt = System.currentTimeMillis() + 60_000 }
        if (pendingSentences.isNotEmpty()) record = record?.copy(processingPending = true)
        saveRecord(); val finished = record
        if (pendingAgent.isNotEmpty()) {
            val input = pendingAgent.toList(); val source = state.source
            val payload = modePolicy.input(source, input, repository.people, previous, previousReply).put("recordId", finished?.id).put("recordRevision", finished?.revision)
            val voice = VoiceRequest(input.joinToString(" ") { it.text }, modePolicy.agentPrompt + "\n" + payload, source, input.lastOrNull()?.roundId,
                true, finished?.id, finished?.revision ?: 1, input.any { it.processingMode == "backfill" })
            val contextGeneration = agentContextGeneration
            main.post { if (contextGeneration == agentContextGeneration) request(voice) }
        }
        replaying = false; pendingSentences.clear(); pendingAgent.clear(); dispatching = false; engines.values.forEach { it.reset() }; manualSpeakers.clear(); closing = false
        publish(state.copy(running = false, stopping = false, segments = finished?.segments.orEmpty(), activeRecord = null, records = repository.records, status = message))
        if (finished != null && finished.mode != ConversationMode.ENROLL && !finished.processingPending && finished.segments.isNotEmpty()) { enqueueSummary(finished.id, "record_end"); scheduleDaySummaries(finished.source == ConversationSource.ALWAYS_ON) }
        val continuation = stopAfter; stopAfter = null; main.post { if (continuation != null) continuation() else stopped() }
    }
    fun saveEnrollment(name: String, self: Boolean, contactUri: String? = null, confirmAllSamples: Boolean = false) { worker.post {
        check(state.running && state.mode == ConversationMode.ENROLL && !closing)
        engine(enrollmentChannel).flush(); val assessment = engine(enrollmentChannel).enrollment()
        check(assessment.ready || confirmAllSamples && assessment.confirmationVectors.isNotEmpty()) { assessment.message }
        val person = (enrollmentTarget ?: VoicePerson(name = name, self = self, contactUri = contactUri)).copy(name = name, self = self,
            contactUri = contactUri, samples = if (assessment.ready) assessment.vectors else assessment.confirmationVectors, unnamed = false)
        repository.savePerson(person); publish(state.copy(people = repository.people, status = "${person.name}의 목소리가 이 기기에 저장됨")); stop()
    } }
    fun bind(recordId: String, speakerLabel: String, person: VoicePerson, rememberVoice: Boolean) { worker.post {
        val old = record?.takeIf { it.id == recordId } ?: repository.records.first { it.id == recordId }
        repository.bindSpeakerVoice(old, speakerLabel, person, rememberVoice)
        val revised = old.copy(speakerPeople = old.speakerPeople + (speakerLabel to person.id))
        repository.save(revised)
        if (record?.id == recordId) { record = revised; manualSpeakers[speakerLabel] = person.id }
        applyCorrection(mapOf(recordId to old.segments.filter { it.speaker == speakerLabel }.map { it.id }.toSet()), person.id)
    } }
    fun correctSpeaker(recordId: String, ids: Set<String>, personId: String?, person: VoicePerson? = null) { worker.post {
        person?.let(repository::savePerson); applyCorrection(mapOf(recordId to ids), personId)
    } }
    fun correctSpeakers(selection: Map<String, Set<String>>, personId: String?) { worker.post { applyCorrection(selection, personId) } }
    private fun applyCorrection(selection: Map<String, Set<String>>, personId: String?) {
        selection.forEach { (id, ids) -> repository.correctSpeaker(id, ids, personId) }
        record?.let { active -> record = repository.records.firstOrNull { it.id == active.id } ?: active }
        publish(state.copy(records = repository.records, people = repository.people, segments = record?.segments ?: state.segments, activeRecord = if (state.running) record else null))
    }
    fun renamePerson(id: String, name: String) { worker.post {
        repository.savePerson(repository.people.first { it.id == id }.copy(name = name, unnamed = false))
        publish(state.copy(people = repository.people))
    } }
    fun mergePeople(sourceIds: Set<String>, targetId: String, recordIds: Set<String>) { worker.post {
        repository.mergePeople(sourceIds, targetId, recordIds)
        record?.let { active -> record = repository.records.firstOrNull { it.id == active.id } ?: active }
        publish(state.copy(records = repository.records, people = repository.people))
    } }
    fun splitPerson(personId: String, newName: String, selection: Map<String, Set<String>>) { worker.post {
        val person = repository.people.first { it.id == personId }
        // A split must learn only the selected historical voice samples.
        val vectors = selection.flatMap { (id, ids) ->
            val row = repository.records.first { it.id == id }
            if (row.audioPending || row.audioSamples == 0L) emptyList() else row.segments.filter { it.id in ids && it.personId == personId && !it.mixed && it.endMS - it.beginMS >= 800 }
                .mapNotNull { segment ->
                    val pcm = recordings.readSamples(row.audioId ?: row.id, segment.beginMS, minOf(segment.endMS, segment.beginMS + 3000), segment.sourceChannel)
                    engine(segment.sourceChannel).embedding(pcm)
                }
        }
        val created = VoicePerson(name = newName, model = person.model, samples = vectors.takeLast(20))
        repository.savePerson(created); applyCorrection(selection, created.id)
    } }
    fun correctMemory(recordId: String, memoryId: String, subject: String, text: String) { worker.post {
        repository.correctMemory(recordId, memoryId, subject, text); publish(state.copy(records = repository.records))
    } }
    fun removeVoice(id: String) { worker.post { repository.removeVoice(id); publish(state.copy(people = repository.people)) } }
    private fun day(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun summaryConfigured() = settings.read().let { MobileModelEndpoint.isConfigured(it.optString("modelUrl"), it.optString("modelName"), it.optString("modelKey")) }
    fun summarize(recordId: String) { worker.post { enqueueSummary(recordId, "manual") } }
    private fun scheduleDaySummaries(includeToday: Boolean) {
        if (!summaryConfigured()) return
        val today = LocalDate.now().toString()
        val days = repository.records.filter { it.source == ConversationSource.ALWAYS_ON && !it.processingPending }
            .flatMap { row -> row.segments.map { day(row.timeOf(it)) } }.distinct().filter { includeToday || it < today }
        days.forEach { date ->
            val sources = repository.records.filter { row -> row.source == ConversationSource.ALWAYS_ON && row.segments.any { day(row.timeOf(it)) == date } }
            if (sources.any { it.processingPending }) return@forEach
            if (repository.records.any { row -> row.summaries.any { it.kind == "day_end" && it.day == date && !it.stale } }) return@forEach
            sources.maxByOrNull { it.createdAt }?.let { enqueueSummary(it.id, "day_end", date) }
        }
    }
    private fun enqueueSummary(id: String, kind: String, date: String = "") {
        if (!summaryConfigured() || activeSummary == Triple(id, kind, date) || summaryJobs.any { it.first == id && it.second == kind && it.third == date }) return
        summaryJobs += Triple(id, kind, date); drainSummaries()
    }
    private fun drainSummaries() {
        if (state.summarizing || summaryJobs.isEmpty()) return
        val (id, kind, date) = summaryJobs.removeFirst()
        val target = repository.records.firstOrNull { it.id == id } ?: return drainSummaries()
        val sources = if (kind == "day_end") repository.records.filter { row -> row.source == ConversationSource.ALWAYS_ON && row.segments.any { day(row.timeOf(it)) == date } } else listOf(target)
        val input = if (kind == "day_end") target.copy(segments = sources.flatMap { row -> row.segments.filter { day(row.timeOf(it)) == date } }) else target
        if (input.segments.isEmpty()) return drainSummaries()
        val versions = sources.associate { it.id to it.segments.map { segment -> segment.json().toString() } }
        val cfg = settings.read(); activeSummary = Triple(id, kind, date); publish(state.copy(summarizing = true))
        inference.execute {
            val result = runCatching { summarizer.summarize(input, repository.people, cfg) }
            worker.post {
                result.fold({ summarized ->
                    val currentSources = if (kind == "day_end") repository.records.filter { row -> row.source == ConversationSource.ALWAYS_ON && row.segments.any { day(row.timeOf(it)) == date } }
                        else repository.records.filter { it.id == id }
                    val valid = currentSources.map { it.id }.toSet() == versions.keys && versions.all { (recordId, segments) ->
                        repository.records.firstOrNull { it.id == recordId }?.segments?.map { it.json().toString() } == segments }
                    if (valid) repository.records.firstOrNull { it.id == id }?.let { current ->
                        val snapshot = RecordSummary(kind, summarized.summary, recordIds = sources.map { it.id }, day = date)
                        val snapshots = current.summaries.filterNot { kind == "day_end" && it.kind == kind && it.day == date } + snapshot
                        val updated = if (kind == "day_end") current.copy(summaries = snapshots) else current.copy(summary = summarized.summary,
                            memories = summarized.memories, summaryStale = false, summaries = snapshots)
                        val saved = repository.save(updated)
                        if (record?.id == id) record = saved
                    }
                }, { error -> publish(state.copy(error = "요약은 나중에 다시 시도할 수 있음: ${error.message}")) })
                activeSummary = null; publish(state.copy(summarizing = false, records = repository.records, activeRecord = if (state.running) record else null)); drainSummaries()
            }
        }
    }
    fun refreshRecords() { worker.post {
        record?.let { active -> record = repository.records.firstOrNull { it.id == active.id } ?: active }
        publish(state.copy(records = repository.records, people = repository.people, activeRecord = if (state.running) record else null))
    } }
    fun configurationChanged() { worker.post { nextBackfillAt = 0; if (!state.running) resumePending() } }
    private fun resumePending() {
        if (state.running || closing || resourceConstraint() != null || System.currentTimeMillis() < nextBackfillAt || !SpeechRounds.ready(context, settings.read())) return
        repository.records.filter { it.processingPending && !it.audioPending && it.audioSamples > 0 }.minByOrNull { it.createdAt }?.let { retranscribe(it.id) }
    }
    /** Fresh per session or replay: only realtime two-channel records assume channel 1 is the wearer. */
    private fun selfGateFor(source: ConversationSource, mode: ConversationMode, cfg: JSONObject): SelfVoiceGate? =
        if (source != ConversationSource.REALTIME || mode == ConversationMode.ENROLL) null
        else cfg.optString("selfVoiceMarginDb").ifBlank { "6" }.toDoubleOrNull()?.takeIf { it > 0 }?.let { margin ->
            SelfVoiceGate(margin, cfg.optString("selfVoiceFloorDb").toDoubleOrNull() ?: -50.0,
                (cfg.optString("selfVoiceHoldMs").toIntOrNull() ?: 300) / 20)
        }
    fun retranscribe(recordId: String) {
        check(!state.running && !closing && state.modelReady) { "먼저 현재 기록을 중지하세요" }
        val old = repository.records.first { it.id == recordId }; require(old.audioSamples > 0)
        val cfg = settings.read(); require(SpeechRounds.ready(context, cfg)) { SpeechRounds.missing(cfg) }
        ++generation
        publish(state.copy(running = true, stopping = false, mode = old.mode, source = old.source, status = "채널별 완료 커서에 따라 각각 보충하며 원문과 수동 수정은 유지됩니다",
            error = null, activeRecord = old, segments = old.segments, decodedSeconds = 0.0))
        worker.post {
            selfGate = selfGateFor(old.source, old.mode, cfg)
            config = cfg; samples = old.audioSamples; roundId = null; replaying = true; replayFailed = false; replayReadComplete = false; deferredProcessing = false; savingAudio = false
            pendingSentences.clear(); pendingAgent.clear(); manualSpeakers.clear(); inputThrough.clear(); record = repository.save(old.copy(processingPending = true))
            try { beginBackfill(false) }
            catch (error: Exception) { replayFailed = true; deferredProcessing = true; nextBackfillAt = System.currentTimeMillis() + 60_000; fail(error.message ?: "보충 초기화 실패", generation) }
        }
    }
    private fun fail(message: String, token: Int) {
        if (token != generation || !state.running) return
        publish(state.copy(error = message)); stop()
    }
    private fun publish(next: ConversationState) { state = next; main.post(changed) }
    fun close() {
        worker.removeCallbacks(maintenance)
        if (state.running && !closing) stop()
        writer.post { worker.post {
            generation++; cancelASR(); tail?.close(); engines.values.forEach { it.close() }; engines.clear(); summarizer.close(); inference.shutdownNow()
            writerThread.quitSafely(); thread.quitSafely()
        } }
    }
}

/**
 * Channel 1 is the wearer's mic, channel 2 the outside mic, and the wearer's own voice reaches both. When a
 * 20 ms frame is clearly louder on channel 1 than on channel 2 (by [marginDb]) and channel 1 is above a speech
 * floor, the wearer is talking, so channel 2 is silenced (plus a short hangover) and the same words are not
 * transcribed twice as someone else's. A loud outside sound raises both channels about equally, so the ratio,
 * not channel 1's level alone, decides. Channel 1 itself passes unchanged.
 */
internal class SelfVoiceGate(private val marginDb: Double,
    /** Below this channel-1 level (dBFS) the frame is silence, whatever the ratio. */
    private val floorDb: Double = -50.0,
    /** Frames (20 ms each) to keep muting after the last own-voice frame. */
    private val holdFrames: Int = 15) {
    private var hold = 0
    @Volatile private var total = 0L
    @Volatile private var muted = 0L
    fun summary() = if (total == 0L) "" else "본인 목소리로 판단해 채널 2 무음 처리: ${muted * 100 / total}% (기준 ${marginDb.toInt()} dB)"
    fun apply(pcm: ConversationPCM): ConversationPCM {
        val self = pcm.channels[0]; val outside = pcm.channels[1]
        val out = outside.copyOf()
        var start = 0
        while (start < self.size) {
            val end = minOf(start + FRAME, self.size)
            var a = 1e-10; var b = 1e-10
            for (i in start until end) { a += self[i] * self[i]; b += outside[i] * outside[i] }
            val level = 10 * kotlin.math.log10(a / (end - start))
            val ratio = 10 * kotlin.math.log10(a / b)
            if (level > floorDb && ratio >= marginDb) hold = holdFrames + 1   // this frame, then the hold
            total++
            if (hold > 0) { hold--; muted++; out.fill(0f, start, end) }
            start = end
        }
        return ConversationPCM(listOf(self, out))
    }
    private companion object {
        const val FRAME = 320        // 20 ms at 16 kHz
    }
}

/**
 * Glasses-side speech flags for always-on audio: each A3 batch carries vpuMask/vadMask with one bit per
 * 20 ms Opus frame. Frames either mask marks as speech, plus a short lead-in and hangover, pass to ASR;
 * the rest is silenced so background noise is not transcribed. Flags arrive with their batch before the
 * decoder emits its PCM, which gives the lead-in for free. Without flags, audio passes unchanged.
 */
internal class VoiceGate {
    private val flags = java.util.concurrent.ConcurrentLinkedDeque<Boolean>()
    // Writer-thread state.
    private var position = 0
    private var open = true
    private var hold = 0
    @Volatile private var total = 0L
    @Volatile private var passed = 0L
    fun offer(frames: List<Boolean>) {
        flags.addAll(frames)
        // Dropped decoder packets would leave flags behind their audio; resync instead of drifting.
        while (flags.size > MAX_PENDING) flags.pollFirst()
    }
    fun reset() { flags.clear(); position = 0; open = true; hold = 0; total = 0; passed = 0 }
    /** Thread safe; keeps statistics. */
    fun clearFlags() = flags.clear()
    fun summary() = if (total == 0L) "" else "안경 음성 감지: 전체의 ${passed * 100 / total}%를 인식에 사용"
    /** Writer thread. Returns a copy with non-speech frames zeroed. */
    fun apply(pcm: ConversationPCM): ConversationPCM {
        if (total == 0L && flags.isEmpty()) return pcm
        val out = pcm.channels.map { it.copyOf() }
        for (index in 0 until pcm.frames) {
            if (position == 0) {
                val next = flags.pollFirst()
                if (next == null) open = true
                else {
                    total++
                    if (next || flags.asSequence().take(LEAD).any { it }) hold = HANG
                    open = hold > 0; if (hold > 0) hold--
                    if (open) passed++
                }
            }
            if (!open) out.forEach { it[index] = 0f }
            position = (position + 1) % FRAME
        }
        return ConversationPCM(out)
    }
    private companion object {
        const val FRAME = 320       // 20 ms at 16 kHz
        const val LEAD = 10         // 200 ms before speech
        const val HANG = 25         // 500 ms after speech
        const val MAX_PENDING = 150
    }
}
