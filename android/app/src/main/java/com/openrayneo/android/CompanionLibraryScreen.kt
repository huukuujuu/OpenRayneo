// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

@Composable
internal fun AgentServerScreen(model: AppModel, requestBluetoothPermission: () -> Unit) {
    val context = LocalContext.current
    val state = model.state.agentServer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("DSH Go 연결", style = MaterialTheme.typography.headlineSmall)
        Note("어시스턴트 채팅과 음성 질문은 휴대폰 어시스턴트가 받아 DSH Go 플러그인에 넘깁니다. 답변 방식, 도구 승인, 휴대폰 조작은 DSH Go가 맡고, 답변은 이 앱과 안경에 표시됩니다.")
        Note("안경과의 블루투스 연결은 휴대폰이 유지하고, DSH Go 같은 프로그램이 이 연결을 통해 안경을 다룹니다. 기본은 꺼짐입니다. 켜면 토큰을 가진 휴대폰 안의 프로그램이 기록을 읽고 안경 화면을 바꿀 수 있습니다.")
        Note(state.notice)
        var port by remember(model.config.optString("localAgentPort")) { mutableStateOf(model.config.optString("localAgentPort", "8766")) }
        if (!state.enabled) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("로컬 포트") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            TextButton(onClick = { model.saveConfig(mapOf("localAgentPort" to port)) }, enabled = port.toIntOrNull() in 1024..65535) { Text("포트 저장") }
        }
        if (!model.bluetoothAllowed) {
            Note("백그라운드 기기 서비스에는 Android 근처 기기 권한이 필요합니다. 이 버튼은 권한만 요청하며 안경을 스캔하거나 연결하지 않습니다.")
            ToolButton(onClick = requestBluetoothPermission) { Text("근처 기기 권한 허용") }
        } else ToolButton(onClick = { model.attempt { model.service().enableAgentServer(!state.enabled) } }) { Text(if (state.enabled) "휴대폰 어시스턴트 끄기" else "휴대폰 어시스턴트 켜기") }
        if (state.enabled) {
            Text("http://127.0.0.1:${state.port}")
            ToolOutlinedButton(onClick = { model.attempt {
                val token = model.service().agentServer.tokenForUser()
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenRayneo Agent", "http://127.0.0.1:${state.port}\nAuthorization: Bearer $token"))
                model.notice = "주소와 토큰이 복사됨"
            } }) { Text("주소와 액세스 토큰 복사") }
            TextButton(onClick = { model.attempt { model.service().agentServer.rotate() } }) { Text("토큰 재설정 (이전 토큰 무효화)") }
            Note("DSH Go: openrayneo-dsh 플러그인을 설치하고, 여기서 주소와 액세스 토큰을 복사한 뒤 DSH 채팅 화면에서 '안경 연결'을 누르고 클립보드 읽기를 허용해 자격 증명을 저장하세요. 포트를 바꿨다면 플러그인의 Service URL도 먼저 수정하세요. 두 앱 모두 백그라운드 실행 권한을 유지하세요.")
            Note("컴퓨터에서 USB로 접근: adb forward tcp:8766 tcp:${state.port}. 이후 컴퓨터의 127.0.0.1:8766으로 요청하세요. 서비스는 휴대폰 로컬 주소에서만 수신하며 LAN에 직접 노출되지 않습니다.")
            Note("켜져 있으면 기록 조회, 안경 조작, 작업 받기와 답변 보내기를 할 수 있습니다. 기록 동기화는 아래에서 따로 켭니다.")
        }
        HorizontalDivider()
        SectionLabel("휴대폰-컴퓨터 근거리 동기화")
        Note("같은 네트워크 또는 USB 포워딩 상태에서 컴퓨터가 기록, 전사, 인물, 요약을 보충하고 분석과 메모는 휴대폰으로 돌아옵니다. 원본 오디오는 필요할 때만 가져옵니다. 오프라인 동안 각 기기는 데이터와 동기화 대기 변경을 보관합니다.")
        if (model.state.available) {
            val sync = model.service().recordSync
            val syncState = model.state.recordSync
            Note(syncState.notice)
            ToolButton(onClick = { model.attempt { if (syncState.enabled) sync.stop() else sync.start() } }) {
                Text(if (syncState.enabled) "기록 동기화 끄기" else "기록 동기화 켜기")
            }
            if (syncState.enabled) {
                val addresses = sync.addresses()
                addresses.forEach { Note(it) }
                if (addresses.isEmpty()) Note("현재 LAN 주소가 없습니다. USB 포워딩으로 연결할 수 있습니다.")
                ToolOutlinedButton(onClick = {
                    val address = addresses.firstOrNull() ?: "http://127.0.0.1:${syncState.port}"
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenRayneo 기록 동기화", "$address\nAuthorization: Bearer ${sync.tokenForUser()}"))
                    model.notice = "동기화 주소와 전용 토큰이 복사됨"
                }) { Text("동기화 주소와 전용 토큰 복사") }
                TextButton(onClick = { model.attempt { sync.rotate() } }) { Text("동기화 토큰 재설정") }
                Note("Mac의 기록 동기화 설정에 주소와 토큰을 입력하세요. USB 연결 시 adb forward tcp:8767 tcp:${syncState.port} 실행 후 Mac에 입력: http://127.0.0.1:8767.")
            }
        } else Note("기기 서비스가 준비되면 기록 동기화를 켤 수 있습니다.")
    }
}

@Composable
internal fun TeleprompterScreen(model: AppModel) {
    val state = model.state.teleprompter
    // Saveable so the draft survives rotation and leaving the screen; the saved copy tells whether edits would be lost.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var savedTitle by rememberSaveable { mutableStateOf("") }
    var savedText by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableIntStateOf(2) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    // Plain text files (.txt, .md, ...) become scripts named after the file.
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        // Reading may download from a cloud provider: do it off the main thread, then save on main.
        kotlin.concurrent.thread(name = "script-import") {
            val read = runCatching {
                uris.map { uri ->
                    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "원고"
                    val bytes = checkNotNull(resolver.openInputStream(uri)) { "$name: 파일을 열 수 없음" }.use { it.readNBytes(1_000_001) }
                    require(bytes.size <= 1_000_000) { "$name: 1MB보다 큰 파일은 가져오지 않습니다" }
                    name to bytes
                }
            }
            androidx.core.content.ContextCompat.getMainExecutor(context).execute {
                model.attempt { model.notice = "원고 ${model.service().teleprompter.import(read.getOrThrow())}개를 가져왔습니다" }
            }
        }
    }
    fun load(id: String?, newTitle: String, newText: String) { selected = id; title = newTitle; text = newText; savedTitle = newTitle; savedText = newText }
    fun switchTo(id: String?, newTitle: String, newText: String) =
        if (title != savedTitle || text != savedText) pending = { load(id, newTitle, newText) } else load(id, newTitle, newText)
    pending?.let { discard ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("저장하지 않은 원고") },
            text = { Text("지금 원고의 변경 내용을 저장하지 않았습니다. 버리고 넘어갈까요?") },
            confirmButton = { TextButton(onClick = { pending = null; discard() }) { Text("버리기") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("계속 편집") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("프롬프터 원고함", style = MaterialTheme.typography.headlineSmall)
        Note("원고는 휴대폰에 저장되며 공식 클라우드를 사용하지 않습니다. 전송 시 현재 표시와 음성 수신을 중지한 뒤 안경으로 전송합니다.")
        Note(state.notice)
        ToolOutlinedButton(onClick = { importFiles.launch(arrayOf("text/*")) }, enabled = !state.busy) { Text("텍스트 파일 가져오기") }
        Note("휴대폰에 저장된 원고는 안경과 연결되면 안경의 프롬프터 목록에도 보내져, 안경 메뉴에서 바로 고를 수 있습니다.")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.active != null) Row {
            TextButton(onClick = { model.attempt { model.service().teleprompter.control(if (state.paused) "resume" else "pause") {} } }, enabled = !state.busy) { Text(if (state.paused) "계속" else "일시정지") }
            TextButton(onClick = { model.attempt { model.service().teleprompter.control("stop") {} } }, enabled = !state.busy) { Text("프롬프터 중지") }
        }
        state.documents.forEach { document ->
            TextButton(onClick = { switchTo(document.getString("did"), document.getString("title"), document.getString("text")) }) { Text(document.getString("title")) }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("원고 제목") })
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("본문") }, minLines = 6, maxLines = 16)
        Text("스크롤 속도 $speed")
        Slider(speed.toFloat(), { speed = it.toInt() }, valueRange = 1f..10f, steps = 8)
        ToolOutlinedButton(onClick = { model.attempt { selected = model.service().teleprompter.save(title.trim(), text, selected ?: java.util.UUID.randomUUID().toString()); savedTitle = title; savedText = text; model.notice = "원고가 휴대폰에 저장됨" } }, enabled = title.isNotBlank() && text.isNotBlank() && !state.busy) { Text("원고 저장") }
        ToolButton(onClick = { model.attempt { model.service().startTeleprompter(title.trim(), text, speed, selected) {} } },
            enabled = title.isNotBlank() && text.isNotBlank() && !state.busy && model.state.glasses.link.phase == LinkPhase.FULL) { Text("저장 후 안경에서 재생") }
        TextButton(onClick = { switchTo(null, "", "") }) { Text("새 원고") }
    }
}
