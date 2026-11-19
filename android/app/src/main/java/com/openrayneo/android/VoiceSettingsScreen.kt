// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val voiceKeys = listOf("asrEngine", "asrKey", "asrUrl", "asrModel", "asrLanguage", "nativeAutoAnswer", "audioChannel", "speakerThreshold", "memoryPrompt", "memoryAutoSummary", "answerClearSeconds", "answerMaxChars", "glassesVoiceGate", "selfVoiceMarginDb", "selfVoiceFloorDb", "selfVoiceHoldMs", "bargeInLevelDb", "bargeInMinMs", "asrKeepLoaded")
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("음성 및 기억 설정", style = MaterialTheme.typography.headlineSmall)
        Text("앱 빌드 ${BuildConfig.GIT_COMMIT} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        Note("음성 특징과 화자 처리는 이 기기에서 실행되며 ASR과 기록 요약은 설정한 서비스를 사용합니다. 답변, 짧은 힌트, 작업 판단은 휴대폰 로컬 Agent가 맡고 키는 Android Keystore로 암호화 저장됩니다.")
        SectionLabel("ASR 엔진")
        // The draft is the single source: what is shown is exactly what "save" writes, also after leaving unsaved.
        val engine = drafts["asrEngine"].orEmpty().ifBlank { "cloud" }
        Column {
            listOf("cloud" to "클라우드 (Bailian Qwen)", "nemotron" to "기기 내장 · 실시간 다국어 (Nemotron)", "stream" to "기기 내장 · 실시간 한국어 (zipformer)",
                "local" to "기기 내장 · 문장 단위 (SenseVoice)").forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(engine == key, { drafts["asrEngine"] = key }); Text(label) }
            }
        }
        Note("클라우드는 정확도가 가장 높고 말 다듬기(군말 제거)를 하지만 네트워크 지연이 있습니다. 실시간 기기 내장은 말하는 동안 글자가 나오고 고쳐집니다. Nemotron은 한국어·영어 등 다국어(언어 힌트가 하나면 그 언어로 고정, 아니면 자동 감지)이며 무겁고, zipformer는 가볍지만 한국어 전용입니다. 문장 단위 기기 내장은 문장이 끝난 뒤 인식하고 언어를 자동 감지해, 아래 언어 힌트 밖의 결과를 버립니다. 기기 내장은 모두 잡음 제거 후 인식합니다. 실시간 번역은 영어도 들어야 해서 한국어 스트리밍 대신 SenseVoice(없으면 클라우드)를 씁니다.")
        val context = LocalContext.current
        SpeechRounds.model(JSONObject().put("asrEngine", engine))?.let { local ->
            var access by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
            var recheck by remember { mutableIntStateOf(0) }
            val installed = remember(local, local.downloading, access, recheck) { local.installed(context) }
            if (installed) Text("${local.title} 모델 준비됨" + if (local.shared(context)) " (공용 폴더 사용)" else " (앱 내부 저장소, 재설치 시 삭제됨)")
            else ToolButton(onClick = { local.download(context.applicationContext) }, enabled = !local.downloading, modifier = Modifier.fillMaxWidth()) {
                Text(if (local.downloading) "${(local.progress * 100).toInt()}% 진행 중…"
                    else "${local.title} 모델 내려받기 (${local.size}, Wi-Fi 권장" + if (access) ", 공용 폴더에 저장)" else ")")
            }
            val movable = remember(local, local.downloading, access, recheck) { local.movable(context) }
            if (movable) ToolOutlinedButton(onClick = { local.moveToShared(context.applicationContext) }, enabled = !local.downloading, modifier = Modifier.fillMaxWidth()) {
                Text("앱 내부 모델을 공용 폴더로 옮기기 (백업용)")
            }
            Note("모델 폴더: 휴대폰 저장소의 ${LocalModels.SHARED_ROOT}/${local.sharedName}/ (공식 압축 파일을 풀면 같은 폴더명). 필요한 파일: ${local.fileNames.joinToString(", ")}. " +
                "모든 파일 접근을 허용하면 내려받기도 이 폴더에 저장되어, 앱을 지웠다 깔아도 남고 PC로 복사해 백업할 수 있습니다.")
            if (!access) ToolOutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + context.packageName)))
            }, modifier = Modifier.fillMaxWidth()) { Text("공용 모델 폴더 사용 허용 (모든 파일 접근)") }
            else if (!installed) ToolOutlinedButton(onClick = { recheck++ }, modifier = Modifier.fillMaxWidth()) { Text("복사한 폴더 다시 확인") }
            if (!access) LaunchedEffect(Unit) {
                // Pick up the permission when the user returns from system settings.
                while (true) { kotlinx.coroutines.delay(1000); if (android.os.Environment.isExternalStorageManager()) { access = true; break } }
            }
            if (local.downloading) LinearProgressIndicator(progress = { local.progress }, modifier = Modifier.fillMaxWidth())
            local.message?.takeIf { !local.downloading }?.let { Note(it) }
        }
        // Every control below reads the draft directly, like the engine choice, so leaving unsaved cannot split screen and save.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(drafts["asrKeepLoaded"] != "false", { drafts["asrKeepLoaded"] = it.toString() }); Text("기기 내장 ASR 모델을 항상 메모리에 유지")
        }
        Note("켜면 고른 기기 내장 모델을 미리 올려 두어 깨울 때 불러오기 지연(Nemotron은 수 초)이 없습니다. 휴대폰 메모리를 계속 차지합니다(Nemotron 약 700MB, SenseVoice 약 250MB). 끄면 1분 동안 쓰지 않을 때 내립니다.")
        SectionLabel("Bailian ASR")
        VoiceField(model, "asrKey", "API Key", secret = true)
        VoiceField(model, "asrUrl", "WSS 주소", "wss://dashscope.aliyuncs.com/api-ws/v1/inference")
        VoiceField(model, "asrModel", "모델", "qwen-audio-3.1-asr-flash-message")
        val asrLanguages = drafts["asrLanguage"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        fun toggleLanguage(code: String, on: Boolean) {
            val chosen = if (on) asrLanguages + code else asrLanguages - code
            drafts["asrLanguage"] = listOf("ko", "en").filter { it in chosen }.joinToString(",")
        }
        Text("인식 언어 힌트")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox("ko" in asrLanguages, { toggleLanguage("ko", it) }); Text("한국어")
            Checkbox("en" in asrLanguages, { toggleLanguage("en", it) }); Text("영어")
        }
        Note("선택하지 않으면 자동 감지합니다. 클라우드는 language_hints로 전달하고, 기기 내장은 고른 언어 밖의 결과(예: 잡음을 중국어로 읽은 결과)를 버립니다.")
        Row { Checkbox(drafts["nativeAutoAnswer"] == "true", { drafts["nativeAutoAnswer"] = it.toString() }); Text("음성 깨우기로 깨우면 자동 응답") }
        Note("기본 깨우기로 응답한 뒤 오디오를 받아 ASR과 화자 처리를 마치고 다음으로 전달합니다: 로컬 Agent.")
        Row { Checkbox(drafts["glassesVoiceGate"] != "false", { drafts["glassesVoiceGate"] = it.toString() }); Text("종일 기록: 안경 음성 감지(VPU/VAD) 구간만 인식") }
        Note("안경이 말소리로 표시한 구간만 ASR에 보내고 나머지는 무음 처리해 잡음 전사를 줄입니다. 저장되는 원본 오디오는 그대로입니다. 기록 화면의 감지 비율이 0%에 가깝다면 꺼 주세요.")
        SectionLabel("Hey RayNeo: 답변 중 말로 끊기 감도")
        Note("안경 설정의 \"대화 끊기\"가 켜져 있을 때, 답변 중 마이크 소리가 기준보다 큰 상태가 정해진 시간 이상 이어지면 바로 끊습니다(글자 인식을 기다리지 않음). 앱 로그의 \"대화 끊기 진단: 답변 중 마이크 최대 N dBFS\"로 답변 소리 크기를 보고 맞추세요.")
        GateSlider(drafts, "bargeInLevelDb", "감지 기준 소리 크기", -30f, -60f..-10f, 49, { "${it.toInt()} dBFS" },
            "말해도 안 끊기면 내리고(예: -38), 답변 소리나 잡음에 저절로 끊기면 올리세요. 진단 로그의 최대값보다 조금 높게 두면 됩니다.")
        GateSlider(drafts, "bargeInMinMs", "기준 이상이 이어져야 하는 시간", 250f, 50f..1000f, 18, { "${it.toInt()} ms" },
            "짧은 소리에 끊기면 늘리고, 끊기기까지 느리면 줄이세요.")
        SectionLabel("실시간 듣기: 채널 2에서 본인 목소리 걸러내기")
        Note("채널 1(본인 마이크)이 채널 2(바깥 마이크)보다 기준 이상 크면 본인이 말하는 것으로 보고, 그동안 채널 2를 무음 처리해 같은 말이 다른 사람 발화로 두 번 적히지 않게 합니다. 바깥 소리가 커도 두 채널이 함께 커지므로 비율로 판단합니다. 기록 화면에 무음 처리 비율이 표시되며, 다음 기록부터 적용됩니다.")
        GateSlider(drafts, "selfVoiceMarginDb", "판단 기준 (채널 1이 더 커야 하는 정도)", 6f, 0f..20f, 19, { if (it < 0.5f) "끔" else "${it.toInt()} dB" },
            "상대 말이 잘리면 올리고, 본인 말이 채널 2에 남으면 내리세요.")
        GateSlider(drafts, "selfVoiceFloorDb", "무음 판정 수준 (채널 1이 이보다 작으면 판단 안 함)", -50f, -70f..-20f, 9, { "${it.toInt()} dBFS" },
            "주변이 시끄러워 잡음에도 무음 처리되면 올리고(예: -40), 작게 말할 때 걸러지지 않으면 내리세요.")
        GateSlider(drafts, "selfVoiceHoldMs", "말이 끝난 뒤 유지 시간", 300f, 0f..1000f, 9, { "${it.toInt()} ms" },
            "본인 말 끝부분이 채널 2에 남으면 늘리고, 바로 이어지는 상대 말이 잘리면 줄이세요.")
        Row { Checkbox(drafts["memoryAutoSummary"] != "false", { drafts["memoryAutoSummary"] = it.toString() }); Text("롤링·하루 마감·기록 종료 요약 자동 생성") }
        Note("종일 기록과 개별 기록은 원본 오디오를 자동 저장하고, 목소리 등록은 음성 특징만 저장합니다. 전사가 지연되면 오디오를 먼저 저장하고 복구 후 전사와 화자 분석을 보충합니다.")
        VoiceField(model, "answerClearSeconds", "실시간 힌트 답변 후 화면 지우기까지 초 (0이면 유지)", "0")
        VoiceField(model, "answerMaxChars", "안경 답변 최대 글자 수 (0이면 제한 없음)", DEFAULT_ANSWER_MAX_CHARS.toString())
        Note("답변이 안경 화면 아래로 잘리면 숫자를 줄이세요. Agent에게 이 길이 안에서 답하도록 요청합니다.")
        SectionLabel("현재 위치 및 날씨")
        Note("로컬 Agent는 도구로 날씨를 조회할 수 있으며 위치 도구에는 위치 권한이 필요합니다. 백그라운드 음성은 안경 연결 포그라운드 서비스를 사용합니다.")
        TextButton(onClick = actions.locationPermission) { Text("위치 접근 허용") }
        SectionLabel("채널 및 식별 판단")
        Note("일반 2채널 동시 처리: 채널 1은 기본 본인, 채널 2는 별도 식별. 종일 기록과 기타 모노 오디오는 전체 화자 식별. 이 옵션은 직접 등록에만 쓰이며 기록 정책은 바뀌지 않습니다.")
        VoiceField(model, "audioChannel", "등록 진단 채널: 0/1 채널 1, 2 채널 2", "0")
        VoiceField(model, "speakerThreshold", "목소리 매칭 임계값 (내 목소리로 보정 필요)", "0.7")
        SectionLabel("기록 요약")
        VoiceField(model, "modelUrl", "요약 모델 서비스 주소")
        VoiceField(model, "modelName", "요약 모델 이름")
        VoiceField(model, "modelKey", "요약 모델 API Key", secret = true)
        StructuredVoicePrompt(model, "memoryPrompt", "기억 귀속 및 정리", ConversationPrompts.memory)
        Note("실시간 번역(한국어 ↔ 영어)도 이 모델과 위에서 고른 ASR 엔진을 사용합니다.")
        Note("이 모델은 앱 내 기록 요약에 사용됩니다.원문, 화자, 서술 대상은 따로 저장되며 가져올 수 있는 모든 전사는 장기 보관됩니다.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ToolButton(onClick = {
            val invalid = listOf("speakerThreshold")
                .firstOrNull { key -> val value = drafts[key].orEmpty(); value.isNotBlank() && value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } != true }
            if (invalid != null) error = "$invalid 값은 0~1 사이여야 함"
            else if (drafts["audioChannel"].orEmpty().isNotBlank() && drafts["audioChannel"]?.toIntOrNull() !in 0..2) error = "채널은 0, 1, 2 중 하나여야 함"
            else if (drafts["answerMaxChars"].orEmpty().isNotBlank() && drafts["answerMaxChars"]?.toIntOrNull() !in 0..1000) error = "답변 최대 글자 수는 0~1000이어야 함"
            else if (drafts["selfVoiceMarginDb"].orEmpty().isNotBlank() && drafts["selfVoiceMarginDb"]?.toDoubleOrNull()?.let { it in 0.0..30.0 } != true) error = "본인 목소리 판단 기준은 0~30 dB여야 함"
            else if (drafts["answerClearSeconds"].orEmpty().isNotBlank() && drafts["answerClearSeconds"]?.toIntOrNull() !in 0..3600) error = "화면 지우기 지연은 0~3600초여야 함"
            else { error = null; model.saveConfig((voiceKeys + listOf("modelUrl", "modelName", "modelKey")).associateWith { drafts[it].orEmpty() }) }
        }, modifier = Modifier.fillMaxWidth()) { Text("음성 설정 저장") }
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
        Note("선명한 목소리는 이름 없는 인물 프로필로 자동 생성되고 기록 간 연결을 시도합니다. 이름 지정, 병합, 분리 또는 과거 발화 선택으로 수정할 수 있으며 음성 특징은 휴대폰에만 저장됩니다. 본인 등록 시 혼자 서너 문장을 말하고 문장마다 잠깐 쉬어 주세요.")
        if (state.running && state.mode == ConversationMode.ENROLL) {
            var confirmed by remember { mutableStateOf(false) }
            Text("음성 ${state.enrollmentTotalSeconds.toInt()}초 · 일관 샘플 ${state.enrollmentSeconds.toInt()}초 · 수신 오디오 ${state.decodedSeconds.toInt()}초 ")
            if (state.enrollmentCanConfirm && !state.enrollmentReady) {
                Note("모델이 샘플 일관성을 의심합니다. 처음부터 끝까지 본인만 말했다면 이 샘플을 수동으로 표시할 수 있고, 다른 사람이 말했다면 다시 녹음하세요.")
                Row { Checkbox(confirmed, { confirmed = it }); Text("이 음성은 모두 본인이 말한 것임을 확인합니다") }
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
                    if (person.unnamed) Note("자동 생성 · 이름 지정 대기")
                    Note(if (person.samples.isEmpty()) "등록된 목소리 없음" else "등록됨: ${person.samples.size}개 목소리 샘플")
                    if (person.contactUri != null) Note("선택한 연락처와 연결됨")
                    Row {
                        TextButton(onClick = { rename = person }) { Text("이름 지정") }
                        TextButton(onClick = { merge = person }, enabled = state.people.count { it.mergedInto == null } > 1) { Text("병합") }
                        TextButton(onClick = { split = person }) { Text("분리") }
                    }
                    TextButton(onClick = { history = person }) { Text("과거 발화 선택하여 수정") }
                    if (person.samples.isNotEmpty()) TextButton(onClick = { model.service().conversations.removeVoice(person.id) }) { Text("목소리 제거 (인물 기록 유지)") }
                }
            }
        }
    }
    rename?.let { person ->
        var name by remember(person.id) { mutableStateOf(person.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("인물 이름 지정") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("인물 이름") }, singleLine = true)
                Note("이 인물의 이름과 연결 표시를 업데이트합니다. 관련 요약과 Agent 결과는 새로 고침 필요로 표시됩니다.")
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
    AlertDialog(onDismissRequest = dismiss, title = { Text("인물 프로필 병합") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("병합할 프로필", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id != targetId }.forEach { person ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(person.id in sourceIds, { checked -> sourceIds = if (checked) sourceIds + person.id else sourceIds - person.id })
                    Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("병합 대상", style = MaterialTheme.typography.titleSmall)
            people.filter { it.id !in sourceIds }.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { targetId = person.id }) {
                    RadioButton(targetId == person.id, { targetId = person.id }); Text(person.name, Modifier.padding(top = 12.dp))
                }
            }
            Text("과거 귀속 수정 범위", style = MaterialTheme.typography.titleSmall)
            records.forEach { record ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(record.id in recordIds, { checked -> recordIds = if (checked) recordIds + record.id else recordIds - record.id })
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(record.title)
                        Note(java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.CHINA).format(java.util.Date(record.createdAt)))
                    }
                }
            }
            Note("병합할 목소리 프로필 ${sourceIds.size}개, 수정할 선택 기록 ${validRecords.size}개, ${affected}개 발화. 관련 요약과 Agent 결과는 새로 고침 필요로 표시됩니다. 선택하지 않은 과거 원문은 현재 귀속을 유지합니다.")
        }
    }, confirmButton = { TextButton(onClick = {
        model.attempt { model.service().conversations.mergePeople(sourceIds, requireNotNull(targetId), validRecords.map { it.id }.toSet()) }
        dismiss()
    }, enabled = sourceIds.isNotEmpty() && targetId != null && targetId !in sourceIds) { Text("병합 확인") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}
