// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TEMPORARY test aid (remove after the glasses-menu verification): speak test sentences without a real voice. */
@Composable
internal fun VirtualVoicePanel(model: AppModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("virtual-voice", Context.MODE_PRIVATE) }
    var folder by remember { mutableStateOf(preferences.getString("folder", null)?.let(Uri::parse)) }
    var files by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    // Read off the main thread (files may live on slow storage), then hand the bytes to the service on main.
    fun play(name: String, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        kotlin.concurrent.thread(name = "virtual-voice-file") {
            val bytes = runCatching { checkNotNull(resolver.openInputStream(uri)) { "파일을 열 수 없음" }.use { it.readNBytes(VirtualVoice.MAX_FILE_BYTES + 1) } }
            ContextCompat.getMainExecutor(context).execute {
                model.attempt {
                    val data = bytes.getOrThrow()
                    require(data.size <= VirtualVoice.MAX_FILE_BYTES) { "10MB보다 큰 WAV는 넣지 않습니다" }
                    model.service().injectVirtualWav(data); model.notice = "가상 음성 재생: $name"
                }
            }
        }
    }

    val pickWav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) play("선택한 WAV", uri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        // Keep the folder only if access survives an app restart; drop the grant of the folder it replaces.
        val kept = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (kept.isFailure) { model.notice = "이 폴더의 읽기 권한을 유지하지 못했습니다: ${kept.exceptionOrNull()?.message}"; return@rememberLauncherForActivityResult }
        folder?.takeIf { it != uri }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        preferences.edit { putString("folder", uri.toString()) }
        folder = uri; refresh++
    }
    // null = still loading; a stale listing is cancelled when the folder or refresh changes.
    var listing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open, folder, refresh) {
        val tree = folder
        if (!open || tree == null) return@LaunchedEffect
        listing = null
        val resolver = context.applicationContext.contentResolver
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1) ?: continue
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                            if (name.endsWith(".wav", ignoreCase = true)) add(name to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                        }
                    }.sortedBy { it.first }
                }.orEmpty()
            }
        }
        found.onSuccess { files = it; listing = if (it.isEmpty()) "폴더에 WAV 파일이 없습니다." else "" }
            .onFailure { files = emptyList(); listing = "테스트 음성 폴더를 읽지 못함: ${it.message}. 폴더를 다시 선택하세요" }
    }

    TextButton(onClick = { open = !open }) { Text(if (open) "가상 음성 테스트 닫기" else "가상 음성 테스트 (임시)") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("안경에서 기능(실시간 자막, 실시간 답변, 음성 메모, 깨우기)을 먼저 연 뒤 사용하세요. 재생하는 동안 안경 마이크 소리는 무시됩니다.",
            style = MaterialTheme.typography.bodySmall)
        Text("테스트 음성 폴더 (권장: 다운로드/$FOLDER)", style = MaterialTheme.typography.labelLarge)
        if (folder == null) Text("아직 폴더를 고르지 않았습니다.", style = MaterialTheme.typography.bodySmall)
        else when (val status = listing) {
            null -> Text("폴더를 읽는 중…", style = MaterialTheme.typography.bodySmall)
            "" -> Unit
            else -> Text(status, style = MaterialTheme.typography.bodySmall)
        }
        files.forEach { (name, uri) ->
            OutlinedButton(onClick = { play(name, uri) }, modifier = Modifier.fillMaxWidth()) { Text("▶ ${name.removeSuffix(".wav")}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val start = folder?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
                    ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$FOLDER")
                pickFolder.launch(start)
            }) {
                Text(if (folder == null) "폴더 선택" else "폴더 바꾸기")
            }
            if (folder != null) TextButton(onClick = { refresh++ }) { Text("새로고침") }
            TextButton(onClick = { pickWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) { Text("다른 WAV") }
        }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("직접 말할 문장 (휴대폰 TTS)") }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.attempt { model.service().speakVirtual(text); model.notice = "가상 음성을 만드는 중…" } }, enabled = text.isNotBlank()) { Text("말하기") }
            TextButton(onClick = { model.attempt { model.service().stopVirtualVoice() } }) { Text("중지") }
        }
    }
}

private const val FOLDER = "OpenRayneo/test-voices"
