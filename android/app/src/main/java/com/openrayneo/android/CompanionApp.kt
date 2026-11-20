// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionApp(model: AppModel, actions: UiActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Sub-screens opened on top of the tabs, newest last: back returns to where each one was opened from.
    var saved by rememberSaveable { mutableStateOf("") }   // routes joined by '/', saved as plain text
    val stack = saved.split('/').filter { it.isNotEmpty() }
    val route = stack.lastOrNull().orEmpty()
    val open: (String) -> Unit = { next -> saved = (stack.filter { it != next } + next).joinToString("/") }
    val back: () -> Unit = { saved = stack.dropLast(1).joinToString("/") }
    var devices by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val pages = rememberSaveableStateHolder()
    val s = model.state
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(
        enabled =
            !devices &&
                !sources &&
                (route.isNotEmpty() || model.onboarding && (model.onboardingStep > 0 || model.setupReplay) || tab != 0)
    ) {
        if (route.isNotEmpty()) back()
        else if (model.onboarding) { if (model.onboardingStep > 0) model.step(model.onboardingStep - 1) else model.finishSetup() }
        else tab = 0
    }
    LaunchedEffect(model.notice) {
        val message = model.notice ?: return@LaunchedEffect
        // Clear only after the snackbar ends; clearing first changed this effect's key and cancelled the snackbar.
        try { snackbars.showSnackbar(message) } finally { if (model.notice == message) model.notice = null }
    }
    Scaffold(
        topBar = {
            if (!model.onboarding || route.isNotEmpty())
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                "settings" -> "설정"
                                "model" -> "요약·대화 모델"
                                "computer" -> "컴퓨터 연결"
                                "diagnostics" -> "기기 및 진단"
                                "voice-settings" -> "음성 설정"
                                "voice-profiles" -> "목소리 및 인물"
                                "device-settings" -> "안경 설정"
                                "content" -> "홈 화면 및 콘텐츠"
                                "teleprompter" -> "프롬프터 자료"
                                "agent-api" -> "DSH Go 연결"
                                else -> listOf("OpenRayneo", "내비게이션", "어시스턴트", "기록")[tab]
                            }
                        )
                    },
                    navigationIcon = {
                        if (route.isNotEmpty())
                            IconButton(
                                onClick = back
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로")
                            }
                    },
                    actions = {
                        if (route.isEmpty()) {
                            if (s.glasses.link.phase == LinkPhase.FULL) StatusPill("안경 연결됨")
                            IconButton(onClick = { open("settings") }) {
                                Icon(Icons.Outlined.Settings, "설정")
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
        },
        bottomBar = {
            if (route.isEmpty() && !model.onboarding && !keyboardVisible)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    val icons =
                        listOf(
                            Icons.Outlined.Visibility,
                            Icons.Outlined.NearMe,
                            Icons.AutoMirrored.Outlined.Chat,
                            Icons.Outlined.FolderOpen,
                        )
                    listOf("안경", "내비게이션", "어시스턴트", "기록").forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent
                                ),
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (index == 2 && s.approvals.isNotEmpty())
                                            Badge { Text(s.approvals.size.toString()) }
                                    }
                                ) {
                                    Icon(icons[index], null)
                                }
                            },
                            label = { Text(title) },
                        )
                    }
                }
        },
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Edge-to-edge ignores adjustResize: shrink every screen above the keyboard so the focused field scrolls into view.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (route) {
                "settings" ->
                    SettingsScreen(
                        model,
                        actions,
                        open,
                        {
                            model.replaySetup()
                            saved = ""
                        },
                    )
                "model",
                "computer" -> ConfigurationScreen(model, route == "model", actions.importConfig)
                "diagnostics" -> DiagnosticsScreen(model, actions)
                "voice-settings" -> VoiceSettingsScreen(model, actions)
                "voice-profiles" -> VoiceProfilesScreen(model)
                "device-settings" -> GlassesSettingsScreen(model)
                "content" -> CompanionContentScreen(model)
                "teleprompter" -> pages.SaveableStateProvider("teleprompter") { TeleprompterScreen(model) }
                "agent-api" -> AgentServerScreen(model, actions.bluetoothPermission)
                else ->
                    if (model.onboarding)
                        OnboardingScreen(model, actions, { devices = true }, open)
                    else
                        pages.SaveableStateProvider(tab) {
                            when (tab) {
                                0 ->
                                    GlassesScreen(
                                        model,
                                        { devices = true },
                                        { tab = it },
                                        { open("device-settings") },
                                        open,
                                    )
                                1 ->
                                    NavigationScreen(
                                        model,
                                        actions,
                                        { sources = true },
                                        { devices = true },
                                    )
                                2 -> AssistantScreen(model, actions, open)
                                else -> ConversationScreen(model, actions, open)
                            }
                        }
            }
        }
    }
    if (devices)
        ModalBottomSheet(onDismissRequest = { devices = false }) { DevicePicker(model, actions) }
    if (sources)
        ModalBottomSheet(onDismissRequest = { sources = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("내비게이션 알림 소스", style = MaterialTheme.typography.headlineSmall)
                Note("회전 방향과 거리가 포함된 알림을 선택하세요. '내비게이션 중' 메시지만으로는 방향을 알 수 없습니다.", Modifier.padding(vertical = 12.dp))
                if (s.navigationEntries.isEmpty()) Note("아직 Amap 내비게이션 알림을 받지 못했습니다")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(s.navigationEntries, key = { it.key }) { entry ->
                        OutlinedCard(
                            onClick = {
                                model.attempt {
                                    model.service().followNavigation(entry.key)
                                    sources = false
                                }
                            }
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    entry.title.ifBlank { "Amap 내비게이션" },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Note(entry.texts.filter { it != entry.title }.joinToString("\n"))
                                Text(
                                    if (
                                        entry.key == s.glasses.navigation.key &&
                                            s.glasses.navigation.enabled
                                    )
                                        "따라가는 중"
                                    else "이 알림 따라가기",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    model.error?.let { message ->
        AlertDialog(
            onDismissRequest = { model.error = null },
            title = { Text("안내") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("확인") } },
        )
    }
}

@Composable
private fun GlassesScreen(
    model: AppModel,
    connect: () -> Unit,
    navigate: (Int) -> Unit,
    diagnostics: () -> Unit,
    configure: (String) -> Unit,
) {
    val state = model.state.glasses
    val linked = state.link.phase == LinkPhase.FULL
    val busy =
        state.link.phase in
            setOf(LinkPhase.CONNECTING, LinkPhase.SCANNING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ContentCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.deviceName, style = MaterialTheme.typography.titleMedium)
                        Note(if (linked) "연결됨 · 휴대폰 직접 연결" else state.link.detail)
                    }
                    ToolOutlinedButton(
                        onClick =
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) diagnostics
                            else connect
                    ) {
                        Text(
                            if (linked || state.link.phase == LinkPhase.BLE_ONLY) "관리"
                            else if (busy) "연결 진행 상황" else "연결"
                        )
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (linked || state.link.phase == LinkPhase.BLE_ONLY || busy) {
                    ToolOutlinedButton(
                        onClick = { model.attempt { model.service().disconnect() } },
                        enabled = !state.disconnecting && state.link.phase != LinkPhase.CLOSING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.disconnecting) "연결 해제 중…" else "연결 해제")
                    }
                                    }
            }
        }
        item {
            ContentCard {
                Text("독립 컴패니언", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolOutlinedButton(onClick = { configure("content") }) { Text("홈 화면 및 콘텐츠") }
                    ToolOutlinedButton(onClick = { navigate(3) }) { Text("음성 기록") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { configure("teleprompter") }) { Text("프롬프터 원고함") }
                    TextButton(onClick = { configure("agent-api") }) { Text("DSH Go 연결") }
                }
            }
        }
        item { SectionLabel("안경 화면") }
        item { GlassesOutputPreview(state) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (linked) {
                    Note(state.displayDescription)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolOutlinedButton(onClick = { navigate(1) }) { Text("내비게이션") }
                        ToolOutlinedButton(
                            onClick = { model.attempt { model.service().startRealtimeHint() } }
                        ) {
                            Text("실시간 힌트")
                        }
                    }
                    if (!model.state.translationActive)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolOutlinedButton(
                                onClick = { model.attempt { model.service().startTranslation() } }
                            ) {
                                Text("실시간 번역 (한↔영)")
                            }
                            ToolOutlinedButton(
                                onClick = { model.attempt { model.service().startCaptions() } }
                            ) {
                                Text("실시간 자막")
                            }
                        }
                    if (model.state.translationActive) {
                        val captions = model.state.translationCaptions
                        Note("${if (captions) "실시간 자막" else "실시간 번역 (한국어 ↔ 영어)"}: ${model.state.translationStatus}")
                        TextButton(onClick = { model.attempt { model.service().stopTranslation() } }) {
                            Text(if (captions) "자막 종료" else "번역 종료")
                        }
                    } else if (model.state.translationStatus != "실시간 번역 꺼짐") Note(model.state.translationStatus)
                    if (state.session?.mode == PageMode.PROMPT && state.session.phase == PagePhase.READY)
                        TextButton(onClick = { model.attempt { model.service().clearDisplay() } }) {
                            Text("화면 지우기 (음성 수신 유지)")
                        }
                    if (state.intent is DisplayIntent.Answer)
                        TextButton(
                            onClick = { model.attempt { model.service().dismissAnswer() } }
                        ) {
                            Text("안경 답변 종료")
                        }
                    if (state.session != null)
                        TextButton(onClick = { model.attempt { model.service().pausePrompt() } }) {
                            Text("표시 및 음성 수신 일시정지")
                        }
                }
            }
        }
        item {
            ContentCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("안경 오디오", style = MaterialTheme.typography.titleSmall)
                        Note(state.audio.detail)
                    }
                }
                if (state.audio.phase == AudioPhase.RECEIVING) {
                    val levels = model.state.audioLevels
                    val channels =
                        if (levels.channels == 1) listOf("모노" to levels.left)
                        else listOf("채널 1" to levels.left, "채널 2" to levels.right)
                    channels.forEach { (label, rms) ->
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = {
                                ((20 * kotlin.math.log10(rms.coerceAtLeast(.00001)) + 60) / 60)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Note("음량은 휴대폰에서만 계산하며 오디오를 저장하거나 업로드하지 않습니다.")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureTile("내비게이션", "Amap 알림", Icons.Outlined.NearMe, Modifier.weight(1f)) {
                    navigate(1)
                }
                FeatureTile(
                    "어시스턴트",
                    "휴대폰 · 컴퓨터",
                    Icons.AutoMirrored.Outlined.Chat,
                    Modifier.weight(1f),
                ) {
                    navigate(2)
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    click: () -> Unit,
) {
    Card(
        onClick = click,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
        }
    }
}

@Composable
private fun DevicePicker(model: AppModel, actions: UiActions) {
    val s = model.state
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    val connecting =
        s.glasses.link.phase in setOf(LinkPhase.CONNECTING, LinkPhase.RETRY_WAIT, LinkPhase.CLOSING)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("안경 연결하기", style = MaterialTheme.typography.headlineSmall)
        Note("안경을 깨어 있는 상태로 두고, 먼저 공식 앱이나 컴퓨터와의 연결을 끊으세요. 시스템 페어링 안내가 나타나면 휴대폰에서 확인하세요.")
        Text(s.glasses.link.detail, color = MaterialTheme.colorScheme.primary)
        if (!model.bluetoothAllowed) {
            ToolButton(onClick = actions.scan, modifier = Modifier.fillMaxWidth()) {
                Text("근처 기기 허용 후 스캔")
            }
            TextButton(onClick = actions.appSettings) { Text("앱 권한 설정으로 이동") }
        } else {
            ToolButton(
                onClick = actions.scan,
                enabled = !ready && !connecting && s.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null)
                Spacer(Modifier.width(8.dp))
                Text("안경 스캔")
            }
        }
        if (connecting || s.glasses.link.phase == LinkPhase.SCANNING)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.devices, key = { it.bluetooth.address }) { device ->
                OutlinedCard(
                    onClick = { model.connect(device, false) },
                    enabled = !ready && !connecting,
                ) {
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(if (ready) "연결됨" else "탭하여 연결") },
                        leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        if (s.devices.isEmpty() && model.bluetoothAllowed && !connecting) Note("안경이 보이지 않으면 안경을 깨운 뒤 다시 스캔하세요.")
        if (ready || connecting)
            ToolOutlinedButton(onClick = { model.attempt { model.service().disconnect() } }) {
                Text(if (ready) "안경 연결 해제" else "연결 취소")
            }
    }
}

@Composable
private fun NavigationScreen(
    model: AppModel,
    actions: UiActions,
    sources: () -> Unit,
    connect: () -> Unit,
) {
    val s = model.state
    val nav = s.glasses.navigation
    val entry = s.navigationEntries.firstOrNull { it.key == nav.key }
    val instruction = entry?.instruction?.takeIf { nav.enabled && s.notificationConnected }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry?.updatedAt) {
        while (true) {
            time = System.currentTimeMillis()
            delay(10_000)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionLabel("안경 화면") }
        item { GlassesOutputPreview(s.glasses) }
        item {
            ContentCard {
                Text("Amap 알림", style = MaterialTheme.typography.titleMedium)
                if (instruction != null) {
                    Text(
                        "${instruction.direction} · ${instruction.meters} m",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Note(entry!!.title)
                } else Note(s.navigationStatus)
                val frame = nav.frame
                if (
                    frame != null &&
                        (s.glasses.session?.mode != PageMode.NAVIGATION ||
                            s.glasses.session?.writtenContent?.text != frame)
                ) {
                    GridPreview(frame, "내비게이션 레이아웃 전송 대기")
                }
                Note("전체 도로명은 여기에 표시됩니다. 안경 레이아웃에는 오른쪽 아래 8칸만 도로명으로 표시됩니다.")
            }
        }
        item {
            ContentCard {
                Text(
                    if (nav.enabled) "Amap 알림을 따라가는 중" else "시작 준비",
                    style = MaterialTheme.typography.titleMedium,
                )
                Note(s.notificationReadStatus)
                if (
                    s.notificationReadPhase == NotificationReadPhase.FAILED &&
                        android.os.Build.MANUFACTURER.equals("xiaomi", true)
                ) {
                    Note("Xiaomi / HyperOS의 백그라운드 자동 시작 제한이 리스너 연결을 막을 수 있습니다. OpenRayneo의 '백그라운드 자동 시작'을 허용한 뒤 알림 읽기를 복구하세요.")
                    TextButton(onClick = actions.appSettings) { Text("앱 설정 확인") }
                }
                Note(s.navigationStatus)
                if (entry != null && nav.enabled) {
                    val age = ((time - entry.updatedAt).coerceAtLeast(0) / 1000)
                    Note("최근 알림 수신: ${if (age < 60) "${age}초 전" else "${age / 60}분 전"} · Amap의 실시간 안내를 기준으로 하세요")
                }
                if (!model.notificationAllowed)
                    ToolButton(
                        onClick = actions.notificationAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("알림 접근 허용")
                    }
                else if (!nav.enabled)
                    ToolButton(onClick = actions.openGaode, modifier = Modifier.fillMaxWidth()) {
                        Text("Amap 내비게이션 열기")
                    }
                if (nav.enabled) {
                    if (s.glasses.link.phase != LinkPhase.FULL)
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text("안경 연결")
                        }
                    else {
                        Note(s.glasses.displayDescription)
                        if (
                            s.glasses.intent != DisplayIntent.Navigation ||
                                s.glasses.ownership != DisplayOwnership.APP ||
                                s.glasses.error != null
                        )
                            ToolButton(
                                onClick = { model.attempt { model.service().reopenNavigation() } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("안경 내비게이션 재개")
                            }
                    }
                    ToolOutlinedButton(
                        onClick = { model.attempt { model.service().stopNavigation() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("안경에 띄우기 중지")
                    }
                    Note("안경 표시만 멈추고 Amap 내비게이션은 계속됩니다.")
                }
            }
        }
        item {
            OutlinedCard(onClick = sources) {
                ListItem(
                    headlineContent = { Text("알림 소스") },
                    supportingContent = {
                        Text(
                            if (s.navigationEntries.isEmpty()) "Amap 내비게이션 알림 대기 중"
                            else "${s.navigationEntries.size}개 후보 알림 · 탭하여 선택"
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.NotificationsNone, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            TextButton(onClick = actions.openGaode) { Text("Amap에서 경로 보기") }
            ToolOutlinedButton(
                onClick = {
                    model.attempt {
                        NavigationNotifications.requestConnection(model.getApplication())
                    }
                },
                enabled = model.notificationAllowed,
            ) {
                Text("알림 읽기 복구")
            }
            Note("알림을 다시 읽어 옵니다. 고른 알림과 권한은 그대로입니다.")
            Note("위의 안경 화면은 전송 결과에 따라서만 갱신되며 Amap 알림은 따로 표시됩니다. 종료되거나 연결이 끊기면 이전 화면을 현재 화면으로 간주하지 않습니다.")
        }
    }
}
