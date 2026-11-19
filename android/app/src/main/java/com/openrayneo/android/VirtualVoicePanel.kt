// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** TEMPORARY test aid (remove after the glasses-menu verification): speak test sentences without a real voice. */
@Composable
internal fun VirtualVoicePanel(model: AppModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val pickWav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        kotlin.concurrent.thread(name = "virtual-voice-file") {
            val bytes = runCatching { checkNotNull(resolver.openInputStream(uri)) { "파일을 열 수 없음" }.use { it.readNBytes(VirtualVoice.MAX_FILE_BYTES + 1) } }
            androidx.core.content.ContextCompat.getMainExecutor(context).execute {
                model.attempt {
                    val data = bytes.getOrThrow()
                    require(data.size <= VirtualVoice.MAX_FILE_BYTES) { "10MB보다 큰 WAV는 넣지 않습니다" }
                    model.service().injectVirtualWav(data); model.notice = "WAV 파일을 가상 음성으로 재생합니다"
                }
            }
        }
    }
    TextButton(onClick = { open = !open }) { Text(if (open) "가상 음성 테스트 닫기" else "가상 음성 테스트 (임시)") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("안경에서 기능(실시간 자막, 실시간 답변, 음성 메모, 깨우기)을 먼저 연 뒤 사용하세요. 재생하는 동안 안경 마이크 소리는 무시됩니다.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("말할 문장") }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("오늘 회의는 몇 시에 시작해?", "Where is the nearest station?", "내일 오후 세 시에 팀 회의가 있어요.").forEach { sample ->
                AssistChip(onClick = { text = sample }, label = { Text(sample.take(10) + "…") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.attempt { model.service().speakVirtual(text); model.notice = "가상 음성을 만드는 중…" } }, enabled = text.isNotBlank()) { Text("말하기") }
            OutlinedButton(onClick = { pickWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) { Text("WAV 파일") }
            TextButton(onClick = { model.attempt { model.service().stopVirtualVoice() } }) { Text("중지") }
        }
    }
}
