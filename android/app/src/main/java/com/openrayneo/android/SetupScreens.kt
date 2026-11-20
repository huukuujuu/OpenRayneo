// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun OnboardingScreen(
    model: AppModel,
    actions: UiActions,
    connect: () -> Unit,
    configure: (String) -> Unit,
) {
    val step = model.onboardingStep
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val titles = listOf("OpenRayneo 시작하기", "안경 연결", "내비게이션 안내 켜기", "어시스턴트 설정", "설정 확인")
    val subtitles =
        listOf(
            "OpenRayneo에 오신 것을 환영합니다. 몇 단계로 설정을 마치거나 나중에 계속할 수 있습니다.",
            "휴대폰으로 RayNeo iO에 직접 연결하면 내비게이션과 답변이 눈앞에 표시됩니다.",
            "경로는 Amap이 계획하고, OpenRayneo가 알림을 읽어 방향, 거리, 도로명을 안경에 보여 줍니다.",
            "질문은 휴대폰에서 바로 처리하고, 기록과 요약도 휴대폰이 혼자 합니다. 컴퓨터의 Codex를 연결하면 긴 작업을 컴퓨터에 맡길 수도 있습니다.",
            "아래는 현재 준비 상태입니다. 완료하지 않은 항목은 언제든 설정에서 계속할 수 있습니다.",
        )
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("OpenRayneo", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = model::finishSetup) { Text("나중에 설정") }
        }
        LinearProgressIndicator(
            progress = { (step + 1) / 5f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "${step + 1} / 5",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(titles[step], style = MaterialTheme.typography.headlineLarge)
            Note(subtitles[step])
            when (step) {
                0 -> {
                    GlassesArtwork(
                        Modifier.fillMaxWidth().height(90.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    SetupRow(Icons.Outlined.NearMe, "눈을 들어 내비게이션 보기", "휴대폰 Amap의 실제 내비게이션 알림을 따라감")
                    SetupRow(Icons.Outlined.AutoAwesome, "언제든 어시스턴트에게 질문", "휴대폰에서 바로 답하고, 필요하면 컴퓨터의 Codex에 맡김")
                    SetupRow(Icons.Outlined.Tune, "직접 제어", "표시와 음성 수신은 언제든 일시정지 가능")
                }
                1 -> {
                    ContentCard {
                        GlassesArtwork(
                            Modifier.fillMaxWidth().height(110.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (model.state.glasses.link.phase == LinkPhase.FULL) "안경 연결됨"
                            else "연결 전 준비",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Note("1. 안경을 깨워 휴대폰 가까이 두기\n2. 공식 앱이나 컴퓨터와의 안경 연결 끊기\n3. 휴대폰에서 시스템 페어링 안내 확인")
                        ToolButton(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (model.state.glasses.link.phase == LinkPhase.FULL) "연결 보기"
                                else "안경 연결"
                            )
                        }
                    }
                    Note("'근처 기기' 권한은 연결할 때만 요청합니다. 연결하지 않아도 어시스턴트를 먼저 설정할 수 있습니다.")
                }
                2 -> {
                    ContentCard {
                        Text(
                            if (model.notificationAllowed) "알림 접근 허용됨" else "Amap 내비게이션 알림 읽기 허용",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (model.notificationAllowed) Note(model.state.notificationReadStatus)
                        Note("시스템이 알림 접근 권한을 부여하며, 앱은 Amap 내비게이션 알림만 처리합니다. 알림 본문은 모델로 전송되지 않습니다.")
                        ToolOutlinedButton(
                            onClick = actions.notificationAccess,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (model.notificationAllowed) "알림 권한 보기" else "알림 접근 허용하러 가기")
                        }
                    }
                    var example by rememberSaveable { mutableIntStateOf(0) }
                    val samples =
                        listOf(
                            Triple("직진", 300, "세종대로 따라"),
                            Triple("우회전", 80, "을지로 진입"),
                            Triple("좌회전", 20, "강변북로 따라"),
                        )
                    val sample = samples[example]
                    GridPreview(
                        NavigationFrame.render(sample.first, sample.second, sample.third),
                        "데모 · 안경과 같은 내비게이션 그리드",
                    )
                    TextButton(onClick = { example = (example + 1) % samples.size }) {
                        Text("데모 안내 전환")
                    }
                    Note("위 화면은 데모이며 안경으로 전송되거나 내비게이션을 시작하지 않습니다. 실제 내비게이션은 Amap 앱에서 시작하세요.")
                }
                3 -> {
                    SetupOption(
                        "휴대폰 어시스턴트",
                        "휴대폰에서 어시스턴트를 켜고, DSH Go 플러그인에서 안경과 연결하세요.",
                        model.state.agentServer.enabled,
                        Icons.Outlined.Smartphone,
                    ) {
                        configure("agent-api")
                    }
                    SetupOption(
                        "컴퓨터 Codex",
                        "컴퓨터에서 만든 설정 파일을 가져오거나 연결 정보를 직접 입력하세요. USB로 연결할 때는 컴퓨터에서 포워딩을 켜 두어야 합니다.",
                        model.config.optString("codexToken").isNotBlank(),
                        Icons.Outlined.Computer,
                    ) {
                        configure("computer")
                    }
                    Note("휴대폰 어시스턴트의 답변 방식과 모델은 DSH Go에서 정합니다. 기록 요약에 쓰는 모델은 '요약·대화 모델' 설정에서 따로 고를 수 있습니다.")
                }
                4 -> {
                    ContentCard {
                        CompletionRow(
                            "안경 연결",
                            model.state.glasses.link.phase == LinkPhase.FULL,
                            "연결됨",
                            "나중에 연결",
                        )
                        CompletionRow("내비게이션 알림 권한", model.notificationAllowed, "허용됨", "나중에 허용")
                        CompletionRow(
                            "휴대폰 어시스턴트",
                            model.state.agentServer.enabled,
                            "켜짐",
                            "선택 사항, 나중에 설정",
                        )
                        CompletionRow(
                            "컴퓨터 Codex",
                            model.config.optString("codexToken").isNotBlank(),
                            "설정 저장됨",
                            "선택 사항, 나중에 설정",
                        )
                    }
                    if (!model.backgroundNotifications)
                        ContentCard {
                            Text("백그라운드에서도 연결 상태 확인", style = MaterialTheme.typography.titleMedium)
                            Note("상태 알림을 허용하면 시스템 알림 표시줄에서 안경 연결을 확인하고 서비스를 중지할 수 있습니다.")
                            ToolOutlinedButton(onClick = actions.notificationPermission) {
                                Text("상태 알림 허용")
                            }
                        }
                    Note("'설정 → 사용 안내 다시 보기'에서 언제든 여기로 돌아올 수 있습니다.")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) ToolOutlinedButton(onClick = { model.step(step - 1) }) { Text("이전") }
            ToolButton(
                onClick = { if (step == 4) model.finishSetup() else model.step(step + 1) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) {
                Text(if (step == 0) "설정 시작" else if (step == 4) "OpenRayneo 시작" else "계속")
            }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(description)
        }
    }
}

@Composable
private fun SetupOption(
    title: String,
    description: String,
    saved: Boolean,
    icon: ImageVector,
    click: () -> Unit,
) {
    ContentCard {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Note(description)
        if (saved) StatusPill("설정 저장됨")
        ToolOutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "설정 보기" else "설정: $title")
        }
    }
}

@Composable
private fun CompletionRow(title: String, done: Boolean, complete: String, incomplete: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint =
                if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Note(if (done) complete else incomplete)
        }
    }
}

@Composable
internal fun SettingsScreen(
    model: AppModel,
    actions: UiActions,
    open: (String) -> Unit,
    replay: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("서비스 연결")
        SettingsRow(
            "요약·대화 모델",
            if (!model.mobileModelConfigured) "아직 설정되지 않음"
            else model.config.optString("modelName").ifBlank { "설정 저장됨" },
            Icons.Outlined.Smartphone,
        ) {
            open("model")
        }
        SettingsRow(
            "컴퓨터 Codex",
            if (model.state.computerConnected) "연결됨" else "연결 매개변수 및 설정 가져오기",
            Icons.Outlined.Computer,
        ) {
            open("computer")
        }
        SettingsRow("DSH Go 연결", "깨우기 답변을 DSH Go로 보내기", Icons.Outlined.Hub) { open("agent-api") }
        SettingsRow("음성 설정", "음성 인식, 불편한 점 고치기", Icons.Outlined.Mic) { open("voice-settings") }
        SettingsRow("목소리 및 인물", "내 목소리 등록, 이름 붙이기", Icons.Outlined.People) { open("voice-profiles") }
        SectionLabel("안경과 휴대폰")
        SettingsRow("안경 설정", "밝기, 화면 위치 및 깨우기", Icons.Outlined.Settings) { open("device-settings") }
        SettingsRow("리모컨 입력", "Anki·프롬프터 키 변경 및 입력 확인", Icons.Outlined.Bluetooth) { open("remote-input") }
        SettingsRow("홈 화면 및 콘텐츠", "위젯, 할 일, 일정 및 날씨", Icons.Outlined.Dashboard) { open("content") }
        SettingsRow("프롬프터 원고함", "로컬 원고, 안경 재생 및 제어", Icons.Outlined.Description) { open("teleprompter") }
        SettingsRow(
            "근처 기기",
            if (model.bluetoothAllowed) "허용됨" else "안경 연결 시 필요",
            Icons.Outlined.Bluetooth,
        ) {
            actions.appSettings()
        }
        SettingsRow(
            "Amap 알림 접근 권한",
            if (model.notificationAllowed) "허용됨 · ${model.state.notificationReadStatus}"
            else "내비게이션 안내를 안경에 띄울 때 필요",
            Icons.Outlined.NotificationsNone,
            actions.notificationAccess,
        )
        SettingsRow(
            "백그라운드 상태 알림",
            if (model.backgroundNotifications) "허용됨" else "선택 사항, 백그라운드 연결 상태 확인",
            Icons.Outlined.NotificationsActive,
        ) {
            if (model.backgroundNotifications) actions.appSettings()
            else actions.notificationPermission()
        }
        SettingsRow("기기 및 진단", "연결 로그, 테스트 알림 및 오디오 복구", Icons.Outlined.Tune) { open("diagnostics") }
        SectionLabel("화면 모드")
        ContentCard {
            listOf("system" to "시스템 설정 따르기", "light" to "라이트", "dark" to "다크").forEach { (key, title) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = model.appearance == key,
                        onClick = { model.changeAppearance(key) },
                    )
                    TextButton(onClick = { model.changeAppearance(key) }) { Text(title) }
                }
            }
        }
        SectionLabel("정보: OpenRayneo")
        SettingsRow("사용 안내 다시 보기", "안경 연결, 내비게이션 데모 및 어시스턴트 설정", Icons.Outlined.Explore, replay)
        Note(
            "OpenRayneo ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.GIT_COMMIT} (${BuildConfig.VERSION_CODE})\n기록 오디오는 휴대폰에 암호화 저장되며 전사와 화자 분석이 끝난 뒤 전달됩니다. 시스템 음성 입력은 휴대폰 인식 서비스를 사용합니다.",
            Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, icon: ImageVector, click: () -> Unit) {
    Card(
        onClick = click,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
        )
    }
}

@Composable
internal fun ConfigurationScreen(model: AppModel, mobile: Boolean, importConfig: () -> Unit) {
    val urlKey = if (mobile) "modelUrl" else "codexUrl"
    val nameKey = if (mobile) "modelName" else "codexCwd"
    val secretKey = if (mobile) "modelKey" else "codexToken"
    val url = model.formDrafts[urlKey].orEmpty()
    val name = model.formDrafts[nameKey].orEmpty()
    val secret = model.formDrafts[secretKey].orEmpty()
    var showSecret by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val localModel = mobile && MobileModelEndpoint.isLocalAddress(url)
    val ready = url.isNotBlank() && (secret.isNotBlank() || localModel) && (!mobile || name.isNotBlank())
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            if (mobile) Icons.Outlined.Smartphone else Icons.Outlined.Computer,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(if (mobile) "기록 요약 모델 설정" else "컴퓨터 연결 설정", style = MaterialTheme.typography.headlineMedium)
        Note(
            if (mobile) "앱 내 기록 요약에 사용되며 Chat Completions 호환 클라우드 또는 휴대폰 로컬 서비스를 지원합니다. 로컬 서비스에 인증이 없으면 API Key를 비워 둘 수 있습니다."
            else "먼저 컴퓨터에서 OpenRayneo의 Codex 연결 스크립트를 실행한 뒤 생성된 설정을 가져오세요. USB 모드는 포트 포워딩도 켜야 합니다."
        )
        if (!mobile) {
            ToolButton(onClick = importConfig, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("컴퓨터 설정 파일 가져오기")
            }
            Note("OpenRayneo-setup.json을 선택하세요. 액세스 자격 증명이 들어 있으므로 가져온 뒤 다운로드 폴더의 사본을 삭제하세요.")
            HorizontalDivider()
            Text("또는 직접 입력", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            url,
            { model.formDrafts[urlKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "서비스 주소" else "컴퓨터 서비스 주소") },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            placeholder = { Text(if (mobile) "https://서비스 제공자/v1" else "ws://127.0.0.1:4500") },
            singleLine = true,
            supportingText = {
                Text(if (mobile) "원격은 HTTPS 사용, 휴대폰 로컬은 HTTP 가능. 키를 주소에 넣지 마세요" else "USB: 로컬 주소, 무선 연결: 다음 사용 WSS")
            },
        )
        OutlinedTextField(
            name,
            { model.formDrafts[nameKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile) "모델 이름" else "컴퓨터 작업 디렉터리 (선택)") },
            singleLine = true,
        )
        OutlinedTextField(
            secret,
            { model.formDrafts[secretKey] = it },
            Modifier.fillMaxWidth(),
            label = { Text(if (mobile && localModel) "API Key (로컬 서비스는 비워 둘 수 있음)" else if (mobile) "API Key" else "연결 토큰") },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            visualTransformation =
                if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(
                        if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (showSecret) "키 숨기기" else "키 보기",
                    )
                }
            },
        )
        ToolButton(
            onClick = {
                model.saveConfig(
                    if (mobile) mapOf("modelUrl" to url, "modelName" to name, "modelKey" to secret)
                    else mapOf("codexUrl" to url, "codexCwd" to name, "codexToken" to secret)
                )
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("설정 저장")
        }
        Note("자격 증명은 Android Keystore로 암호화 저장됩니다. 저장해도 모델 요청이나 컴퓨터 작업은 실행되지 않으며 연결 결과는 어시스턴트 탭에서 확인하세요.")
        if (!mobile)
            ContentCard {
                Text("USB 연결 단계", style = MaterialTheme.typography.titleMedium)
                Note(
                    "1. 컴퓨터에 Codex CLI 설치 및 로그인\n2. 저장소의 scripts/phone-codex.py 실행\n3. 컴퓨터에서 adb reverse tcp:4500 tcp:4500 실행\n4. 어시스턴트 탭에서 컴퓨터 Codex를 선택하고 연결 누르기"
                )
            }
    }
}

@Composable
internal fun DiagnosticsScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current
    val s = model.state
    var bleOnly by rememberSaveable { mutableStateOf(false) }
    val ready = s.glasses.link.phase in setOf(LinkPhase.FULL, LinkPhase.BLE_ONLY)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContentCard {
            Text("알림 읽기", style = MaterialTheme.typography.titleMedium)
            Note(if (model.notificationAllowed) "시스템 권한 허용됨" else "시스템 권한 미허용")
            Note(s.notificationReadStatus)
            Note("현재 Amap 후보: ${s.navigationEntries.size}개")
            if (s.notificationSnapshotAt > 0)
                Note(
                    "최근 스냅샷 동기화: " +
                        java.text
                            .SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(s.notificationSnapshotAt))
                )
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
        }
        ContentCard {
            Text(model.deviceName, style = MaterialTheme.typography.titleLarge)
            Note(s.glasses.link.detail)
            Note(s.glasses.displayDescription)
            Note(s.glasses.audio.detail)
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().connection.sendTestNotification() } },
                enabled = ready,
            ) {
                Text("테스트 알림 보내기")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().retryAudio() } },
                enabled =
                    s.glasses.session?.phase in
                        setOf(PagePhase.LAYOUT, PagePhase.SETTLING, PagePhase.READY),
            ) {
                Text("오디오 디코딩 재시작 (페이지 유지)")
            }
            ToolOutlinedButton(
                onClick = { model.attempt { model.service().disconnect() } },
                enabled = ready || s.glasses.link.phase == LinkPhase.CONNECTING,
            ) {
                Text("안경 연결 해제")
            }
        }
        if (s.glasses.link.phase == LinkPhase.FULL) VirtualVoicePanel(model)
        ContentCard {
            Text("고급 연결 진단", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(bleOnly, { bleOnly = it }, enabled = !ready)
                Text("BLE 진단 전용", Modifier.padding(start = 12.dp))
            }
            Note("연결 문제 해결 전용입니다. 내비게이션과 어시스턴트 표시는 완전한 연결이 필요합니다.")
            if (bleOnly) {
                ToolOutlinedButton(onClick = actions.scan, enabled = !ready) { Text("기기 스캔") }
                s.devices.forEach { device ->
                    TextButton(onClick = { model.connect(device, true) }, enabled = !ready) {
                        Text("진단 연결 ${device.name}")
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("연결 로그")
            TextButton(
                onClick = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(
                            ClipData.newPlainText("OpenRayneo", s.logs.joinToString("\n"))
                        )
                    model.notice = "로그가 복사됨"
                }
            ) {
                Text("로그 복사")
            }
            TextButton(
                onClick = {
                    // Reading the log file waits for pending writes: off the main thread, then the share sheet on main.
                    val app = context.applicationContext
                    kotlin.concurrent.thread(name = "log-share") {
                        val file = runCatching {
                            val text = model.service().logText()
                            java.io.File(app.cacheDir, "shared-logs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                                .resolve("openrayneo-log-${java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-')}.txt")
                                .apply { writeText(text) }
                        }
                        androidx.core.content.ContextCompat.getMainExecutor(context).execute {
                            model.attempt {
                                val uri = FileProvider.getUriForFile(app, "${app.packageName}.logs", file.getOrThrow())
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "OpenRayneo 로그")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "로그 보내기"))
                            }
                        }
                    }
                }
            ) {
                Text("로그 공유")
            }
        }
        ContentCard { Note(s.logs.joinToString("\n").ifBlank { "연결 로그 없음" }) }
    }
}
