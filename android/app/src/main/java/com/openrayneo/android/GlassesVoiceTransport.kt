// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Native assistant and LifeLog wire ownership. No account login or official cloud relay. */
internal class GlassesVoiceTransport(
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val wake: () -> Unit, private val beginRound: () -> Unit,
    private val endRound: (Boolean) -> Unit, private val audio: (ByteArray) -> Unit,
    /** Per-frame speech flags (vpuMask | vadMask) of an always-on batch, delivered before its audio. */
    private val activity: (List<Boolean>) -> Unit,
    private val failure: (String) -> Unit,
    /** App log for assistant control messages we send (live partials excluded). */
    private val trace: (String) -> Unit = {},
) {
    var source: ConversationSource? = null; private set
    var wakeId: String? = null; private set
    var nativeActive = false; private set
    private var nativeCode = 0
    private var sequence = 0x7100
    private var lifeTask: String? = null
    private var lastReply: String? = null
    private var advancedReply: String? = null
    /** Wake whose final transcript was already sent; later partials would arrive out of order. */
    private var finalSent: String? = null
    private val client = UUID.randomUUID().toString().replace("-", "")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Firmware barge-in setting ("대화 끊기"); read when an answer is sent. */
    var bargeIn: () -> Boolean = { false }
    /**
     * Barge-in: capture was reopened right after the answer while the glasses still present it. The first
     * heard words switch the glasses to LISTENING (cutting the answer); otherwise their own next-turn
     * request (type 11) after the answer does.
     */
    private var answering = false
        set(value) { if (field != value) { field = value; answeringChanged(value) } }
    /** Main thread; lets the service arm the level-based speech detector only while an answer is presented. */
    var answeringChanged: (Boolean) -> Unit = {}
    /** Answer text being presented, so its own playback picked up by the mic does not count as an interruption. */
    private var answerText = ""
    /** Wake already answered with "listening" (rc 1) before native capture took over, e.g. while always-on stopped. */
    private var acceptedWake: String? = null
    /** Question audio that arrived while that handoff was still running; replayed when native capture starts. */
    private val earlyAudio = ArrayDeque<ByteArray>()

    /**
     * Answer a wake at once although another capture (always-on) still owns the audio: the glasses start
     * streaming the question only after this, and the handoff takes a second or more.
     */
    @Synchronized fun acceptWake() {
        if (!nativeActive || nativeCode !in 1..6 || acceptedWake == wakeId) return
        acceptedWake = wakeId; earlyAudio.clear()
        send(0x0d, 2, JSONObject().put("rc", 1))
        if (!answering) send(0x0d, 9, JSONObject().put("rc", 1))
    }
    /** The early-answered wake will not get native capture after all: let the glasses stop listening. */
    @Synchronized fun releaseWake(expected: String?) {
        if (expected == null || acceptedWake != expected) return
        acceptedWake = null; earlyAudio.clear()
        if (nativeActive && wakeId == expected && source != ConversationSource.NATIVE) send(0x0d, 2, JSONObject().put("rc", 2))
    }
    private var replaying = false
    private var staleTaskReported: String? = null
    /** Last LifeLog round start (161) or audio (163) of ours: tells whether LifeLog came back after a voice memo. */
    private var lifeHeardAt = 0L
    /** A re-sent LifeLog switch is a nudge: its refusal must not stop the running always-on record. */
    private var rearmPending = false
    /**
     * The glasses' voice memo takes the microphone from LifeLog. If no LifeLog round or audio comes back within
     * [REARM_WAIT_MS] after the memo, switch LifeLog on again; a fresh round (161) then replaces the dead one.
     */
    @Synchronized fun rearmLifeLog() {
        if (source != ConversationSource.ALWAYS_ON) return
        val since = System.currentTimeMillis()
        main.postDelayed({
            synchronized(this) {
                if (source != ConversationSource.ALWAYS_ON || lifeHeardAt >= since) return@synchronized
                trace("음성 메모 뒤 종일 기록 오디오가 오지 않아 다시 켬")
                rearmPending = true
                send(0x0f, 20, JSONObject().put("cmd", "life_log_switch")
                    .put("payload", JSONObject().put("value", 1).put("mode", 0).put("data", "{\"delay\":2}")))
            }
        }, REARM_WAIT_MS)
    }
    private fun replaySlice(expected: String?) {
        synchronized(this) {
            if (source != ConversationSource.NATIVE || wakeId != expected) { replaying = false; earlyAudio.clear(); return }
            repeat(minOf(REPLAY_SLICE, earlyAudio.size)) { audio(earlyAudio.removeFirst()) }
            if (earlyAudio.isEmpty()) { replaying = false; return }
        }
        main.postDelayed({ replaySlice(expected) }, REPLAY_SLICE_MS)
    }
    @Synchronized fun start(next: ConversationSource) {
        check(source == null || source == next) { "먼저 기존 오디오 기능을 종료하세요" }
        source = next
        when (next) {
            ConversationSource.NATIVE -> {
                check(nativeActive && nativeCode in 1..6) { "먼저 깨우기 명령어(Hey RayNeo 등)로 안경을 깨우고 자동 응답을 켜세요" }
                val early = acceptedWake != null && acceptedWake == wakeId
                acceptedWake = null
                if (!early) {
                    earlyAudio.clear()
                    send(0x0d, 2, JSONObject().put("rc", 1))
                    if (!answering) send(0x0d, 9, JSONObject().put("rc", 1))
                } else if (earlyAudio.isNotEmpty()) {
                    // The decoder queue holds about 5 s: replay in slices; live frames queue behind until it is drained.
                    trace("깨우기 전환 중 받은 질문 오디오 ${earlyAudio.size}개를 이어서 전사")
                    replaying = true; replaySlice(wakeId)
                }
            }
            ConversationSource.ALWAYS_ON -> send(0x0f, 20, JSONObject().put("cmd", "life_log_switch")
                .put("payload", JSONObject().put("value", 1).put("mode", 0).put("data", "{\"delay\":2}")))
            ConversationSource.REALTIME, ConversationSource.VOICE_MEMO -> Unit
        }
    }
    @Synchronized fun stop() {
        val previous = source; source = null; lifeTask = null
        acceptedWake = null; earlyAudio.clear(); replaying = false
        if (previous == ConversationSource.NATIVE) send(0x0d, 2, JSONObject().put("rc", 2))
        if (previous == ConversationSource.ALWAYS_ON) send(0x0f, 20, JSONObject().put("cmd", "life_log_switch")
            .put("payload", JSONObject().put("value", 0).put("mode", 0).put("data", "")))
    }
    @Synchronized fun disconnected() { source = null; nativeActive = false; wakeId = null; lifeTask = null; answering = false; acceptedWake = null; earlyAudio.clear(); replaying = false }
    @Synchronized fun receive(frame: ProtocolCodec.Frame) {
        if (frame.protocol == 0x0f) {
            val message = ProtocolCodec.message(frame.payload); val body = message.json?.let(::JSONObject) ?: return
            if (message.type == 21 && source == ConversationSource.ALWAYS_ON && body.optString("cmd") == "life_log_switch_result") {
                val code = body.optJSONObject("payload")?.optInt("value", -1) ?: -1
                val nudge = rearmPending; rearmPending = false
                if (code != 0 && nudge) trace("종일 기록 다시 켜기 응답 코드 $code (기록은 계속)")
                else if (code != 0) failure("안경이 종일 기록을 거부함: " + when (code) { 1 -> "마이크 권한 꺼짐"; 2 -> "안경 접힘"; 3 -> "배터리 부족"; else -> "기기 코드 $code" })
            }
            return
        }
        if (frame.protocol != 0x0d) return
        val message = ProtocolCodec.message(frame.payload)
        val body = message.json?.let(::JSONObject) ?: JSONObject()
        when (message.type) {
            1 -> {
                nativeActive = true; nativeCode = body.optInt("rc"); wakeId = UUID.randomUUID().toString()
                lastReply = null; advancedReply = null; answering = false; acceptedWake = null; earlyAudio.clear(); replaying = false
                if (nativeCode in 1..6) wake()
            }
            3 -> if (source == ConversationSource.NATIVE && !replaying) message.audio?.let(audio)
                // Accepted early while always-on still stops (or its audio is being replayed): keep the order.
                else if (nativeActive && (replaying || acceptedWake != null && acceptedWake == wakeId)) message.audio?.let {
                    earlyAudio.addLast(it); while (earlyAudio.size > EARLY_AUDIO_FRAMES) earlyAudio.removeFirst()
                }
            8 -> { nativeActive = false; answering = false; acceptedWake = null; earlyAudio.clear(); replaying = false; if (source == ConversationSource.NATIVE) endRound(true) }
            11 -> if (answering && nativeActive) {
                // The answer finished without an interruption; we are already listening for the next turn.
                val sid = body.optString("sid")
                if (sid.isBlank() || sid == lastReply) {
                    answering = false
                    send(0x0d, 2, JSONObject().put("rc", 1)); send(0x0d, 9, JSONObject().put("rc", 1))
                }
            } else if (nativeActive && lastReply != null && advancedReply != lastReply) {
                val sid = body.optString("sid")
                if (sid.isNotBlank() && sid != lastReply) return
                advancedReply = lastReply; wakeId = UUID.randomUUID().toString(); wake()
            }
            161 -> if (source == ConversationSource.ALWAYS_ON) {
                // A new round while ours is still open: the glasses dropped it (e.g. their voice memo paused LifeLog
                // without an end). Ignoring this left LifeLog "on" on the glasses with no audio ever sent.
                if (lifeTask != null) { trace("종일 기록: 안경이 새 라운드를 시작해 이전 라운드를 닫고 이어 받음"); lifeTask = null; endRound(false) }
                val id = UUID.randomUUID().toString().lowercase(); lifeTask = id; lifeHeardAt = System.currentTimeMillis()
                beginRound()
                send(0x0d, 162, JSONObject().put("taskId", id).put("idleTimeoutSec", 300))
                send(0x0d, 168, JSONObject().put("inRealtimePage", false))
            }
            163 -> if (source == ConversationSource.ALWAYS_ON) {
                val task = body.optString("taskId")
                if (lifeTask == null || task.isNotBlank() && task != lifeTask) {
                    val key = task.ifBlank { "-" }
                    if (staleTaskReported != key) { staleTaskReported = key; trace("종일 기록: 열린 라운드가 아닌 오디오($key)라 무시") }
                    return
                }
                lifeHeardAt = System.currentTimeMillis()
                val bytes = message.audio ?: return
                val count = body.optInt("frameCount", bytes.size / 240)
                if (count !in 1..31 || bytes.size != count * 240) { failure("종일 기록 오디오 프레임 길이 이상으로 이번 라운드를 중지함"); return }
                if (body.has("vpuMask") || body.has("vadMask")) {
                    val vpu = body.optLong("vpuMask"); val vad = body.optLong("vadMask")
                    activity(List(count) { ((vpu shr it) and 1L) != 0L || ((vad shr it) and 1L) != 0L })
                }
                repeat(count) { index -> audio(bytes.copyOfRange(index * 240, (index + 1) * 240)) }
            }
            165 -> if (source == ConversationSource.ALWAYS_ON && (body.optString("taskId").isBlank() || body.optString("taskId") == lifeTask)) {
                lifeTask = null; endRound(false)
            }
        }
    }
    fun asr(text: String, expectedWake: String) {
        if (text.isBlank() || !nativeActive || wakeId != expectedWake) return
        interrupt(text)
        finalSent = expectedWake
        send(0x0d, 4, JSONObject().put("rc", 1))
        send(0x0d, 5, JSONObject().put("text", text).put("final", true))
        send(0x0d, 4, JSONObject().put("rc", 2)); send(0x0d, 9, JSONObject().put("rc", 3))
    }
    /** Live, still-changing transcript on the native assistant page, as the official app streams it. */
    fun partial(text: String, expectedWake: String) {
        if (text.isBlank() || !nativeActive || wakeId != expectedWake || finalSent == expectedWake) return
        interrupt(text)
        send(0x0d, 5, JSONObject().put("text", text).put("final", false))
    }
    /** Sustained voice level on the mic while the answer is presented (no transcript needed): cut the answer now. */
    fun voiceDuringAnswer() {
        if (!answering || !nativeActive) return
        answering = false
        trace("대화 끊기: 답변 중 목소리 감지(소리 크기)")
        send(0x0d, 9, JSONObject().put("rc", 1))
    }
    /** Speech heard while the previous answer is still presented: switch the glasses to listening. */
    private fun interrupt(text: String) {
        if (!answering) return
        // Ignore a single character (noise) and words that are part of the answer itself (its playback heard by the mic).
        val heard = text.filter { it.isLetterOrDigit() }
        if (heard.length < 2 || answerText.filter { it.isLetterOrDigit() }.contains(heard)) return
        answering = false
        trace("대화 끊기: 답변 중 발화 감지")
        send(0x0d, 9, JSONObject().put("rc", 1))
    }
    fun reply(text: String, query: String, expectedWake: String): Boolean {
        if (!nativeActive || wakeId != expectedWake) return false
        val listen = bargeIn()
        // With barge-in, capture stays open through the answer instead of closing and waiting for type 11.
        if (!listen) send(0x0d, 2, JSONObject().put("rc", 2))
        val body = JSONObject().put("sub", "workflow").put("vendor", "OpenRayneo").put("uuid", client)
            .put("sid", expectedWake.replace("-", "").lowercase()).put("round", -1).put("timestamp", System.currentTimeMillis() / 1000)
            .put("query", query).put("domain", "chat").put("intent", "chat").put("offline", false)
            .put("answer", JSONObject().put("text", text).put("isFinal", true))
        lastReply = expectedWake.replace("-", "").lowercase()
        send(0x0d, 9, JSONObject().put("rc", 3)); send(0x0d, 32, body); send(0x0d, 12, JSONObject())
        val answered = lastReply
        if (listen) {
            answerText = text
            main.post {
                if (!nativeActive || lastReply != answered || advancedReply == answered) return@post
                advancedReply = answered; answering = true; wakeId = UUID.randomUUID().toString(); wake()
            }
            // Neither an interruption nor the glasses' next-turn request: close the mic instead of listening forever.
            main.postDelayed({
                if (answering && lastReply == answered) { answering = false; trace("대화 끊기: 30초 동안 발화 없음, 수신 종료"); send(0x0d, 2, JSONObject().put("rc", 2)) }
            }, 30_000)
        }
        return true
    }
    private fun send(protocol: Int, type: Int, body: JSONObject) {
        if (protocol == 0x0d && !(type == 5 && !body.optBoolean("final"))) trace("음성 이벤트 송신 type=$type ${body.toString().take(160)}")
        try { write(ProtocolCodec.display(protocol, sequence++ and 65535, type, body.toString())) { result ->
            result.onFailure { failure("안경 음성 제어 전송 실패: ${it.message}") }
        } } catch (error: Exception) { failure("안경 음성 제어 전송 실패: ${error.message}") }
    }
}

/**
 * Level-based barge-in on the native mic (PCM16, 48 kHz mono): while armed, [minMs] of consecutive 10 ms
 * frames at or above [thresholdDb] dBFS count as the wearer talking over the answer. Fires once per arming.
 * The peak level seen while armed is kept so the log shows what the mic heard (e.g. the answer's own playback).
 */
internal class BargeInMeter {
    @Volatile private var armed = false
    @Volatile private var thresholdDb = -30.0
    @Volatile private var minMs = 250
    @Volatile var peakDb = -120.0; private set
    private var loudMs = 0
    fun arm(threshold: Double, minimum: Int) { thresholdDb = threshold; minMs = minimum; loudMs = 0; peakDb = -120.0; armed = true }
    fun disarm() { armed = false }
    /** Audio thread. Returns true once when sustained speech is detected while armed. */
    fun feed(pcm: ShortArray): Boolean {
        if (!armed || pcm.isEmpty()) return false
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + 480, pcm.size)
            var power = 1e-9
            for (i in start until end) power += pcm[i].toDouble() * pcm[i]
            val db = 10 * kotlin.math.log10(power / (end - start)) - 90.309   // 20*log10(32768)
            if (db > peakDb) peakDb = db
            loudMs = if (db >= thresholdDb) loudMs + 10 else 0
            if (loudMs >= minMs) { armed = false; return true }
            start = end
        }
        return false
    }
}

private const val EARLY_AUDIO_FRAMES = 1_000
private const val REPLAY_SLICE = 100
private const val REPLAY_SLICE_MS = 400L
private const val REARM_WAIT_MS = 8_000L
