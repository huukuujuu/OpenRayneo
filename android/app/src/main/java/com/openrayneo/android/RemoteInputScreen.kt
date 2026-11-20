// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteInputScreen(model: AppModel) {
    var editing by remember { mutableStateOf<RemoteAction?>(null) }
    editing?.let { action ->
        val actions = if (action in ankiRemoteActions) ankiRemoteActions else prompterRemoteActions
        val captureFocus = remember { FocusRequester() }
        var conflict by remember(action) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(action.label) },
            text = {
                Column(Modifier.onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val binding = RemoteKey.from(key) ?: return@onPreviewKeyEvent false
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        val owner = remoteActionFor(binding, key.device.name, actions, model.remoteBindings)
                        if (owner != null && owner != action) {
                            conflict = "${binding.label} 키는 ‘${owner.label}’에 이미 지정되어 있습니다."
                        } else {
                            model.bindRemoteKey(action, binding)
                            editing = null
                        }
                    }
                    key.action == KeyEvent.ACTION_DOWN || key.action == KeyEvent.ACTION_UP
                }.focusRequester(captureFocus).focusable()) {
                    Text("컨트롤러 버튼이나 연결된 키보드의 키를 누르세요.")
                    conflict?.let { Note(it) }
                }
                LaunchedEffect(action) { captureFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    val owner = remoteRestoreConflict(action, actions, model.remoteBindings)
                    if (owner != null) conflict = "기본 키를 ‘${owner.label}’이 쓰고 있습니다. 먼저 그 동작의 키를 바꾸세요."
                    else { model.bindRemoteKey(action, null); editing = null }
                }, enabled = action in model.remoteBindings) { Text("기본 키 복원") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } },
        )
    }
    val focus = remember { FocusRequester() }
    var listening by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(listening) {
        if (listening) focus.requestFocus()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Note("동작을 선택한 뒤 연결된 컨트롤러나 키보드에서 사용할 키를 누르세요.")
        listOf("프롬프터" to prompterRemoteActions, "Anki 복습" to ankiRemoteActions).forEach { (title, actions) ->
            SectionLabel(title)
            actions.forEach { action ->
                val key = remoteKeyLabel(action, model.remoteBindings)
                ToolOutlinedButton(onClick = {
                    listening = false
                    editing = action
                }, modifier = Modifier.fillMaxWidth()) { Text("${action.label} · $key") }
            }
        }
        SectionLabel("버튼 입력 확인")
        ToolButton(onClick = {
            if (!listening) events = emptyList()
            listening = !listening
        }) { Text(if (listening) "입력 확인 종료" else "입력 확인 시작") }
        if (listening) {
            Column(
                Modifier.onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val device = key.device
                    if (device == null || !device.isExternal || device.isVirtual ||
                        key.keyCode == KeyEvent.KEYCODE_BACK
                    ) return@onPreviewKeyEvent false
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        val label = KeyEvent.keyCodeToString(key.keyCode)
                        events = (events + "${device.name} · $label · 스캔 ${key.scanCode}").takeLast(20)
                    }
                    key.action == KeyEvent.ACTION_DOWN || key.action == KeyEvent.ACTION_UP
                }.focusRequester(focus).focusable(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Note("버튼 입력 기다리는 중")
            }
        }
        if (events.isNotEmpty()) {
            Note("최근 버튼 입력 (최신순)")
            events.asReversed().forEach { Text(it) }
        }
    }
}
