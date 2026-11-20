// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassesSettingsScreen(model: AppModel) {
    val snapshot = model.state.deviceSettings
    val connected = model.state.glasses.link.phase == LinkPhase.FULL
    val editable = connected && snapshot.loaded && !snapshot.busy
    val status = snapshot.status
    val settings = snapshot.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("안경 설정", style = MaterialTheme.typography.headlineMedium)
        Note("블루투스로 기기 설정을 직접 읽고 수정합니다. 사용 중에는 OpenRayneo가 안경 연결을 유지합니다.")
        ToolButton(onClick = { model.attempt { model.service().deviceSettings.refresh() } },
            enabled = connected && !snapshot.busy, modifier = Modifier.fillMaxWidth()) { Text("안경 설정 읽기") }
        if (snapshot.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Note(if (connected) snapshot.notice else "먼저 안경 탭에서 연결을 완료하세요")
        val savedName = model.state.widgets.userName
        var name by remember(savedName) { mutableStateOf(savedName) }
        SettingsSection("내 이름") {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("안경에 보일 이름") }, singleLine = true)
            Note("공식 앱처럼 연결할 때마다 안경에 보냅니다.")
            ToolButton(onClick = { model.attempt { model.service().widgets.setUserName(name) } }, enabled = name.trim() != savedName) { Text("이름 저장") }
        }
        if (snapshot.loaded) {
            SettingsSection("기기 정보") {
                Text(status.optString("deviceName").ifBlank { model.deviceName }, style = MaterialTheme.typography.titleMedium)
                settings.optString("firmwareVersion").takeIf { it.isNotBlank() }?.let { Note("펌웨어 $it") }
                status.deviceInt("battery")?.let { Note("배터리 $it%") }
                status.deviceFlag("isCharging")?.let { Note(if (it) "충전 중" else "충전 안 함") }
            }
            status.deviceInt("brightness")?.let { current ->
                var value by remember(current) { mutableIntStateOf(current) }
                SettingsSection("표시 밝기") {
                    Text("밝기 $value · 현재 기기 값 $current")
                    Slider(value.toFloat(), { value = it.toInt() }, valueRange = 1f..17f, steps = 15, enabled = editable)
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.brightness(value) } }, enabled = editable) { Text("밝기 적용") }
                    status.deviceFlag("automaticBrightness")?.let { Note("자동 밝기: ${if (it) "켜짐" else "꺼짐"}") }
                }
            }
            val display = settings.optJSONObject("displayConfig")
            val currentHeight = display?.deviceInt("height")
            val currentDistance = display?.deviceInt("distance")
            if (currentHeight != null && currentDistance != null) {
                var height by remember(currentHeight) { mutableIntStateOf(currentHeight) }
                var distance by remember(currentDistance) { mutableIntStateOf(currentDistance) }
                SettingsSection("화면 위치") {
                    Text("높이 $height")
                    Slider(height.toFloat(), { height = it.toInt() }, valueRange = 1f..6f, steps = 4, enabled = editable)
                    Text("거리 $distance")
                    Slider(distance.toFloat(), { distance = it.toInt() }, valueRange = 1f..3f, steps = 1, enabled = editable)
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.display(height, distance) } }, enabled = editable) { Text("화면 위치 적용") }
                }
            }
            settings.deviceInt("autoLockTime")?.let { current ->
                var value by remember(current) { mutableIntStateOf(current) }
                SettingsSection("자동 절전") {
                    SettingsChoice("절전 시간", value, listOf(5, 10, 15, 25, 40, 60, 120).map { it to "${it}초" }, editable) { value = it }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.autoLock(value) } },
                        enabled = editable && value in listOf(5, 10, 15, 25, 40, 60, 120)) { Text("절전 시간 적용") }
                }
            }
            settings.optJSONObject("wakeupConfig")?.let { wake ->
                val current = wake.deviceFlag("headupSwitch"); val currentAngle = wake.deviceInt("headupDegree"); val currentCrown = wake.deviceInt("crownSwitch")
                if (current != null && currentAngle != null && currentCrown != null) {
                    var enabled by remember(current) { mutableStateOf(current) }; var angle by remember(currentAngle) { mutableIntStateOf(currentAngle) }
                    var crown by remember(currentCrown) { mutableIntStateOf(currentCrown) }
                    SettingsSection("절전 설정") {
                        SettingsToggle("Head-Up 디스플레이 (고개를 들면 화면 켜기)", enabled, editable) { enabled = it }
                        Text("Head-Up 각도 $angle°"); Slider(angle.coerceIn(0, 70).toFloat(), { angle = it.toInt() }, valueRange = 0f..70f, enabled = editable && enabled)
                        TextButton(onClick = { angle = 20 }, enabled = editable && angle != 20) { Text("기본 각도(20°) 복원") }
                        SettingsChoice("화면 꺼짐 상호 작용", crown, listOf(0 to "꺼짐", 1 to "자이로스코프", 2 to "콕 찌르기", 3 to "구슬"), editable) { crown = it }
                        Note("화면이 꺼진 상태에서 크라운을 돌리면 고른 픽셀 애니메이션이 나옵니다.")
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.wakeup(enabled, angle, crown) } }, enabled = editable) { Text("깨우기 설정 적용") }
                    }
                }
            }
            settings.optJSONObject("headGestures")?.let { head ->
                val enabledNow = head.deviceFlag("enabled"); val modeNow = head.deviceInt("mode")
                if (enabledNow != null && modeNow != null) {
                    var enabled by remember(enabledNow) { mutableStateOf(enabledNow) }; var mode by remember(modeNow) { mutableIntStateOf(modeNow) }
                    SettingsSection("헤드 컨트롤") {
                        SettingsToggle("머리 제스처", enabled, editable) { enabled = it }
                        Note("고개를 끄덕이거나 저어 안경의 제안을 받거나 닫습니다.")
                        SettingsChoice("방향", mode, listOf(0 to "자연 (끄덕임 확인, 젓기 취소)", 1 to "사용자 지정 (끄덕임 취소, 젓기 확인)"), editable && enabled) { mode = it }
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.headGestures(enabled, mode) } }, enabled = editable) { Text("머리 제스처 적용") }
                    }
                }
            }
            settings.optJSONObject("crownConfig")?.let { crown ->
                val d = crown.deviceInt("direction"); val c = crown.deviceInt("double"); val l = crown.deviceInt("longPress")
                if (d != null && c != null && l != null) {
                    var direction by remember(d) { mutableIntStateOf(d) }; var double by remember(c) { mutableIntStateOf(c) }; var long by remember(l) { mutableIntStateOf(l) }
                    val choices = listOf(0 to "RayNeo AI", 1 to "대시보드로 돌아가기", 2 to "집중 모드", 3 to "음성 메모", 4 to "실시간 자막",
                        5 to "텔레프롬프터", 6 to "Life Log 켜기/끄기", 9 to "Live Answers", 8 to "할 일", 7 to "없음")
                    SettingsSection("크라운 컨트롤") {
                        SettingsChoice("회전 방향", direction, listOf(1 to "자연 (시계 방향으로 오른쪽·아래)", 0 to "기본 (반시계 방향으로 오른쪽·아래)"), editable) { direction = it }
                        SettingsChoice("두 번 누르기", double, choices, editable) { double = it }
                        SettingsChoice("화면이 꺼진 상태에서 길게 누르기", long, choices.filter { it.first != 1 && it.first != 6 }, editable) { long = it }
                        Note("길게 누르기 단축키는 화면이 꺼져 있을 때만 동작합니다. 화면이 켜져 있으면 대시보드로 돌아갑니다.")
                        ToolButton(onClick = { model.attempt { model.service().deviceSettings.crown(direction, double, long) } }, enabled = editable) { Text("다이얼 조작 적용") }
                    }
                }
            }
            settings.optJSONArray("supportedLanguages")?.let { languages ->
                val choices = (0 until languages.length()).map { languages.getString(it) }
                var language by remember(settings.optString("currentLang")) { mutableStateOf(settings.optString("currentLang")) }
                if (choices.isNotEmpty() && language.isNotBlank()) SettingsSection("시스템 언어") {
                    choices.forEach { option -> FilterChip(language == option, { language = option }, label = { Text(option) }, enabled = editable) }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.language(language) } }, enabled = editable) { Text("언어 적용") }
                }
            }
            settings.optJSONObject("privacyConfig")?.let { privacy ->
                val labels = linkedMapOf("mic_switch" to "안경 마이크", "led_light" to "표시등", "led_auto_light" to "표시등 자동 밝기", "log_switch" to "기기 로그")
                val values = remember(privacy.toString()) { mutableStateMapOf<String, Boolean>().apply { labels.keys.forEach { key -> privacy.deviceFlag(key)?.let { put(key, it) } } } }
                if (values.isNotEmpty()) SettingsSection("개인정보 및 하드웨어") {
                    labels.forEach { (key, label) -> values[key]?.let { SettingsToggle(label, it, editable) { values[key] = it } } }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.privacy(values.toMap()) } }, enabled = editable) { Text("개인정보 설정 적용") }
                }
            }
            settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("voiceWakeup")?.let { current ->
                var enabled by remember(current) { mutableStateOf(current) }
                SettingsSection("음성 깨우기") {
                    SettingsToggle("안경 어시스턴트 깨우기 허용", enabled, editable) { enabled = it }
                    Note("이 항목은 안경의 깨우기 스위치를 변경합니다. 응답 방식은 '음성 및 프롬프트'에서 설정합니다.")
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.voiceWake(enabled) } }, enabled = editable) { Text("음성 깨우기 적용") }
                }
            }
            settings.optJSONObject("voiceAssistantConfig")?.deviceFlag("workMode")?.let { current ->
                var enabled by remember(current) { mutableStateOf(current) }
                SettingsSection("대화 끊기") {
                    SettingsToggle("AI가 말하는 중에 말로 끊기", enabled, editable) { enabled = it }
                    Note("켜면 Hey RayNeo로 깨운 대화에서 AI가 답하는 도중 말을 걸어 답변을 끊고 바로 다음 질문을 할 수 있습니다. 안경 펌웨어 설정입니다.")
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.interrupt(enabled) } }, enabled = editable) { Text("대화 끊기 적용") }
                }
            }
            settings.optJSONObject("voiceAssistantConfig")?.deviceInt("wakeupWord")?.let { current ->
                var word by remember(current) { mutableStateOf(current) }
                SettingsSection("깨우기 명령어") {
                    SettingsChoice("명령어", word, listOf(2 to "Hey RayNeo (영어)", 1 to "샤오레이 샤오레이 (중국어)"), editable) { word = it }
                    Note("안경 펌웨어의 깨우기 명령어를 변경합니다. 기기가 현재 값을 보고한 뒤에 표시됩니다.")
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.wakeWord(word) } }, enabled = editable) { Text("깨우기 명령어 적용") }
                }
            }
            val units = settings.optJSONObject("systemUnitsConfig")
            val currentTime = units?.deviceInt("time_format")
            val currentTemp = units?.deviceInt("temp_unit")
            val currentMeasure = units?.deviceInt("measurement")
            if (currentTime != null && currentTemp != null && currentMeasure != null) {
                var time by remember(currentTime) { mutableIntStateOf(currentTime) }
                var temperature by remember(currentTemp) { mutableIntStateOf(currentTemp) }
                var measurement by remember(currentMeasure) { mutableIntStateOf(currentMeasure) }
                SettingsSection("시간 및 단위") {
                    SettingsChoice("시간", time, listOf(1 to "24시간제", 2 to "12시간제"), editable) { time = it }
                    SettingsChoice("온도", temperature, listOf(1 to "섭씨 °C", 2 to "화씨 °F"), editable) { temperature = it }
                    SettingsChoice("측정 단위", measurement, listOf(1 to "미터법", 2 to "야드파운드법"), editable) { measurement = it }
                    ToolButton(onClick = { model.attempt { model.service().deviceSettings.units(time, temperature, measurement) } },
                        enabled = editable && listOf(time, temperature, measurement).all { it in 1..2 }) { Text("시간 및 단위 적용") }
                    Note("날짜 형식은 안경의 현재 설정을 따릅니다.")
                }
            }
            if (settings.optJSONObject("voiceAssistantConfig") == null || units == null) {
                Note("음성 깨우기와 단위 옵션은 기기가 해당 설정을 보고한 뒤에 나타납니다. 보고되지 않은 설정은 기본값으로 덮어쓰지 않습니다.")
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingsToggle(title: String, value: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(value, update, enabled = enabled)
    }
}

@Composable
private fun SettingsChoice(title: String, value: Int, choices: List<Pair<Int, String>>, enabled: Boolean, update: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolOutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$title: ${choices.firstOrNull { it.first == value }?.second ?: "기기 값 $value"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { update(id); expanded = false }) }
        }
    }
}
