// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun VoiceSettingsScreen(model: AppModel, actions: UiActions) {
    val drafts = model.formDrafts
    val voiceKeys = listOf("asrEngine", "asrKey", "asrUrl", "asrModelWake", "asrModelLive", "asrLanguage", "nativeAutoAnswer", "audioChannel", "speakerThreshold", "memoryPrompt", "memoryAutoSummary", "answerClearSeconds", "nativeIdleCloseSeconds", "answerMaxChars", "glassesVoiceGate", "selfVoiceMarginDb", "selfVoiceFloorDb", "selfVoiceHoldMs", "bargeInLevelDb", "bargeInMinMs", "asrKeepLoaded")
    var error by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun save() {
        val invalid = listOf("speakerThreshold")
            .firstOrNull { key -> val value = drafts[key].orEmpty(); value.isNotBlank() && value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } != true }
        if (invalid != null) error = "내 목소리 인정 기준은 0~1 사이여야 합니다"
        else if (drafts["audioChannel"].orEmpty().isNotBlank() && drafts["audioChannel"]?.toIntOrNull() !in 0..2) error = "마이크는 1 또는 2여야 합니다"
        else if (drafts["answerMaxChars"].orEmpty().isNotBlank() && drafts["answerMaxChars"]?.toIntOrNull() !in 0..1000) error = "답변 최대 글자 수는 0~1000이어야 합니다"
        else if (drafts["selfVoiceMarginDb"].orEmpty().isNotBlank() && drafts["selfVoiceMarginDb"]?.toDoubleOrNull()?.let { it in 0.0..30.0 } != true) error = "본인 판단 기준은 0~30 dB여야 합니다"
        else if (drafts["nativeIdleCloseSeconds"].orEmpty().isNotBlank() && drafts["nativeIdleCloseSeconds"]?.toIntOrNull() !in 0..3600) error = "깨우기 창 자동 닫기는 0~3600초여야 합니다"
        else if (drafts["answerClearSeconds"].orEmpty().isNotBlank() && drafts["answerClearSeconds"]?.toIntOrNull() !in 0..3600) error = "답변 표시 시간은 0~3600초여야 합니다"
        else { error = null; model.saveConfig((voiceKeys + listOf("modelUrl", "modelName", "modelKey")).associateWith { drafts[it].orEmpty() }) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("음성 설정", style = MaterialTheme.typography.headlineSmall)
        SectionLabel("불편한 점 고치기")
        SettingFixesSection(model)

        SectionLabel("음성 인식")
        // The draft is the single source: what is shown is exactly what "save" writes, also after leaving unsaved.
        val engine = drafts["asrEngine"].orEmpty().ifBlank { "cloud" }
        Column {
            listOf("cloud" to "클라우드 (가장 정확, 인터넷 필요)", "nemotron" to "휴대폰에서 · 한국어/영어 (무거움)", "stream" to "휴대폰에서 · 한국어 전용 (가벼움)",
                "local" to "휴대폰에서 · 문장이 끝난 뒤 인식").forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(engine == key, { drafts["asrEngine"] = key }); Text(label) }
            }
        }
        if (engine == "cloud") VoiceField(model, "asrKey", "음성 인식 API 키 (Bailian)", secret = true)
        val context = LocalContext.current
        SpeechRounds.model(JSONObject().put("asrEngine", engine))?.let { local ->
            var access by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
            var recheck by remember { mutableIntStateOf(0) }
            val installed = remember(local, local.downloading, access, recheck) { local.installed(context) }
            if (installed) Note("${local.title} 모델 준비됨" + if (local.shared(context)) " (공용 폴더)" else " (앱 안, 앱을 지우면 함께 삭제)")
            else ToolButton(onClick = { local.download(context.applicationContext) }, enabled = !local.downloading, modifier = Modifier.fillMaxWidth()) {
                Text(if (local.downloading) "${(local.progress * 100).toInt()}% 받는 중…"
                    else "${local.title} 모델 받기 (${local.size}, Wi-Fi 권장)")
            }
            val movable = remember(local, local.downloading, access, recheck) { local.movable(context) }
            if (movable) ToolOutlinedButton(onClick = { local.moveToShared(context.applicationContext) }, enabled = !local.downloading, modifier = Modifier.fillMaxWidth()) {
                Text("모델을 공용 폴더로 옮기기 (재설치해도 유지)")
            }
            if (!access) ToolOutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + context.packageName)))
            }, modifier = Modifier.fillMaxWidth()) { Text("공용 폴더 사용 허용") }
            else if (!installed) ToolOutlinedButton(onClick = { recheck++ }, modifier = Modifier.fillMaxWidth()) { Text("복사한 모델 다시 찾기") }
            if (!access) LaunchedEffect(Unit) {
                // Pick up the permission when the user returns from system settings.
                while (true) { kotlinx.coroutines.delay(1000); if (android.os.Environment.isExternalStorageManager()) { access = true; break } }
            }
            if (local.downloading) LinearProgressIndicator(progress = { local.progress }, modifier = Modifier.fillMaxWidth())
            local.message?.takeIf { !local.downloading }?.let { Note(it) }
        }
        val asrLanguages = drafts["asrLanguage"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        fun toggleLanguage(code: String, on: Boolean) {
            val chosen = if (on) asrLanguages + code else asrLanguages - code
            drafts["asrLanguage"] = listOf("ko", "en").filter { it in chosen }.joinToString(",")
        }
        Text("말하는 언어 (고르지 않으면 자동)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox("ko" in asrLanguages, { toggleLanguage("ko", it) }); Text("한국어")
            Checkbox("en" in asrLanguages, { toggleLanguage("en", it) }); Text("영어")
        }
        SectionLabel("깨우기와 기록")
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(drafts["nativeAutoAnswer"] == "true", { drafts["nativeAutoAnswer"] = it.toString() }); Text("Hey RayNeo로 깨우면 이 앱이 대답") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(drafts["memoryAutoSummary"] != "false", { drafts["memoryAutoSummary"] = it.toString() }); Text("기록을 자동으로 요약") }
        VoiceField(model, "modelKey", "요약·번역 API 키", secret = true)
        TextButton(onClick = actions.locationPermission) { Text("위치 접근 허용 (날씨·위치 질문에 사용)") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ToolButton(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("저장") }

        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) { Text(if (advanced) "고급 설정 접기" else "고급 설정 (숫자로 직접 조정)") }
        if (advanced) {
            Text("앱 빌드 ${BuildConfig.GIT_COMMIT} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
            SectionLabel("음성 인식 서버")
            VoiceField(model, "asrUrl", "WSS 주소 (비우면 Bailian 국제)", AppSettings.DEFAULT_ASR_URL)
            VoiceField(model, "asrModelWake", "깨우기용 모델", AsrModels.SHORT)
            VoiceField(model, "asrModelLive", "계속 듣기용 모델 (자막·번역·기록)", AsrModels.STREAMING)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(drafts["asrKeepLoaded"] != "false", { drafts["asrKeepLoaded"] = it.toString() }); Text("휴대폰 인식 모델을 메모리에 계속 올려 두기")
            }
            Note("켜면 깨울 때 기다림이 없지만 메모리를 계속 씁니다(최대 약 700MB).")
            SectionLabel("누가 말했는지")
            VoiceField(model, "speakerThreshold", "내 목소리 인정 기준 (0~1, 기본 0.7, 낮출수록 '나'로 잘 잡음)", "0.7")
            VoiceField(model, "audioChannel", "목소리 등록에 쓸 마이크 (1 입가 · 2 바깥)", "1")
            SectionLabel("실시간 듀얼 마이크")
            GateSlider(drafts, "selfVoiceMarginDb", "본인 판단 기준 (입가 마이크가 더 커야 하는 정도)", 6f, 0f..20f, 19, { if (it < 0.5f) "끔" else "${it.toInt()} dB" },
                "낮추면 바깥 마이크에서 내 말을 더 많이 지웁니다. 상대 말이 잘리면 올리세요.")
            GateSlider(drafts, "selfVoiceFloorDb", "판단 하한", -50f, -70f..-20f, 9, { "${it.toInt()} dBFS" },
                "시끄러운 곳에서 상대 말이 지워지면 올리세요.")
            GateSlider(drafts, "selfVoiceHoldMs", "말 끝 유지 시간", 300f, 0f..1000f, 9, { "${it.toInt()} ms" },
                "내 말 끝이 상대 말로 남으면 늘리세요.")
            SectionLabel("종일 기록")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(drafts["glassesVoiceGate"] != "false", { drafts["glassesVoiceGate"] = it.toString() }); Text("종일 기록: 말소리가 들린 구간만 받아 적기 (조용할 때는 인식 비용 없음)") }
            SectionLabel("Hey RayNeo 깨우기")
            VoiceField(model, "nativeIdleCloseSeconds", "말이 없으면 창 닫기까지 초 (0이면 닫지 않음)", DEFAULT_NATIVE_IDLE_CLOSE_SECONDS.toString())
            GateSlider(drafts, "bargeInLevelDb", "답변 끊기 소리 크기", -30f, -60f..-10f, 49, { "${it.toInt()} dBFS" },
                "안경 설정의 \"대화 끊기\"가 켜져 있을 때만 씁니다. 낮출수록 작은 목소리에도 끊습니다.")
            GateSlider(drafts, "bargeInMinMs", "답변 끊기 지속 시간", 250f, 50f..1000f, 18, { "${it.toInt()} ms" },
                "짧을수록 빨리 끊고, 길수록 잡음에 덜 끊깁니다.")
            SectionLabel("안경 답변 화면")
            VoiceField(model, "answerMaxChars", "답변 최대 글자 수 (0이면 제한 없음)", DEFAULT_ANSWER_MAX_CHARS.toString())
            VoiceField(model, "answerClearSeconds", "답변을 지우기까지 초 (0: 실시간 답변은 15초, 나머지는 계속 표시)", "0")
            SectionLabel("요약 모델")
            VoiceField(model, "modelUrl", "서비스 주소 (비우면 Bailian 국제)", AppSettings.DEFAULT_MODEL_URL)
            VoiceField(model, "modelName", "모델 이름", AppSettings.DEFAULT_MODEL_NAME)
            StructuredVoicePrompt(model, "memoryPrompt", "요약 지침", ConversationPrompts.memory)
            ToolButton(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("저장") }
        }
    }
}

/** Slider bound to a numeric config draft; [steps] intermediate stops as in Material Slider. */
@Composable
private fun GateSlider(drafts: MutableMap<String, String>, key: String, label: String, default: Float,
                       range: ClosedFloatingPointRange<Float>, steps: Int, format: (Float) -> String, hint: String) {
    val value = drafts[key]?.toFloatOrNull()?.coerceIn(range) ?: default
    Text("$label: ${format(value)}")
    Slider(value, { drafts[key] = Math.round(it).toString() }, valueRange = range, steps = steps, modifier = Modifier.fillMaxWidth())
    Note(hint)
}

@Composable
private fun VoiceField(model: AppModel, key: String, label: String, fallback: String = "", secret: Boolean = false) {
    val value = model.formDrafts[key].orEmpty()
    if (secret) OutlinedTextField(value, { model.formDrafts[key] = it }, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true)
    else OutlinedTextField(value, { model.formDrafts[key] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(fallback) }, singleLine = true)
}

@Composable
private fun StructuredVoicePrompt(model: AppModel, key: String, title: String, fallback: String) {
    val raw = model.formDrafts[key].orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val initial = remember(key, model.config) {
        if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject().put("objective", raw.ifBlank { fallback }).put("rules", JSONArray())
    }
    var role by remember(initial) { mutableStateOf(initial.optString("role")) }
    var objective by remember(initial) { mutableStateOf(initial.optString("objective")) }
    var rules by remember(initial) { mutableStateOf(initial.optJSONArray("rules")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }.orEmpty()) }
    fun update() {
        model.formDrafts[key] = JSONObject().put("role", role).put("objective", objective)
            .put("rules", JSONArray(rules.lines().map { it.trim() }.filter { it.isNotEmpty() })).toString()
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(title + if (expanded) " · 접기" else " · 편집") }
            if (expanded) {
                TextButton(onClick = { role = ""; objective = fallback; rules = ""; update() }) { Text("이 버전의 기본 프롬프트로 복원") }
                OutlinedTextField(role, { role = it; update() }, Modifier.fillMaxWidth(), label = { Text("역할") })
                OutlinedTextField(objective, { objective = it; update() }, Modifier.fillMaxWidth(), label = { Text("목표") }, minLines = 3)
                OutlinedTextField(rules, { rules = it; update() }, Modifier.fillMaxWidth(), label = { Text("규칙 (한 줄에 하나)") }, minLines = 3)
            }
        }
    }
}

@Composable
internal fun VoiceProfilesScreen(model: AppModel) {
    val state = model.state.conversation
    val self = state.people.firstOrNull { it.self }
    var rename by remember { mutableStateOf<VoicePerson?>(null) }
    var merge by remember { mutableStateOf<VoicePerson?>(null) }
    var history by remember { mutableStateOf<VoicePerson?>(null) }
    var split by remember { mutableStateOf<VoicePerson?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("목소리 및 인물", style = MaterialTheme.typography.headlineSmall)
        Note("내 목소리를 등록하면 기록에서 내 말이 '나'로 표시됩니다. 조용한 곳에서 혼자 서너 문장을 말하고, 문장마다 잠깐 쉬어 주세요. 목소리 정보는 이 휴대폰에만 저장됩니다.")
        if (state.running && state.mode == ConversationMode.ENROLL) {
            var confirmed by remember { mutableStateOf(false) }
            Text("말한 시간 ${state.enrollmentTotalSeconds.toInt()}초 · 쓸 수 있는 목소리 ${state.enrollmentSeconds.toInt()}초")
            if (state.enrollmentCanConfirm && !state.enrollmentReady) {
                Note("목소리가 고르지 않게 들렸습니다. 처음부터 끝까지 혼자 말했다면 아래를 체크하고 저장하세요. 아니면 다시 녹음하세요.")
                Row { Checkbox(confirmed, { confirmed = it }); Text("모두 내가 말했습니다") }
            }
            ToolButton(onClick = { model.service().conversations.saveEnrollment(self?.name ?: "나", true, self?.contactUri, confirmAllSamples = confirmed) }, enabled = !state.stopping && (state.enrollmentReady || state.enrollmentCanConfirm && confirmed)) { Text("내 목소리 저장") }
            TextButton(onClick = { model.service().conversations.stop() }) { Text("등록 중지") }
        } else ToolButton(onClick = { model.attempt { model.service().startConversation(ConversationMode.ENROLL, ConversationSource.REALTIME, self) } },
            enabled = !state.running && state.modelReady && model.state.glasses.link.phase == LinkPhase.FULL) { Text(if (self?.samples?.isNotEmpty() == true) "내 목소리 다시 등록" else "내 목소리 등록") }
        Note(state.status)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.people.filter { it.mergedInto == null }.forEach { person ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(person.name + if (person.self) " · 나" else "", style = MaterialTheme.typography.titleMedium)
                    if (person.unnamed) Note("이름 없음")
                    Note(if (person.samples.isEmpty()) "목소리 없음" else "목소리 등록됨")
                    if (person.contactUri != null) Note("선택한 연락처와 연결됨")
                    Row {
                        TextButton(onClick = { rename = person }) { Text("이름 지정") }
                        TextButton(onClick = { merge = person }, enabled = state.people.count { it.mergedInto == null } > 1) { Text("병합") }
                        TextButton(onClick = { split = person }) { Text("분리") }
                    }
                    TextButton(onClick = { history = person }) { Text("지난 기록에서 이 사람 말 고르기") }
                    if (person.samples.isNotEmpty()) TextButton(onClick = { model.service().conversations.removeVoice(person.id) }) { Text("목소리만 지우기") }
                }
            }
        }
    }
    rename?.let { person ->
        var name by remember(person.id) { mutableStateOf(person.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("이름 바꾸기") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("인물 이름") }, singleLine = true)
                Note("지난 기록에도 새 이름으로 표시됩니다.")
            }
        }, confirmButton = { TextButton(onClick = { model.attempt { model.service().conversations.renamePerson(person.id, name.trim()) }; rename = null }, enabled = name.isNotBlank()) { Text("이름 저장") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("취소") } })
    }
    merge?.let { PersonMergeDialog(model, it) { merge = null } }
    history?.let { HistoricalSpeakerDialog(model, it.id, dismiss = { history = null }) }
    split?.let { HistoricalSpeakerDialog(model, it.id, split = true, dismiss = { split = null }) }
}

@Composable
private fun PersonMergeDialog(model: AppModel, initial: VoicePerson, dismiss: () -> Unit) {
    val state = model.state.conversation
    val people = state.people.filter { it.mergedInto == null }
    var sourceIds by remember { mutableStateOf(setOf(initial.id)) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var recordIds by remember { mutableStateOf(emptySet<String>()) }
    val records = state.records.filter { record -> record.segments.any { it.personId in sourceIds } }
    val validRecords = records.filter { it.id in recordIds }
    val affected = validRecords.sumOf { record -> record.segments.count { it.personId in sourceIds } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("같은 사람으로 합치기") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("합칠 사람", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id != targetId }.forEach { person ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(person.id in sourceIds, { checked -> sourceIds = if (checked) sourceIds + person.id else sourceIds - person.id })
                    Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("남길 사람", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id !in sourceIds }.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { targetId = person.id }) {
                    RadioButton(targetId == person.id, { targetId = person.id }); Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("함께 고칠 지난 기록", style = MaterialTheme.typography.titleSmall)
            records.forEach { record ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(record.id in recordIds, { checked -> recordIds = if (checked) recordIds + record.id else recordIds - record.id })
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(record.title)
                        Note(java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.CHINA).format(java.util.Date(record.createdAt)))
                    }
                }
            }
            Note("${sourceIds.size}명을 합치고, 고른 기록 ${validRecords.size}개의 문장 ${affected}개를 고칩니다. 고르지 않은 기록은 그대로 둡니다.")
        }
    }, confirmButton = { TextButton(onClick = {
        model.attempt { model.service().conversations.mergePeople(sourceIds, requireNotNull(targetId), validRecords.map { it.id }.toSet()) }
        dismiss()
    }, enabled = sourceIds.isNotEmpty() && targetId != null && targetId !in sourceIds) { Text("합치기") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
