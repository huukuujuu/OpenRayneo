// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject

internal data class ChatRecord(val question: String, val answer: String)
internal data class SelectedContact(val name: String, val uri: String)

internal data class CompanionState(
    val available: Boolean = false,
    val glasses: GlassesState = GlassesState(),
    val conversation: ConversationState = ConversationState(),
    val deviceSettings: DeviceSettingsState = DeviceSettingsState(),
    val content: CompanionContentState = CompanionContentState(),
    val anki: AnkiState = AnkiState(),
    val teleprompter: TeleprompterState = TeleprompterState(),
    val agentServer: AgentServerState = AgentServerState(),
    val recordSync: AgentServerState = AgentServerState(notice = "근거리 동기화가 꺼져 있음"),
    val phoneEphemeral: Boolean = false,
    val nativeWakeAvailable: Boolean = false,
    val devices: List<RayneoConnection.Device> = emptyList(),
    val navigationEntries: List<NavigationFeed.Entry> = emptyList(),
    val navigationStatus: String = "Amap에서 내비게이션을 시작한 뒤 여기로 돌아와 알림을 따라가세요",
    val notificationConnected: Boolean = false,
    val phoneQuestion: String = "",
    val phoneAnswer: String = "",
    val phoneRunning: Boolean = false,
    val phoneStatus: String = "모델을 설정하면 대화를 시작할 수 있습니다",
    val computerQuestion: String = "",
    val computerAnswer: String = "",
    val computerRunning: Boolean = false,
    val computerStatus: String = "컴퓨터가 아직 연결되지 않음",
    val computerConnected: Boolean = false,
    val approvals: List<CodexClient.Request> = emptyList(),
    val logs: List<String> = emptyList(),
    val audioLevels: PromptAudio.State = PromptAudio.State(),
    val translationActive: Boolean = false,
    val translationStatus: String = "실시간 번역 꺼짐",
    val translationCaptions: Boolean = false,
    val notificationReadStatus: String = "알림 리스너 서비스 대기 중",
    val notificationSnapshotAt: Long = 0,
    val notificationReadPhase: NotificationReadPhase = NotificationReadPhase.DISCONNECTED,
)

internal class AppModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val preferences = context.getSharedPreferences("companion_ui", Context.MODE_PRIVATE)
    private val settings = AppSettings(context)
    @SuppressLint(
        "StaticFieldLeak"
    ) // Application-bound service; the binding and observer are released in onCleared.
    private var bridge: BridgeService? = null
    private var bound = false
    var state by mutableStateOf(CompanionState())
        private set

    var config by mutableStateOf(JSONObject())
        private set

    val mobileModelConfigured: Boolean get() = MobileModelEndpoint.isConfigured(
        config.optString("modelUrl"), config.optString("modelName"), config.optString("modelKey"))

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var bluetoothAllowed by mutableStateOf(false)
        private set

    var notificationAllowed by mutableStateOf(false)
        private set

    var backgroundNotifications by mutableStateOf(false)
        private set

    var ankiAllowed by mutableStateOf(false)
        private set

    var onboarding by mutableStateOf(!preferences.getBoolean("onboardingComplete", false))
        private set

    var onboardingStep by mutableIntStateOf(preferences.getInt("onboardingStep", 0))
        private set

    var appearance by mutableStateOf(preferences.getString("appearance", "system") ?: "system")
        private set

    var deviceName by
        mutableStateOf(preferences.getString("deviceName", "RayNeo iO") ?: "RayNeo iO")
        private set

    var phoneDraft by mutableStateOf("")
    var selectedContact by mutableStateOf<SelectedContact?>(null)
    var computerDraft by mutableStateOf("")
    var phoneHistory by mutableStateOf<List<ChatRecord>>(emptyList())
        private set

    var computerHistory by mutableStateOf<List<ChatRecord>>(emptyList())
        private set

    // Form drafts survive activity recreation without writing secrets into saved instance state.
    val formDrafts = mutableStateMapOf<String, String>()
    var wantsScan = false
    private val files = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun exportRecording(id: String, uri: android.net.Uri) {
        val s = service(); val record = s.conversations.repository.records.first { it.id == id }
        files.execute {
            val result = runCatching { context.contentResolver.openOutputStream(uri)!!.use { s.conversations.recordings.exportWav(record.audioId ?: id, record.audioSamples, it) } }
            main.post { result.fold({ notice = "회의 오디오를 선택한 위치로 내보냈습니다" }, { error = it.message ?: "내보내기 실패" }) }
        }
    }
    var pendingTextExport: Pair<List<String>, String>? = null
    fun exportRecords(ids: List<String>, title: String, uri: android.net.Uri) {
        val conversations = service().conversations
        val records = conversations.repository.records.filter { it.id in ids }
        val people = conversations.repository.people
        files.execute {
            val result = runCatching {
                val text = RecordExport.markdown(records, people, title.takeIf { records.size > 1 })
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            main.post { result.fold({ notice = "기록 ${records.size}개를 내보냈습니다" }, { error = it.message ?: "내보내기 실패" }) }
        }
    }
    private val observer: () -> Unit = { refresh() }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bridge = (binder as BridgeService.LocalBinder).service
                bridge!!.observers.add(observer)
                refresh()
                if (bluetoothAllowed) runCatching { bridge!!.connection.showBonded() }
                if (wantsScan && bluetoothAllowed) {
                    wantsScan = false
                    scan()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bridge = null
                state = CompanionState()
            }
        }

    init {
        attempt {
            config = settings.read()
            resetFormDrafts()
        }
        refreshPermissions()
        bound =
            context.bindService(
                Intent(context, BridgeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        if (!bound) error = "연결 서비스를 시작할 수 없습니다. 앱을 다시 열어 주세요"
    }

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            error = failure.message ?: "작업이 완료되지 않았습니다. 다시 시도하세요"
        }
    }

    fun service(): BridgeService = checkNotNull(bridge) { "연결 서비스를 준비 중입니다. 잠시 후 다시 시도하세요" }

    private fun refresh() {
        val s = bridge ?: return
        state =
            CompanionState(
                available = true,
                glasses = s.state,
                conversation = s.conversations.state,
                deviceSettings = s.deviceSettings.state,
                content = s.content.state,
                anki = s.anki.state,
                teleprompter = s.teleprompter.state,
                agentServer = s.agentServer.state,
                recordSync = s.recordSync.state,
                phoneEphemeral = s.agent.ephemeral,
                nativeWakeAvailable = s.voiceTransport.nativeActive,
                devices = s.devices.values.toList(),
                navigationEntries = s.navigation.entries.values.toList(),
                navigationStatus = s.navigation.status,
                notificationConnected = s.navigation.connected,
                phoneQuestion = s.agent.question,
                phoneAnswer = s.agent.answer,
                phoneRunning = s.agent.running,
                phoneStatus = s.agent.status,
                computerQuestion = s.codex.question,
                computerAnswer = s.codex.answer,
                computerRunning = s.codex.running,
                computerStatus = s.codex.status,
                computerConnected = s.codex.connected,
                approvals = s.codex.requests.toList(),
                logs = s.logs.toList(),
                audioLevels = s.audioLevels,
                translationActive = s.translator.active,
                translationStatus = s.translator.status,
                translationCaptions = s.translator.captions,
                notificationReadStatus = s.navigation.listenerState.detail,
                notificationSnapshotAt = s.navigation.lastSnapshotAt,
                notificationReadPhase = s.navigation.listenerState.phase,
            )
    }

    fun refreshPermissions() {
        bluetoothAllowed =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        notificationAllowed =
            context
                .getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(
                    ComponentName(context, NavigationNotifications::class.java)
                )
        backgroundNotifications =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        NavigationNotifications.refresh(context, foreground = true)
        ankiAllowed = AnkiDroid.allowed(context)
        bridge?.content?.reloadCalendar()
    }

    fun scan() = attempt {
        if (bridge == null) {
            wantsScan = true
            return@attempt
        }
        service().scan()
        service().connection.showBonded()
    }

    fun connect(device: RayneoConnection.Device, diagnostic: Boolean) = attempt {
        service().connect(device, diagnostic)
        deviceName = device.name
        preferences.edit().putString("deviceName", deviceName).apply()
    }

    fun saveConfig(values: Map<String, String>) = attempt {
        val old = settings.read()
        val updated = JSONObject(old.toString())
        values.forEach { (key, value) -> updated.put(key, value.trim()) }
        // A cleared summary address/name means "use the default"; compare and keep the effective value.
        with(settings) { updated.withDefaults() }
        val mobileChanged =
            listOf("modelUrl", "modelName", "modelKey", "mobileAgentBackend").any {
                old.optString(it) != updated.optString(it)
            }
        val computerChanged =
            listOf("codexUrl", "codexToken", "codexCwd").any {
                old.optString(it) != updated.optString(it)
            }
        if (computerChanged) updated.remove("codexThread")
        updated.remove("amapKey")
        updated.remove("amapConsent")
        settings.save(updated)
        if (mobileChanged) {
            bridge?.clearAgent()
            phoneHistory = emptyList()
        }
        if (computerChanged) {
            bridge?.closeCodex()
            computerHistory = emptyList()
        }
        config = updated
        bridge?.conversations?.configurationChanged()
        bridge?.let { LocalModels.keepResident(it, updated) }
        resetFormDrafts()
        notice = "설정이 암호화되어 저장됨"
    }

    /**
     * Save a few voice settings (problem fixes, undo) without touching other unsaved edits on the screen:
     * only these keys' drafts follow the new values.
     */
    fun saveSettingsQuietly(values: Map<String, String>) = attempt {
        val updated = settings.read()
        values.forEach { (key, value) -> updated.put(key, value) }
        settings.save(updated)
        config = updated
        values.forEach { (key, value) -> formDrafts[key] = value }
        bridge?.conversations?.configurationChanged()
    }

    private fun resetFormDrafts() {
        configurationKeys.forEach {
            formDrafts[it] = config.optString(it)
        }
    }

    fun importConfig(data: JSONObject) {
        val keys = configurationKeys
        saveConfig(keys.filter { data.has(it) }.associateWith { data.getString(it) })
    }
    companion object {
        val configurationKeys = listOf("modelUrl", "modelName", "modelKey", "mobileAgentBackend", "localAgentPort", "codexUrl", "codexCwd", "codexToken",
            "asrEngine", "asrKey", "asrUrl", "asrModelWake", "asrModelLive", "asrLanguage", "decisionKey", "decisionUrl", "decisionModel", "nativeAutoAnswer", "audioChannel",
            "speakerThreshold", "voiceRoutingPrompt", "memoryPrompt", "memoryAutoSummary", "answerClearSeconds", "nativeIdleCloseSeconds", "answerMaxChars", "saveMeetingAudio", "glassesVoiceGate", "selfVoiceMarginDb", "selfVoiceFloorDb", "selfVoiceHoldMs", "bargeInLevelDb", "bargeInMinMs", "asrKeepLoaded")
    }

    fun send(source: AnswerSource) = attempt {
        val mobile = source == AnswerSource.MOBILE_AGENT
        val text = (if (mobile) phoneDraft else computerDraft).trim()
        require(text.isNotBlank()) { "먼저 질문을 입력하세요" }
        val old =
            if (mobile) ChatRecord(state.phoneQuestion, state.phoneAnswer)
            else ChatRecord(state.computerQuestion, state.computerAnswer)
        val oldWasEphemeral = state.phoneEphemeral
        service().submitAnswer(source, text)
        if (mobile) {
            if (old.question.isNotBlank() && !oldWasEphemeral) phoneHistory = (phoneHistory + old).takeLast(30)
            phoneDraft = ""
        } else {
            if (old.question.isNotBlank()) computerHistory = (computerHistory + old).takeLast(30)
            computerDraft = ""
        }
    }

    fun newConversation(source: AnswerSource) = attempt {
        if (source == AnswerSource.MOBILE_AGENT) {
            service().newMobileConversation()
            phoneHistory = emptyList()
        } else {
            service().newCodexConversation()
            computerHistory = emptyList()
        }
    }

    fun step(value: Int) {
        onboardingStep = value.coerceIn(0, 4)
        preferences.edit().putInt("onboardingStep", onboardingStep).apply()
    }

    fun finishSetup() {
        setupReplay = false
        onboarding = false
        preferences.edit().putBoolean("onboardingComplete", true).apply()
    }

    /** The setup guide was reopened from settings: back on its first page returns to the app instead of leaving it. */
    var setupReplay by mutableStateOf(false)
        private set

    fun replaySetup() {
        setupReplay = true
        step(0)
        onboarding = true
    }

    fun changeAppearance(value: String) {
        appearance = value
        preferences.edit().putString("appearance", value).apply()
    }

    override fun onCleared() {
        bridge?.observers?.remove(observer)
        if (bound) context.unbindService(connection)
        files.shutdown()
        bridge = null
        bound = false
    }
}
