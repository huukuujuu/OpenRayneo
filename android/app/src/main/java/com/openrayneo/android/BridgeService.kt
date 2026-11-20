// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.time.LocalTime

internal class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@BridgeService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    val observers = mutableSetOf<() -> Unit>()
    val devices = linkedMapOf<String, RayneoConnection.Device>()
    val logs = ArrayDeque<String>()
    private val logFile by lazy { AppLogFile(this) }
    /** The newest part of the persisted app log (survives restarts), for sharing or the PC log endpoint. */
    fun logText(maxBytes: Int = 2_000_000): String = logFile.tail(maxBytes)
    lateinit var settings: AppSettings; private set
    lateinit var conversations: ConversationController; private set
    lateinit var voiceTransport: GlassesVoiceTransport; private set
    lateinit var requests: GlassesRequests; private set
    lateinit var deviceSettings: GlassesDeviceSettings; private set
    private val bargeMeter = BargeInMeter()
    lateinit var teleprompter: TeleprompterLibrary; private set
    private lateinit var voiceMemo: VoiceMemo
    private val virtualVoice by lazy { VirtualVoice(this) }
    lateinit var agentServer: LocalAgentServer; private set
    lateinit var recordSync: RecordSyncServer; private set
    lateinit var content: CompanionContent; private set
    lateinit var anki: AnkiReview; private set
    private lateinit var supervisor: ConnectionSupervisor
    val connection get() = supervisor.transport
    private lateinit var display: GlassesDisplay
    private lateinit var audio: PromptAudio
    private lateinit var voiceAudio: PromptAudio
    val navigation get() = NavigationFeed
    lateinit var agent: MobileAgent; private set
    lateinit var codex: CodexClient; private set
    lateinit var translator: LiveTranslator; private set
    var state = GlassesState(); private set
    val status get() = state.link.detail
    val ready get() = state.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val busy get() = state.link.phase in setOf(LinkPhase.SCANNING, LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    val displayStatus get() = state.displayDescription
    val audioStatus get() = state.audio.detail
    val audioLevels get() = if (voiceAudio.state.active) voiceAudio.state else audio.state
    private val events = ArrayDeque<GlassesEvent>()
    private var dispatching = false
    private var destroyed = false
    private var initialized = false
    private var foreground = false
    private var stopAfterDisconnect = false
    private var restoreTask: Runnable? = null
    private var autoClearFor: AnswerRef? = null
    private var clearAnswerTask: Runnable? = null
    private var closeTask: Runnable? = null
    private var audioOwner: Long? = null
    private var nextAnswer = 0L
    private val serviceInstance = java.util.UUID.randomUUID().toString()
    private var latestRealtimeRequest = 0L
    /** The newest realtime request whose answer reached the glasses: older answers arriving later are dropped. */
    private var shownRealtimeRequest = 0L
    // Listening page transcript (main thread): recent finished sentences and each channel's unfinished one.
    private val heardFinals = ArrayDeque<String>()
    private val heardPartials = sortedMapOf<Int, Pair<String, Long>>()
    private fun heard(channel: Int, text: String, final: Boolean) {
        if (!conversationOwnsDisplay) return
        val value = text.trim(); val now = System.currentTimeMillis()
        if (final) {
            // A final can arrive after the next sentence's partials (speaker analysis delays it): keep those.
            heardPartials[channel]?.first?.let { if (it.take(6) == value.take(6)) heardPartials.remove(channel) }
            heardFinals.addLast(value); while (heardFinals.size > 4) heardFinals.removeFirst()
        } else heardPartials[channel] = value to now
        // A round that ended without a final (error, noise) must not leave its partial on screen.
        heardPartials.entries.removeAll { now - it.value.second > STALE_PARTIAL_MS }
        val transcript = (heardFinals + heardPartials.values.map { it.first }).filter { it.isNotEmpty() }.joinToString(" ")
        dispatch(GlassesEvent.StandbyText(TranslationView(transcript, "듣는 중")))
    }
    private var standbyAnswerTask: Runnable? = null
    private var standbyAnswerKey: Pair<AnswerRef, Int>? = null
    /** Turns the pages of the listening page's answer, then removes it; paused while another page is in front. */
    private fun updateStandbyAnswerTimer() {
        val answer = state.standbyAnswer
        val visible = answer != null && state.intent == DisplayIntent.PromptStandby && state.session?.mode == PageMode.PROMPT &&
            state.ownership == DisplayOwnership.APP && state.link.phase == LinkPhase.FULL && !state.disconnecting
        val key = answer?.let { it.ref to it.page }
        if (!visible || key != standbyAnswerKey) { standbyAnswerTask?.let(main::removeCallbacks); standbyAnswerTask = null; standbyAnswerKey = null }
        if (!visible || answer == null || standbyAnswerTask != null) return
        val last = answer.page >= answer.pages.lastIndex
        val setting = settings.read().optInt("answerClearSeconds", 0).coerceIn(0, 3600)
        val delay = if (!last) ANSWER_PAGE_MS else (if (setting == 0) REALTIME_ANSWER_HOLD_SECONDS else setting) * 1000L
        standbyAnswerKey = key
        standbyAnswerTask = Runnable {
            standbyAnswerTask = null; standbyAnswerKey = null
            dispatch(if (last) GlassesEvent.ClearStandbyAnswer(answer.ref) else GlassesEvent.StandbyAnswerPage(answer.ref, answer.page + 1))
        }.also { main.postDelayed(it, delay) }
    }
    /** Bumped each time the listening page opens: answers to requests from before it (e.g. hint mode) are not shown. */
    private var listeningEpoch = 0L
    private fun openListening() {
        listeningEpoch++; heardFinals.clear(); heardPartials.clear()
        conversationOwnsDisplay = true; dispatch(GlassesEvent.OpenStandby)
    }
    private val pendingAnswers = mutableMapOf<AnswerSource, AnswerPermit>()
    private data class VoiceDelivery(val ref: AnswerRef, val source: ConversationSource, val wakeId: String?)
    private var voiceReply: VoiceDelivery? = null
    private var conversationOwnsDisplay = false
    private var resumeAlwaysOn: ConversationMode? = null
    private var notificationText = ""
    private var notificationReadStatus = ""
    private val navigationObserver: () -> Unit = {
        if (notificationReadStatus != navigation.listenerState.detail) {
            notificationReadStatus = navigation.listenerState.detail
            onLog(notificationReadStatus)
        }
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
    }
    private val notificationRefresh = object : Runnable {
        override fun run() {
            if (navigation.enabled) NavigationNotifications.refresh(this@BridgeService)
            content.automaticWeather()
            content.calendarTick()
            main.postDelayed(this, 30_000)
        }
    }
    private val audioRefresh = object : Runnable {
        override fun run() {
            audioOwner?.let { dispatch(GlassesEvent.AudioChanged(it, audio.state.coordinatorState())) }
            if (conversations.state.running) audioLevels.error?.let { conversations.transportError(it) }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        LocalModels.keepResident(this, settings.read())
        supervisor = ConnectionSupervisor(this, main,
            { dispatch(GlassesEvent.LinkChanged(it)) }, { devices[it.bluetooth.address] = it; notifyUI() },
            { frame, source -> runCatching {
                teleprompter.observe(frame)
                voiceMemo.receive(frame)
                requests.receive(frame)
                // Assistant control events (not audio) go to the app log, to see how the firmware behaves around barge-in.
                if (frame.protocol == 0x0d) ProtocolCodec.message(frame.payload).let { m ->
                    if (m.type !in setOf(3, 163, 164)) onLog("음성 이벤트 수신 type=${m.type} ${m.json.orEmpty().take(160)}")
                }
                voiceTransport.receive(frame)
                display.receive(frame, source)
            }.onFailure { onLog("안경 메시지 파싱 실패: $source") } }, ::onLog)
        requests = GlassesRequests(main, connection::sendFrame)
        deviceSettings = GlassesDeviceSettings(requests, ::notifyUI)
        content = CompanionContent(this, requests, { connection.resolvedClassicAddress?.takeIf { state.link.phase == LinkPhase.FULL } }, ::notifyUI)
        // Cards use the answer page; without the glasses the review runs on the phone screen only.
        anki = AnkiReview(this, main, ::notifyUI, { title, body ->
            if (state.link.phase == LinkPhase.FULL && !state.disconnecting)
                dispatch(GlassesEvent.RevealAnswer(AnswerContent(AnswerRef(AnswerSource.FLASHCARD, ++nextAnswer), title, body)))
        }, { if ((state.intent as? DisplayIntent.Answer)?.ref?.source == AnswerSource.FLASHCARD) clearDisplay() })
        teleprompter = TeleprompterLibrary(this, requests, ::notifyUI)
        agentServer = LocalAgentServer(main, settings, ::notifyUI, {
            JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("commit", BuildConfig.GIT_COMMIT)
                .put("phoneId", nativeIdentity().getString("phoneId")).put("link", state.link.phase.name).put("display", state.displayDescription).put("conversationRunning", conversations.state.running)
        }, {
            org.json.JSONArray(conversations.repository.records.map { it.json().apply { remove("speakerSamples") } })
        }, { name, args, allowed, completion -> requests.withPermission(allowed) { executeTool(name, args, completion) } }, { agent.harness }, ::recordAPI)
        requests.observe = { protocol, type, body ->
            when (protocol) { 15 -> deviceSettings.observe(type, body); 22 -> content.observe(type, body) }
            // The glasses' teleprompter requests are not mapped from a real device yet: log the first of each kind.
            if (protocol == 0x14 && body.optInt("action") == 1) {
                val key = "$protocol:$type"
                val count = glassesRequestSeen.merge(key, 1, Int::plus) ?: 1
                if (count <= 5) onLog("안경 요청 수신: 0x${protocol.toString(16)} type=$type ${body.toString().take(240)}")
            }
        }
        conversations = ConversationController(this, settings, ::conversationChanged, ::conversationCapture, ::submitVoiceRequest,
            { segment, source ->
                if (source == ConversationSource.NATIVE) segment.roundId?.let { voiceTransport.asr(segment.text, it) }
                // Listening page: everything heard is transcribed live, like captions; answers appear below it.
                if (source == ConversationSource.REALTIME) heard(segment.sourceChannel, segment.text, final = true)
            }, ::resumeBackground)
        conversations.partial = { text, wake -> main.post { voiceTransport.partial(text, wake) } }
        conversations.livePartial = { channel, text -> main.post { heard(channel, text, final = false) } }
        recordSync = RecordSyncServer(settings, conversations.repository, conversations.recordings, main, ::notifyUI, ::logText)
        if (settings.read().optBoolean("recordSyncEnabled")) recordSync.start()
        voiceAudio = PromptAudio { left, right ->
            if (virtualVoice.active) return@PromptAudio
            if (bargeMeter.feed(left)) main.post { voiceTransport.voiceDuringAnswer() }
            conversations.accept(left, right)
        }
        voiceTransport = GlassesVoiceTransport(connection::sendFrame, ::nativeWake, {
            voiceAudio.stop(); conversations.voiceRoundBoundary(); voiceAudio.start(1); conversations.nextRound()
        }, { ended ->
            if (ended) conversations.stop() else { voiceAudio.stop(); conversations.voiceRoundBoundary(); conversations.endRound() }
        }, { voiceAudio.accept(it) }, { conversations.voiceActivity(it) }, { conversations.transportError(it) }, ::onLog)
        // Only when the posted next turn will really start (auto answer on, native session running); otherwise capture closes as before.
        voiceTransport.answeringChanged = { on ->
            val cfg = settings.read()
            if (on) bargeMeter.arm(cfg.optString("bargeInLevelDb").toDoubleOrNull() ?: -30.0, cfg.optString("bargeInMinMs").toIntOrNull() ?: 250)
            else { bargeMeter.disarm(); onLog("대화 끊기 진단: 답변 중 마이크 최대 ${bargeMeter.peakDb.toInt()} dBFS (감지 기준 ${cfg.optString("bargeInLevelDb").ifBlank { "-30" }} dBFS)") }
        }
        voiceTransport.idleCloseSeconds = { cachedSettings().optString("nativeIdleCloseSeconds").toIntOrNull() ?: DEFAULT_NATIVE_IDLE_CLOSE_SECONDS }
        voiceTransport.answersWakes = { cachedSettings().optBoolean("nativeAutoAnswer") }
        voiceTransport.bargeIn = { deviceSettings.state.settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("workMode") == true &&
            settings.read().optBoolean("nativeAutoAnswer") && conversations.state.running && conversations.state.source == ConversationSource.NATIVE }
        translator = LiveTranslator(this, main, { source, target, finals, final -> dispatch(GlassesEvent.TranslationText(TranslationView(source, target, finals, final))) }, ::translatorChanged) {
            if (state.intent == DisplayIntent.Translation) dispatch(GlassesEvent.PauseDisplay)
        }
        audio = PromptAudio { left, right ->
            if (virtualVoice.active) return@PromptAudio
            if (translator.active) translator.accept(left, right)
            else if (conversations.state.running && conversations.state.source == ConversationSource.REALTIME) conversations.accept(left, right)
        }
        conversations.backfillHold = { translator.active }
        voiceMemo = VoiceMemo(this, conversations.repository, connection::sendFrame, ::onLog) {
            conversations.refreshRecords(); conversations.configurationChanged()
            // The memo had the microphone: LifeLog stays "on" on the glasses but sent nothing afterwards (device).
            if (conversations.state.running && conversations.state.source == ConversationSource.ALWAYS_ON && !conversations.state.stopping)
                voiceTransport.rearmLifeLog()
        }
        display = GlassesDisplay(connection::sendFrame,
            { task, delay -> main.postDelayed(task, delay) }, { main.removeCallbacks(it) }, ::onLog,
            { id, signal -> dispatch(GlassesEvent.SessionSignal(id, signal)) },
            { id, packet -> if (audioOwner == id && state.session?.id == id) audio.accept(packet) },
            { dispatch(GlassesEvent.ExternalPage(it)) },
            { protocol, type, body ->
                val sid = body?.optString("sid").orEmpty()
                if (type == 1 && sid.isNotBlank()) main.post { glassesStartRequest(protocol, sid, body?.optJSONObject("settings")?.toString()) }
                // The glasses gave up on a request we have not answered yet.
                if (type == 3 && sid.isNotBlank()) main.post { if (state.offer?.sid == sid) { dispatch(GlassesEvent.ClearOffer(sid, decline = false)); onLog("안경이 시작 요청을 취소함") } }
            })
        navigation.initialize(this)
        navigation.stop()
        navigation.observers.add(navigationObserver)
        agent = MobileAgent(settings, conversations.repository, { question, answer, input -> deliverExternalVoice(question, answer, input) }, ::notifyUI, { name, args, allowed, completion -> requests.withPermission(allowed) { executeTool(name, args, completion) } }) { question, answer -> completed(AnswerSource.MOBILE_AGENT, question, answer) }
        if (settings.read().optBoolean("localAgentEnabled")) agentServer.start()
        codex = CodexClient(settings, ::notifyUI) { question, answer -> completed(AnswerSource.COMPUTER_CODEX, question, answer) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("bridge", "안경 연결 및 내비게이션", NotificationManager.IMPORTANCE_LOW))
        initialized = true
        dispatch(GlassesEvent.NavigationChanged(navigationSnapshot()))
        NavigationNotifications.refresh(this)
        main.postDelayed(notificationRefresh, 30_000)
        main.postDelayed(audioRefresh, 1_000)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cancelAnswer(AnswerSource.MOBILE_AGENT); closeCodex()
            dispatch(GlassesEvent.StopNavigation)
            stopAfterDisconnect = true
            disconnect()
        } else {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (PhoneLocation.allowed(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            foreground = true
        }
        return START_NOT_STICKY
    }

    fun enableLocationWhileVisible() {
        if (foreground && PhoneLocation.allowed(this))
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun dispatch(event: GlassesEvent) {
        if (destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dispatch(event) }; return }
        events.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (events.isNotEmpty()) {
                val transition = GlassesCoordinator.reduce(state, events.removeFirst())
                val previous = state
                state = transition.state
                if (previous.link.phase == LinkPhase.FULL && state.link.phase != LinkPhase.FULL) {
                    conversations.stop(); voiceTransport.disconnected(); voiceAudio.stop()
                    requests.disconnected(); deviceSettings.disconnected(); content.disconnected(); teleprompter.disconnected(); voiceMemo.disconnected()
                    if (state.offer != null) events.add(GlassesEvent.ClearOffer(null, decline = false))
                }
                if (previous.link.phase != LinkPhase.FULL && state.link.phase == LinkPhase.FULL) { content.connected(); teleprompter.connected(); runCatching { deviceSettings.refresh() } }
                if (previous.session != null && state.session == null && previous.session.mode == PageMode.PROMPT && conversations.state.running && conversations.state.source == ConversationSource.REALTIME && conversationOwnsDisplay &&
                    (state.ownership != DisplayOwnership.APP || state.intent == DisplayIntent.Idle || state.intent == DisplayIntent.Navigation || state.error != null)) {
                    conversationOwnsDisplay = false; conversations.stop()
                }
                // The translator runs only while the glasses show its window; any other page, link loss or yield stops it.
                // On link loss the intent would otherwise survive and reopen an orphan window after reconnecting.
                if (state.intent == DisplayIntent.Translation && (state.link.phase != LinkPhase.FULL || state.ownership != DisplayOwnership.APP)) {
                    translator.stop(); events.add(GlassesEvent.PauseDisplay)
                } else if (translator.active && state.intent != DisplayIntent.Translation) translator.stop()
                updateAnswerClearTimer()
                updateStandbyAnswerTimer()
                if (resumeAlwaysOn != null && previous.intent != DisplayIntent.Idle && state.intent == DisplayIntent.Idle) main.post(::resumeBackground)
                if (state.displayDescription != previous.displayDescription && state.displayDescription != state.link.detail) onLog(state.displayDescription)
                transition.effects.forEach { effect ->
                    runCatching { execute(effect) }.onFailure { error ->
                        val message = error.message ?: "안경 작업 실패"
                        onLog(message)
                        when (effect) {
                            is GlassesEffect.StartPage -> events.add(GlassesEvent.SessionSignal(effect.session.id, PageSignal.Failed(message)))
                            is GlassesEffect.UpdatePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Failed(message)))
                            is GlassesEffect.ClosePage -> events.add(GlassesEvent.SessionSignal(effect.id, PageSignal.Closed(message)))
                            is GlassesEffect.StartAudio -> events.add(GlassesEvent.AudioChanged(effect.id, AudioState(AudioPhase.FAILED, message)))
                            is GlassesEffect.FollowNavigation -> events.add(GlassesEvent.StopNavigation)
                            else -> Unit
                        }
                    }
                }
            }
        } finally { dispatching = false }
        updateNotification(); notifyUI()
        if (stopAfterDisconnect && state.link.phase == LinkPhase.DISCONNECTED && !state.disconnecting) {
            stopAfterDisconnect = false
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun execute(effect: GlassesEffect) {
        when (effect) {
            is GlassesEffect.StartPage -> display.start(effect.session)
            is GlassesEffect.UpdatePage -> display.update(effect.id, effect.content)
            is GlassesEffect.ClosePage -> display.stop(effect.id)
            GlassesEffect.ResetPage -> display.reset()
            is GlassesEffect.StartAudio -> { audioOwner = effect.id; audio.start(effect.channels) }
            GlassesEffect.StopAudio -> { audioOwner = null; audio.stop() }
            is GlassesEffect.FollowNavigation -> navigation.follow(effect.key)
            GlassesEffect.StopFollowing -> navigation.stop()
            is GlassesEffect.ScheduleRestore -> {
                restoreTask?.let(main::removeCallbacks)
                restoreTask = Runnable { dispatch(GlassesEvent.RestoreAnswer(effect.id, effect.revision, effect.ref)) }
                    .also { main.postDelayed(it, 12_000) }
            }
            GlassesEffect.CancelRestore -> { restoreTask?.let(main::removeCallbacks); restoreTask = null }
            is GlassesEffect.ScheduleCloseTimeout -> {
                closeTask?.let(main::removeCallbacks)
                closeTask = Runnable { dispatch(GlassesEvent.CloseTimedOut(effect.id)) }.also { main.postDelayed(it, 1_500) }
            }
            GlassesEffect.CancelCloseTimeout -> { closeTask?.let(main::removeCallbacks); closeTask = null }
            GlassesEffect.DisconnectTransport -> supervisor.disconnect()
            is GlassesEffect.DeclineOffer -> { display.decline(effect.offer.protocol, effect.offer.sid); onLog("응답하지 않은 안경 시작 요청을 종료함") }
        }
    }

    fun scan() {
        check(!ready && state.link.phase != LinkPhase.CONNECTING && !state.disconnecting) { "먼저 현재 연결을 끊으세요" }
        devices.clear(); supervisor.scan()
    }
    fun connect(device: RayneoConnection.Device, bleOnly: Boolean) {
        check(!ready && !state.disconnecting) { "먼저 현재 연결을 끊으세요" }
        startForegroundService(Intent(this, BridgeService::class.java))
        dispatch(GlassesEvent.BeginConnection)
        runCatching { supervisor.connect(device, bleOnly) }.onFailure {
            supervisor.disconnect(); onLog(it.message ?: "연결 실패")
        }
    }
    fun disconnect() {
        supervisor.cancelRetries()
        conversations.stop()
        dispatch(GlassesEvent.Disconnect)
    }
    fun followNavigation(key: String) {
        conversationOwnsDisplay = false
        if (conversations.state.running) conversations.stop()
        check(!state.disconnecting) { "연결 해제가 끝날 때까지 기다리세요" }
        val entry = checkNotNull(navigation.entries[key]) { "해당 알림이 사라졌습니다. Amap에서 내비게이션을 시작한 뒤 새로 고치세요" }
        val frame = entry.instruction?.let { NavigationFrame.render(it.direction, it.meters, entry.title) }
            ?: NavigationFrame.status("안내 미인식", "Amap 확인")
        dispatch(GlassesEvent.SelectNavigation(NavigationState(true, key, frame)))
    }
    fun stopNavigation() = dispatch(GlassesEvent.StopNavigation)
    fun reopenNavigation() {
        val key = navigation.selectedKey ?: error("먼저 따라갈 Amap 알림을 선택하세요")
        followNavigation(key)
    }
    fun clearDisplay() { autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null; dispatch(GlassesEvent.ClearDisplay) }
    fun enableAgentServer(enabled: Boolean) {
        if (enabled) {
            check(checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "먼저 근처 기기 권한을 허용한 뒤 휴대폰 어시스턴트를 켜 주세요"
            }
            agentServer.start()
            startForegroundService(Intent(this, BridgeService::class.java))
        } else agentServer.stop()
    }
    fun startTeleprompter(title: String, text: String, speed: Int, documentId: String? = null, completion: (Result<JSONObject>) -> Unit) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        conversationOwnsDisplay = false; conversations.stop(); dispatch(GlassesEvent.PauseDisplay)
        val allowed = requests.permission()
        val deadline = SystemClock.elapsedRealtime() + 2_500
        val wait = object : Runnable {
            override fun run() {
                if (!allowed() || state.link.phase != LinkPhase.FULL || state.disconnecting) { completion(Result.failure(IllegalStateException("안경 연결이 끊김"))); return }
                if (state.session == null) {
                    val id = teleprompter.save(title, text, documentId ?: java.util.UUID.randomUUID().toString())
                    requests.withPermission(allowed) { teleprompter.start(id, speed, completion) }
                } else if (SystemClock.elapsedRealtime() < deadline) main.postDelayed(this, 50)
                else completion(Result.failure(IllegalStateException("이전 표시가 아직 끝나지 않았습니다. 잠시 후 다시 재생하세요")))
            }
        }; main.post(wait)
    }
    fun startConversation(mode: ConversationMode, source: ConversationSource, target: VoicePerson? = null) {
        val fromGlasses = liveAnswersNext; liveAnswersNext = false
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        conversations.start(mode, source, target, if (source == ConversationSource.NATIVE) voiceTransport.wakeId else null)
        if (source == ConversationSource.REALTIME) liveAnswers = fromGlasses
    }
    private fun nativeWake() {
        notifyUI()
        if (!settings.read().optBoolean("nativeAutoAnswer")) return
        if (state.link.phase != LinkPhase.FULL || state.disconnecting) return
        if (conversations.state.running) {
            if (conversations.state.source == ConversationSource.ALWAYS_ON || conversations.state.source == ConversationSource.VOICE_MEMO) {
                // A second wake while always-on is already stopping for the first keeps the plan to resume it.
                if (conversations.state.source == ConversationSource.ALWAYS_ON)
                    resumeAlwaysOn = conversations.state.mode.takeUnless { conversations.state.stopping } ?: resumeAlwaysOn
                val wake = voiceTransport.wakeId
                // Stopping always-on takes a second or more; answer the glasses now so they stream the question,
                // which is kept and transcribed once native capture starts (it was lost before).
                voiceTransport.acceptWake()
                onLog("깨우기: ${conversations.state.source.title}을 멈추고 전환하는 동안 질문 오디오를 보관")
                conversations.handoffForWake {
                    val started = voiceTransport.nativeActive && voiceTransport.wakeId == wake && state.link.phase == LinkPhase.FULL && !state.disconnecting &&
                        runCatching { startConversation(ConversationMode.DAILY, ConversationSource.NATIVE) }
                            .onFailure { conversations.report(it.message ?: "깨우기 전환 실패") }.isSuccess
                    // The glasses were told to listen early: release them when native capture did not take over.
                    if (!started) { voiceTransport.releaseWake(wake); resumeBackground() }
                }
                return
            }
            if (conversations.state.source != ConversationSource.NATIVE) return
            conversations.nextRound(voiceTransport.wakeId); agent.harness.cancel("route"); agent.harness.cancel("hint"); voiceAudio.stop(); voiceAudio.start(1); voiceTransport.start(ConversationSource.NATIVE)
        } else {
            try { startConversation(ConversationMode.DAILY, ConversationSource.NATIVE) }
            catch (error: Exception) { conversations.report(error.message ?: "기본 응답 수신 실패") }
        }
    }
    private fun resumeBackground() {
        val mode = resumeAlwaysOn ?: return
        if (destroyed || state.disconnecting || state.link.phase != LinkPhase.FULL) { resumeAlwaysOn = null; return }
        // A glasses page (captions, hint) is still open: the dispatch loop calls again once it closes.
        if (translator.active || state.intent != DisplayIntent.Idle) return
        if (voiceTransport.nativeActive || conversations.state.running) { main.removeCallbacks(resumeRetry); main.postDelayed(resumeRetry, 2_000); return }
        resumeAlwaysOn = null
        startConversation(mode, ConversationSource.ALWAYS_ON)
    }
    private val resumeRetry = Runnable { resumeBackground() }
    private fun conversationCapture(source: ConversationSource, start: Boolean) {
        if (start) {
            when (source) {
                ConversationSource.REALTIME -> openListening()
                // Voice memo records are only ever re-transcribed from saved audio, never captured live here.
                ConversationSource.VOICE_MEMO -> Unit
                ConversationSource.NATIVE, ConversationSource.ALWAYS_ON -> {
                    conversationOwnsDisplay = false
                    dispatch(GlassesEvent.PauseDisplay); voiceAudio.start(1); voiceTransport.start(source)
                }
            }
        } else {
            if (source == ConversationSource.NATIVE && voiceTransport.nativeActive) resumeAlwaysOn = null
            agent.harness.cancel("route"); agent.harness.cancel("hint")
            if (source == ConversationSource.REALTIME) {
                if (conversationOwnsDisplay) dispatch(GlassesEvent.PauseDisplay)
                conversationOwnsDisplay = false
            } else { voiceAudio.stop(); voiceTransport.stop() }
        }
    }
    private fun submitVoiceRequest(request: VoiceRequest): Boolean? {
        val identity = nativeIdentity()
        val input = JSONObject().put("source", request.source.name).put("wakeId", request.wakeId)
            .put("recordId", request.recordId).put("sourceRevision", request.sourceRevision).put("backfill", request.backfill)
            .put("contextKey", identity.getString("conversationKey"))
        if (request.source == ConversationSource.NATIVE && !request.backfill) {
            input.put("phoneId", identity.getString("phoneId")).put("conversationKey", identity.getString("conversationKey"))
        }
        var context = request.context
        if (request.source == ConversationSource.REALTIME) {
            // Live answers suggests replies to what others ask the wearer; the app's realtime hint answers only the wearer.
            if (liveAnswers) context = runCatching { JSONObject(context).put("mode", "live_answers").toString() }.getOrDefault(context)
            latestRealtimeRequest = ++nextAnswer
            input.put("serviceInstance", serviceInstance).put("displayRevision", state.revision).put("realtimeRequest", latestRealtimeRequest).put("listeningEpoch", listeningEpoch)
        }
        // The glasses window shows only a few lines; the DSH plugin asks the Agent to fit them (0 = no limit).
        input.put("answerMaxChars", settings.read().optString("answerMaxChars").toIntOrNull() ?: DEFAULT_ANSWER_MAX_CHARS)
        // The relay's encrypted outbox accepts work even while DSH or the network is unavailable.
        agent.harness.submit(request.text, context, input = input) { result ->
            result.fold({ answer -> deliverExternalVoice(request.text, answer, input) }, { conversations.report(it.message ?: "DSH Go가 작업을 끝내지 못했습니다") })
        }
        return true
    }
    private fun deliverExternalVoice(question: String, answer: String, input: JSONObject) {
        if (answer.isBlank()) return
        val id = input.optString("recordId")
        if (id.isNotBlank()) {
            conversations.repository.applyDerived(RecordDerived(id = input.optString("derivedId").ifBlank { java.util.UUID.randomUUID().toString() }, recordId = id, sourceRevision = input.optInt("sourceRevision", 1),
                kind = "analysis", text = answer, agent = "phone-local-agent"))
            conversations.refreshRecords()
        }
        val source = ConversationSource.valueOf(input.optString("source", "ALWAYS_ON"))
        val conversationKey = input.optString("conversationKey")
        if (source == ConversationSource.NATIVE && conversationKey.isNotBlank() && conversationKey != nativeIdentity().getString("conversationKey")) return
        if (input.optString("contextKey") == nativeIdentity().getString("conversationKey")) conversations.rememberReply(question, answer)
        val wake = input.optString("wakeId").takeUnless { it.isBlank() || it == "null" }
        if (source == ConversationSource.NATIVE && wake != null && wake == voiceTransport.wakeId && voiceTransport.nativeActive)
            voiceTransport.reply(answer, question, wake)
        else if (source == ConversationSource.REALTIME && input.optString("serviceInstance") == serviceInstance &&
            // Every heard sentence sends a request, and most end as "wait": a later sentence must not drop this answer,
            // only a newer answer already shown does, and only answers to this listening page count.
            input.optLong("realtimeRequest") > shownRealtimeRequest && input.optLong("listeningEpoch", -1) == listeningEpoch &&
            conversations.state.running && conversations.state.activeRecord?.id == id && conversationOwnsDisplay &&
            state.ownership == DisplayOwnership.APP && state.link.phase == LinkPhase.FULL && !state.disconnecting) {
            // The answer joins the listening page under the live transcript; the page itself never changes.
            val ref = AnswerRef(AnswerSource.MOBILE_AGENT, input.getLong("realtimeRequest"))
            shownRealtimeRequest = ref.taskId
            dispatch(GlassesEvent.StandbyAnswer(AnswerContent(ref, question, answer)))
        } else if (state.link.phase == LinkPhase.FULL && !state.disconnecting) connection.sendNotification("어시스턴트", answer)
    }
    fun pausePrompt() { conversations.stop(); dispatch(GlassesEvent.PauseDisplay) }
    fun startTranslation(captions: Boolean = false, glassesPage: Boolean = false) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        val config = settings.read()
        translator.validate(config, captions)
        conversationOwnsDisplay = false
        if (conversations.state.running) conversations.stop()
        translator.start(config, captions, fullLines = glassesPage)
        dispatch(GlassesEvent.StartTranslation(captions, glassesPage))
    }
    /** Live captions: the glasses' own caption feature needs the phone to recognise speech, so the app runs it here. */
    fun startCaptions() = startTranslation(captions = true)
    private var lastGlassesStart: String? = null
    private val glassesRequestSeen = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /**
     * The glasses menu opened Live captions (0x13) or Live answers (0x17) and sent its own start request (type 1 with its sid).
     * Official app 1.0.25: the phone answers with type 2 on that sid; a request it will not serve is closed with type 3.
     */
    private fun glassesStartRequest(protocol: Int, sid: String, settings: String?) {
        if (protocol != 0x13 && protocol != 0x17 || sid == lastGlassesStart) return
        lastGlassesStart = sid
        val feature = if (protocol == 0x13) "실시간 자막" else "실시간 답변"
        // Never end something the user started for a request from the glasses menu.
        // Background voice records (wake, all-day, voice-memo transcription) yield; the realtime hint the user opened does not.
        val busy = when {
            conversations.state.running && conversations.state.source == ConversationSource.REALTIME -> "실시간 힌트 기록 실행 중"
            translator.active -> if (translator.captions) "앱 실시간 자막 실행 중" else "실시간 번역 실행 중"
            state.intent != DisplayIntent.Idle && state.intent != DisplayIntent.PromptStandby -> "다른 안경 화면 표시 중"
            else -> null
        }
        val error = busy ?: runCatching {
            // Check what can still refuse before touching the user's page.
            check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "안경 연결이 완전하지 않음" }
            val config = this.settings.read()
            // Live captions translate Korean <-> English when the summary model is set, otherwise they only transcribe.
            val translate = protocol == 0x13 && runCatching { translator.validate(config, captions = false) }.isSuccess
            if (protocol == 0x13 && !translate) translator.validate(config, captions = true)
            // Our idle/standby page would keep the business busy and the request unanswered: close it so the new page answers it.
            if (state.session != null) dispatch(GlassesEvent.PauseDisplay)
            val final = settings?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            if (translate) final.put("source_language", "ko-KR").put("target_language", "en-US")
            dispatch(GlassesEvent.GlassesOffered(GlassesOffer(if (protocol == 0x13) PageMode.CAPTION else PageMode.PROMPT, sid, final.toString())))
            val begin = { if (protocol == 0x13) startTranslation(captions = !translate, glassesPage = true) else startRealtimeHint(fromGlasses = true) }
            if (conversations.state.running) {
                onLog("안경 메뉴의 $feature 요청: ${conversations.state.source.title} 기록을 멈추고 시작 (남은 전사는 나중에 보충)")
                if (conversations.state.source == ConversationSource.ALWAYS_ON)
                    resumeAlwaysOn = conversations.state.mode.takeUnless { conversations.state.stopping } ?: resumeAlwaysOn
                conversations.handoffForWake {
                    // The 8 s answer window may have closed (request refused) while the record was stopping.
                    if (state.offer?.sid != sid) return@handoffForWake
                    runCatching(begin).onFailure { error ->
                        dispatch(GlassesEvent.ClearOffer(sid, decline = true)); onLog("안경 메뉴의 $feature 요청을 시작하지 못함: ${error.message}")
                    }
                }
            } else begin()
        }.exceptionOrNull()?.let { it.message ?: "시작 실패" }
        if (error == null) {
            onLog("안경 메뉴의 $feature 요청 → 안경 화면에서 시작")
            // Realtime hint opens its page only after its worker starts; a request still unanswered by then is refused.
            main.postDelayed({ if (state.offer?.sid == sid) { dispatch(GlassesEvent.ClearOffer(sid, decline = true)); onLog("안경 메뉴의 $feature 요청에 제때 답하지 못해 종료함") } }, 8_000)
            return
        }
        if (state.offer?.sid == sid) dispatch(GlassesEvent.ClearOffer(sid, decline = false))
        display.decline(protocol, sid)
        onLog("안경 메뉴의 $feature 요청을 거절함: $error")
    }
    // TEMPORARY test aid (remove after the glasses-menu verification): virtual voice into whatever is listening now.
    fun speakVirtual(text: String) {
        require(text.isNotBlank()) { "말할 문장을 입력하세요" }
        check(!virtualVoice.active) { "가상 음성을 이미 재생 중입니다" }
        virtualTarget() ?: error(NO_VIRTUAL_TARGET)
        virtualVoice.synthesize(text.trim()) { result ->
            main.post { result.fold({ playVirtual(it) }, { onLog("가상 음성 만들기 실패: ${it.message}") }) }
        }
    }
    fun injectVirtualWav(bytes: ByteArray) {
        check(!virtualVoice.active) { "가상 음성을 이미 재생 중입니다" }
        virtualTarget() ?: error(NO_VIRTUAL_TARGET)
        playVirtual(VirtualVoice.wav(bytes))
    }
    fun stopVirtualVoice() = virtualVoice.stop()
    /** Where decoded glasses audio would go right now, same priority as the real consumers. */
    private fun virtualTarget(): String? = when {
        translator.active -> if (translator.captions) "실시간 자막" else "실시간 번역"
        voiceMemo.recording -> "음성 메모"
        conversations.state.running && !conversations.state.stopping && conversations.state.source != ConversationSource.VOICE_MEMO -> conversations.state.source.title
        else -> null
    }
    private fun playVirtual(pcm: ShortArray) {
        val target = virtualTarget() ?: run { onLog(NO_VIRTUAL_TARGET); return }
        if (virtualVoice.active) { onLog("가상 음성을 이미 재생 중입니다"); return }
        onLog("가상 음성 재생 → $target (${pcm.size / VirtualVoice.RATE}초)")
        voiceMemo.injecting = true
        val silence = ShortArray(0)
        virtualVoice.play(pcm, { chunk ->
            when {
                translator.active -> { translator.accept(chunk, chunk); true }
                voiceMemo.recording -> voiceMemo.inject(chunk)
                conversations.state.running && conversations.state.source == ConversationSource.REALTIME -> {
                    // Same channel count as the glasses page audio; with two channels the wearer is channel 1, the other hears nothing.
                    conversations.accept(chunk, if (audio.state.channels == 2) ShortArray(chunk.size) else silence); true
                }
                conversations.state.running && conversations.state.source != ConversationSource.VOICE_MEMO -> { conversations.accept(chunk, silence); true }
                else -> false
            }
        }) { error -> main.post { if (!virtualVoice.active) voiceMemo.injecting = false; onLog(error ?: "가상 음성 재생 끝") } }
    }
    fun stopTranslation() { translator.stop(); if (state.intent == DisplayIntent.Translation) dispatch(GlassesEvent.PauseDisplay) }
    fun openStandby() = dispatch(GlassesEvent.OpenStandby)
    /** One-tap realtime hint: open the window and start the realtime dual-mic record that actually processes speech. */
    /** The running realtime record was opened by the glasses' Live answers menu (its own prompts in DSH), not the app button. */
    private var liveAnswers = false
    /** Main thread: the next REALTIME record is the glasses' Live answers. */
    private var liveAnswersNext = false
    fun startRealtimeHint(fromGlasses: Boolean = false) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        if (conversations.state.running && conversations.state.source == ConversationSource.REALTIME) {
            liveAnswers = fromGlasses
            openListening(); return
        }
        check(!conversations.state.running) { "다른 기록(${conversations.state.source.title})이 실행 중입니다. 기록 탭에서 먼저 중지하세요" }
        translator.stop()
        liveAnswersNext = fromGlasses
        startConversation(ConversationMode.DAILY, ConversationSource.REALTIME)
    }
    fun retryAudio() {
        check(state.link.phase == LinkPhase.FULL && state.session?.phase in setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY)) {
            "먼저 내비게이션 또는 실시간 힌트를 시작하고 안경의 확인을 기다리세요"
        }
        dispatch(GlassesEvent.RetryAudio)
    }
    fun dismissAnswer() = dispatch(GlassesEvent.DismissAnswer)

    fun submitAnswer(source: AnswerSource, text: String, context: String = "", allowTools: Boolean = true, retainHistory: Boolean = true, forceDsh: Boolean = false) {
        if (source == AnswerSource.MOBILE_AGENT) {
            check(agentServer.state.enabled) { "먼저 설정의 'DSH Go 연결'에서 휴대폰 어시스턴트를 켜고 DSH Go 플러그인을 시작하세요" }
        }
        when (source) {
            AnswerSource.MOBILE_AGENT -> check(!agent.running) { "답변을 기다리거나 먼저 중지하세요" }
            AnswerSource.COMPUTER_CODEX -> check(codex.connected && !codex.running) { "컴퓨터를 연결하고 현재 작업이 끝날 때까지 기다리세요" }
            AnswerSource.FLASHCARD -> error("Anki 카드는 질문으로 보낼 수 없습니다")
        }
        autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null
        val ref = AnswerRef(source, ++nextAnswer)
        dispatch(GlassesEvent.AnswerRequested(ref))
        pendingAnswers[source] = checkNotNull(state.answerPermit)
        if (source == AnswerSource.MOBILE_AGENT) voiceReply = null
        try {
            when (source) {
                AnswerSource.MOBILE_AGENT -> agent.submit(text, context, allowTools, retainHistory, forceDsh)
                AnswerSource.COMPUTER_CODEX -> codex.submit(text)
                AnswerSource.FLASHCARD -> Unit
            }
        } catch (error: Exception) {
            pendingAnswers.remove(source); dispatch(GlassesEvent.CancelAnswer(source)); throw error
        }
    }
    fun cancelAnswer(source: AnswerSource) {
        invalidateAnswer(source)
        when (source) {
            AnswerSource.MOBILE_AGENT -> agent.cancel()
            AnswerSource.COMPUTER_CODEX -> codex.cancel()
            AnswerSource.FLASHCARD -> Unit
        }
    }
    private fun invalidateAnswer(source: AnswerSource) {
        pendingAnswers.remove(source)
        dispatch(GlassesEvent.CancelAnswer(source))
    }
    private fun nativeIdentity(reset: Boolean = false): JSONObject {
        val config = settings.read()
        var changed = false
        if (config.optString("phoneInstanceId").isBlank()) { config.put("phoneInstanceId", java.util.UUID.randomUUID().toString()); changed = true }
        if (reset || config.optString("nativeConversationKey").isBlank()) { config.put("nativeConversationKey", java.util.UUID.randomUUID().toString()); changed = true }
        if (changed) settings.save(config)
        return JSONObject().put("phoneId", config.getString("phoneInstanceId")).put("conversationKey", config.getString("nativeConversationKey"))
    }
    fun newMobileConversation() {
        val previous = nativeIdentity().getString("conversationKey")
        nativeIdentity(reset = true)
        conversations.resetAgentConversation()
        agent.harness.cancelConversation(previous)
        clearAgent()
    }
    fun clearAgent() { invalidateAnswer(AnswerSource.MOBILE_AGENT); agent.clear() }
    fun connectCodex() { invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.connect() }
    fun closeCodex() { invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.close() }
    fun newCodexConversation() {
        check(!codex.running) { "먼저 현재 컴퓨터 작업을 종료하세요" }
        invalidateAnswer(AnswerSource.COMPUTER_CODEX); codex.newConversation()
    }
    private fun completed(source: AnswerSource, question: String, text: String) {
        val permit = pendingAnswers.remove(source) ?: return
        val delivery = voiceReply?.takeIf { it.ref == permit.ref }
        if (text.isBlank()) {
            if (delivery != null) voiceReply = null
            if (state.answerPermit == permit) dispatch(GlassesEvent.CancelAnswer(source))
            return
        }
        if (delivery != null) {
            voiceReply = null
            conversations.rememberReply(question, text)
            if (delivery.source == ConversationSource.NATIVE) {
                if (voiceTransport.nativeActive && delivery.wakeId == voiceTransport.wakeId && delivery.wakeId != null)
                    voiceTransport.reply(text, question, delivery.wakeId)
                else if (state.link.phase == LinkPhase.FULL) connection.sendNotification("어시스턴트", text)
                return
            }
            if (delivery.source == ConversationSource.ALWAYS_ON || state.answerPermit != permit) {
                if (state.link.phase == LinkPhase.FULL) connection.sendNotification("어시스턴트", text)
                return
            }
        }
        if (text.isNotBlank()) {
            autoClearFor = permit.ref; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null
            dispatch(GlassesEvent.AnswerCompleted(AnswerContent(permit.ref, question, text), permit))
        }
    }
    private var answerPageTask: Runnable? = null
    private var answerPageRef: AnswerRef? = null
    private fun updateAnswerClearTimer() {
        // Long answers turn their pages first; the clear timer starts on the last page. Pages only turn while visible.
        val shown = state.answer?.takeIf { (state.intent as? DisplayIntent.Answer)?.ref == it.ref }
        val visible = state.session != null && state.ownership == DisplayOwnership.APP && state.link.phase == LinkPhase.FULL
        if (shown == null || !visible || shown.page >= shown.pages.lastIndex || answerPageRef != shown.ref) {
            answerPageTask?.let(main::removeCallbacks); answerPageTask = null; answerPageRef = null
        }
        if (shown != null && visible && shown.page < shown.pages.lastIndex) {
            if (answerPageTask == null && state.session?.let { it.phase == PagePhase.READY && it.writtenContent == it.content } == true) {
                val ref = shown.ref; val next = shown.page + 1
                answerPageRef = ref
                answerPageTask = Runnable { answerPageTask = null; answerPageRef = null; dispatch(GlassesEvent.AnswerPage(ref, next)) }
                    .also { main.postDelayed(it, ANSWER_PAGE_MS) }
            }
            return
        }
        val ref = autoClearFor ?: return
        if ((state.intent as? DisplayIntent.Answer)?.ref != ref || state.ownership != DisplayOwnership.APP || state.link.phase != LinkPhase.FULL) {
            autoClearFor = null; clearAnswerTask?.let(main::removeCallbacks); clearAnswerTask = null; return
        }
        val session = state.session ?: return
        if (clearAnswerTask != null || session.phase != PagePhase.READY || session.writtenContent != session.content) return
        val seconds = settings.read().optInt("answerClearSeconds", 0).coerceIn(0,3600)
        if (seconds == 0) { autoClearFor = null; return }
        val revision = state.revision; val id = session.id
        clearAnswerTask = Runnable {
            if (state.session?.id == id && state.revision == revision && (state.intent as? DisplayIntent.Answer)?.ref == ref) clearDisplay()
        }.also { main.postDelayed(it, seconds * 1000L) }
    }
    fun revealAnswer(source: AnswerSource) {
        check(state.link.phase == LinkPhase.FULL && !state.disconnecting) { "먼저 안경을 완전히 연결하세요" }
        val (question, answer) = when (source) {
            AnswerSource.MOBILE_AGENT -> agent.question to agent.answer
            AnswerSource.COMPUTER_CODEX -> codex.question to codex.answer
            AnswerSource.FLASHCARD -> anki.state.card?.let { card -> if (anki.state.flipped) card.question to card.answer else "Anki" to card.question } ?: ("" to "")
        }
        require(answer.isNotBlank()) { "표시할 답변이 아직 없습니다" }
        dispatch(GlassesEvent.RevealAnswer(AnswerContent(AnswerRef(source, ++nextAnswer), question, answer)))
    }
    private fun navigationSnapshot() = NavigationState(navigation.enabled, navigation.selectedKey, navigation.frame)
    private fun recordAPI(method: String, rawPath: String, body: JSONObject): JSONObject? {
        val path = rawPath.substringBefore('?')
        val query = if (rawPath.contains('?')) rawPath.substringAfter('?').split('&').associate { row ->
            java.net.URLDecoder.decode(row.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(row.substringAfter('=', ""), "UTF-8")
        } else emptyMap()
        val args = JSONObject(body.toString()).apply { query.forEach { (key, value) -> put(key, value) } }
        val repository = conversations.repository
        return when {
            method == "GET" && path in setOf("/v1/records", "/v1/conversations") || method == "POST" && path == "/v1/records/query" ->
                JSONObject().put("items", repository.query(args.optLong("from", Long.MIN_VALUE), args.optLong("to", Long.MAX_VALUE), args.optString("recordId"), args.optString("text")))
            method == "GET" && path.startsWith("/v1/conversations/") -> JSONObject().put("items", repository.query(id = path.substringAfterLast('/')))
            method == "GET" && path == "/v1/records/people" -> JSONObject().put("items", org.json.JSONArray(repository.people.map { it.json().apply { remove("samples") } }))
            method == "POST" && path == "/v1/records/derived" -> {
                val item = RecordDerived.parse(body)
                check(repository.applyDerived(item)) { "기록이 없습니다" }; conversations.refreshRecords()
                JSONObject().put("item", item.json()).put("persisted", true)
            }
            method == "GET" && path == "/v1/records/sync" -> JSONObject().put("enabled", recordSync.state.enabled).put("port", recordSync.state.port)
                .put("notice", recordSync.state.notice).put("addresses", org.json.JSONArray(recordSync.addresses()))
            else -> null
        }
    }
    private fun executeTool(name: String, args: JSONObject, completion: (Result<JSONObject>) -> Unit) {
        when (name) {
            "navigation_status" -> completion(Result.success(JSONObject().put("following", state.navigation.enabled)
                .put("notificationPresent", navigation.selected != null).put("status", navigation.status)))
            "stop_navigation_mirroring" -> { stopNavigation(); completion(Result.success(JSONObject().put("following", false).put("gaodeNavigationChanged", false))) }
            "show_notification" -> {
                connection.sendNotification(args.getString("title"), args.getString("body"))
                completion(Result.success(JSONObject().put("queued", true).put("visible", "unverified")))
            }
            "record_query" -> completion(Result.success(JSONObject().put("items", conversations.repository.query(args.optLong("from", Long.MIN_VALUE),
                args.optLong("to", Long.MAX_VALUE), args.optString("recordId"), args.optString("text")))))
            "record_annotate" -> {
                val item = RecordDerived.parse(args)
                check(conversations.repository.applyDerived(item)) { "기록이 없습니다" }; conversations.refreshRecords()
                completion(Result.success(JSONObject().put("item", item.json()).put("persisted", true)))
            }
            "memory_search" -> {
                val query = args.getString("query")
                val rows = conversations.repository.records.mapNotNull { record ->
                    val segments = record.segments.filter { it.text.contains(query, true) }
                    val memories = record.memories.filter { it.text.contains(query, true) || it.subject.contains(query, true) }
                    if (segments.isEmpty() && memories.isEmpty()) null else JSONObject().put("record_id", record.id).put("mode", record.mode.name)
                        .put("segments", org.json.JSONArray(segments.map { it.json() })).put("memories", org.json.JSONArray(memories.map { it.json() }))
                }
                completion(Result.success(JSONObject().put("items", org.json.JSONArray(rows))))
            }
            "teleprompter_start" -> startTeleprompter(args.getString("title"), args.getString("text"), args.optInt("speed", 2), completion = completion)
            "teleprompter_control" -> teleprompter.control(args.getString("action"), completion)
            "display_clear" -> { check(state.session?.mode == PageMode.PROMPT && state.ownership == DisplayOwnership.APP) { "이 앱이 소유한 실시간 힌트 페이지가 없습니다" }; clearDisplay(); completion(Result.success(JSONObject().put("queued", true))) }
            "device_read" -> requests.perform({ deviceSettings.readForTool() }, completion)
            else -> if (name.startsWith("device_")) deviceSettings.tool(name, args, completion)
                else content.execute(name, args, completion)
        }
    }
    // Settings are an AES-GCM decrypt; the voice path asks on every partial transcript, so keep them for a moment.
    @Volatile private var settingsCache: Pair<Long, JSONObject>? = null
    private fun cachedSettings(): JSONObject {
        val now = android.os.SystemClock.elapsedRealtime()
        settingsCache?.takeIf { now - it.first < 2_000 }?.let { return it.second }
        return settings.read().also { settingsCache = now to it }
    }
    private fun onLog(text: String) {
        android.util.Log.i("OpenRayneo", text)
        logFile.append(text)
        logs.addLast("${LocalTime.now().withNano(0)} $text")
        while (logs.size > 300) logs.removeFirst()
        if (!dispatching) notifyUI()
    }
    private fun notifyUI() { if (initialized && !destroyed) observers.toList().forEach { it() } }

    // Recognition problems used to show only on the records/translation screens: the app log is where testers look.
    private var loggedConversation = ""
    private var loggedRunning = ""
    private var loggedTranslator = ""
    private fun conversationChanged() {
        if (initialized) {
            val state = conversations.state
            // Record start and end are logged too: a device log must show which record was running when.
            val running = if (state.running) "${state.source.title}${if (state.stopping) " 종료 중" else ""}" else ""
            if (running != loggedRunning) { onLog(if (running.isEmpty()) "기록 끝: ${loggedRunning.removeSuffix(" 종료 중")}${state.voiceGate.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}" else "기록 진행: $running"); loggedRunning = running }
            val line = state.error?.let { "기록 오류: $it" }
                ?: state.status.takeIf { it.isNotBlank() && PROBLEM.containsMatchIn(it) }?.let { "기록 상태: $it" } ?: ""
            if (line.isNotEmpty() && line != loggedConversation) onLog(line)
            loggedConversation = line
        }
        notifyUI()
    }
    private fun translatorChanged() {
        if (initialized) {
            val line = translator.status
            // A normal end-of-session reconnect is not a problem; reconnects after an error carry the error text.
            if (line != loggedTranslator && PROBLEM.containsMatchIn(line) && !line.startsWith("ASR 세션 종료")) onLog("자막/번역 상태: $line")
            loggedTranslator = line
        }
        notifyUI()
    }

    /** TEMPORARY test aid: one sentence straight into the configured ASR (and the translation variant), no glasses involved. */
    private var asrTesting = false
    fun testAsr() {
        check(!virtualVoice.active) { "가상 음성을 재생 중입니다" }
        check(!asrTesting) { "ASR 단독 시험이 이미 진행 중입니다" }
        val base = settings.read()
        require(SpeechRounds.ready(this, base)) { SpeechRounds.missing(base) }
        val engine = base.optString("asrEngine").ifBlank { "cloud" }
        virtualVoice.synthesize("안녕하세요. 음성 인식 시험입니다. What time is it now?") { result ->
            main.post {
                result.onFailure { onLog("ASR 단독 시험: 시험 음성 만들기 실패 ${it.message}") }.onSuccess { pcm ->
                    asrTesting = true
                    val translation = JSONObject(SpeechRounds.bilingual(this, base).toString()).put("asrLanguage", "ko,en")
                    runAsrTest("깨우기 설정", base, pcm, AsrUse.WAKE) { runAsrTest("번역·실시간 설정(ko,en)", translation, pcm, AsrUse.LIVE) {
                        runAsrTest("종일 기록 설정", base, pcm, AsrUse.SPAN) { asrTesting = false } } }
                }
            }
        }
        onLog("ASR 단독 시험 시작: 엔진 $engine, 언어 힌트 \"${base.optString("asrLanguage")}\"")
    }
    private fun runAsrTest(label: String, config: JSONObject, pcm: ShortArray, use: AsrUse, next: () -> Unit) {
        val texts = mutableListOf<String>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var round: SpeechRound? = null
        // Main thread only. Cancelling also frees a local model or closes the cloud socket on every ending.
        fun end(message: String) { if (!done.compareAndSet(false, true)) return; round?.cancel(); onLog("ASR 단독 시험 [$label]: $message"); next() }
        round = runCatching {
            SpeechRounds.create(this, config, { text, _, _, _, _ -> main.post { texts += text } },
                { message -> main.post { end("실패 - $message") } },
                { main.post { end(if (texts.isEmpty()) "끝났지만 인식 결과 없음" else "성공 - ${texts.joinToString(" / ")}") } }, use = use)
        }.getOrElse { end("만들기 실패 - ${it.message}"); return }
        val active = checkNotNull(round)
        Thread {
            try {
                active.start()
                val resampler = ConversationAudio()
                val audio = ShortArray(VirtualVoice.RATE / 2) + pcm + ShortArray(VirtualVoice.RATE * 3 / 2)
                var index = 0
                while (index < audio.size && !done.get()) {
                    val chunk = audio.copyOfRange(index, minOf(index + 960, audio.size))
                    while (!active.canAcceptAudio() && !done.get()) Thread.sleep(20)
                    active.append(resampler.convert(chunk)); index += chunk.size
                    Thread.sleep(20)
                }
                if (!done.get()) {
                    active.finish()
                    // The wait starts once all audio is in, so a slow model load is not reported as "no result".
                    main.postDelayed({ end(if (texts.isEmpty()) "음성을 다 보낸 뒤 20초 안에 결과 없음" else "부분 성공 - ${texts.joinToString(" / ")}") }, 20_000)
                }
            } catch (error: Exception) { main.post { end("오류 - ${error.message}") } }
        }.apply { name = "asr-test"; start() }
    }
    private fun summary() = state.displayDescription + when (state.audio.phase) {
        AudioPhase.RECEIVING -> " · 음성 수신 중"
        AudioPhase.WAITING_PACKET, AudioPhase.WAITING_PCM -> " · 오디오 대기 중"
        AudioPhase.STALLED -> " · 오디오 중단됨"
        AudioPhase.FAILED -> " · 오디오 오류"
        AudioPhase.STOPPED -> ""
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "bridge").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenRayneo").setContentText(summary()).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "서비스 중지", stop).build()).build()
    }
    private fun updateNotification() {
        if (!initialized || !foreground || stopAfterDisconnect) return
        val text = summary()
        if (text != notificationText) { notificationText = text; getSystemService(NotificationManager::class.java).notify(1, notification()) }
    }
    override fun onDestroy() {
        destroyed = true; observers.clear(); main.removeCallbacksAndMessages(null)
        navigation.observers.remove(navigationObserver)
        translator.close(); virtualVoice.close(); voiceMemo.close(); agent.close(); codex.close(); conversations.close(); agentServer.close(); recordSync.close(); requests.close(); content.close(); anki.close(); display.reset(); audio.close(); voiceAudio.close(); supervisor.close()
        logFile.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

internal const val DEFAULT_ANSWER_MAX_CHARS = 60
/** Seconds of silence before the wake assistant popup on the glasses is closed (setting nativeIdleCloseSeconds). */
internal const val DEFAULT_NATIVE_IDLE_CLOSE_SECONDS = 15
private const val ANSWER_PAGE_MS = 7_000L
private const val REALTIME_ANSWER_HOLD_SECONDS = 15
private const val STALE_PARTIAL_MS = 10_000L
private val PROBLEM = Regex("실패|오류|끊|보충|미룸|대기|절전|배터리|온도|내려받|API Key|멈췄|다시 연결")
private const val NO_VIRTUAL_TARGET = "가상 음성을 받을 기능이 없습니다. 먼저 안경에서 실시간 자막/답변/음성 메모를 열거나 깨우세요"
