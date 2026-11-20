// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent

internal enum class MicroButton { START, SELECT, LEFT, UP, DOWN, RIGHT, X, Y, A, B }

private val gamepadKeys = mapOf(
    KeyEvent.KEYCODE_BUTTON_START to MicroButton.START,
    KeyEvent.KEYCODE_BUTTON_SELECT to MicroButton.SELECT,
    KeyEvent.KEYCODE_DPAD_LEFT to MicroButton.LEFT,
    KeyEvent.KEYCODE_DPAD_UP to MicroButton.UP,
    KeyEvent.KEYCODE_DPAD_DOWN to MicroButton.DOWN,
    KeyEvent.KEYCODE_DPAD_RIGHT to MicroButton.RIGHT,
    KeyEvent.KEYCODE_BUTTON_X to MicroButton.X,
    KeyEvent.KEYCODE_BUTTON_Y to MicroButton.Y,
    KeyEvent.KEYCODE_BUTTON_A to MicroButton.A,
    KeyEvent.KEYCODE_BUTTON_B to MicroButton.B,
)

private val keyboardKeys = mapOf(
    KeyEvent.KEYCODE_E to MicroButton.START,
    KeyEvent.KEYCODE_I to MicroButton.SELECT,
    KeyEvent.KEYCODE_F to MicroButton.LEFT,
    KeyEvent.KEYCODE_D to MicroButton.UP,
    KeyEvent.KEYCODE_C to MicroButton.DOWN,
    KeyEvent.KEYCODE_J to MicroButton.RIGHT,
    KeyEvent.KEYCODE_X to MicroButton.X,
    KeyEvent.KEYCODE_Y to MicroButton.Y,
    KeyEvent.KEYCODE_A to MicroButton.A,
    KeyEvent.KEYCODE_B to MicroButton.B,
)

internal enum class RemoteAction(val label: String, vararg val defaults: MicroButton) {
    ANKI_FLIP("뒷면 보기", MicroButton.START),
    ANKI_PREVIOUS("이전 쪽", MicroButton.LEFT, MicroButton.UP),
    ANKI_NEXT("다음 쪽", MicroButton.RIGHT, MicroButton.DOWN),
    ANKI_AGAIN("다시", MicroButton.X),
    ANKI_HARD("어려움", MicroButton.Y),
    ANKI_GOOD("알맞음", MicroButton.A),
    ANKI_EASY("쉬움", MicroButton.B),
    PROMPT_TOGGLE("일시정지·재개", MicroButton.START),
    PROMPT_STOP("재생 중지", MicroButton.SELECT),
    PROMPT_DOWN("아래로 스크롤", MicroButton.DOWN),
    PROMPT_UP("위로 스크롤", MicroButton.UP),
}

internal val ankiRemoteActions = listOf(
    RemoteAction.ANKI_FLIP, RemoteAction.ANKI_PREVIOUS, RemoteAction.ANKI_NEXT,
    RemoteAction.ANKI_AGAIN, RemoteAction.ANKI_HARD, RemoteAction.ANKI_GOOD, RemoteAction.ANKI_EASY,
)
internal val prompterRemoteActions = listOf(
    RemoteAction.PROMPT_TOGGLE, RemoteAction.PROMPT_STOP, RemoteAction.PROMPT_UP, RemoteAction.PROMPT_DOWN,
)

internal data class RemoteKey(val device: String, val code: Int) {
    val label: String get() = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_")
    fun encode() = "$device:$code"

    companion object {
        fun decode(value: String?): RemoteKey? {
            val encoded = value ?: return null
            val code = encoded.substringAfterLast(':', "").toIntOrNull() ?: return null
            return RemoteKey(encoded.substringBeforeLast(':'), code)
        }

        fun from(key: KeyEvent): RemoteKey? {
            val device = key.device ?: return null
            if (!device.isExternal || device.isVirtual || key.keyCode == KeyEvent.KEYCODE_BACK ||
                key.keyCode == KeyEvent.KEYCODE_UNKNOWN || KeyEvent.isModifierKey(key.keyCode) || !key.hasNoModifiers()) return null
            return RemoteKey(device.descriptor, key.keyCode)
        }
    }
}

internal fun remoteActionFor(
    key: RemoteKey, deviceName: String, actions: List<RemoteAction>, bindings: Map<RemoteAction, RemoteKey>,
): RemoteAction? {
    val button = when (deviceName) {
        "8BitDo Micro gamepad" -> gamepadKeys[key.code]
        "8BitDo Micro gamepad Keyboard" -> keyboardKeys[key.code]
        else -> null
    }
    // A binding replaces the default only on the device it was made on; other devices keep their default keys.
    return actions.firstOrNull { bindings[it] == key }
        ?: actions.firstOrNull { bindings[it]?.device != key.device && button in it.defaults }
}

/** The key shown for an action: its binding, else its 8BitDo Micro default buttons. */
internal fun remoteKeyLabel(action: RemoteAction, bindings: Map<RemoteAction, RemoteKey>): String =
    bindings[action]?.label ?: action.defaults.joinToString(" / ") { it.label() }

/** Another action whose explicit binding sits on a default button of [action], which would make that default unreachable. */
internal fun remoteRestoreConflict(action: RemoteAction, actions: List<RemoteAction>, bindings: Map<RemoteAction, RemoteKey>): RemoteAction? =
    actions.firstOrNull { other ->
        other != action && bindings[other]?.code?.let { code -> (gamepadKeys[code] ?: keyboardKeys[code]) in action.defaults } == true
    }

internal fun MicroButton.label(): String = when (this) {
    MicroButton.START -> "+"
    MicroButton.SELECT -> "−"
    MicroButton.LEFT -> "←"
    MicroButton.UP -> "↑"
    MicroButton.DOWN -> "↓"
    MicroButton.RIGHT -> "→"
    else -> name
}

/** Focus the active task's screen and handle shortcuts before Compose navigation. */
@Composable
internal fun Modifier.remoteShortcuts(
    active: Boolean,
    actions: List<RemoteAction>,
    bindings: Map<RemoteAction, RemoteKey>,
    onPress: (RemoteAction) -> Unit,
): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) focus.requestFocus() }
    if (!active) return this
    return onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val binding = RemoteKey.from(key) ?: return@onPreviewKeyEvent false
        val action = remoteActionFor(binding, key.device.name, actions, bindings)
        // Unhandled gamepad keys fall back to system actions (B becomes Back): swallow every key from a remote device.
        val remoteDevice = key.device.name.startsWith("8BitDo Micro") || bindings.values.any { it.device == binding.device }
        if (action == null && !remoteDevice) return@onPreviewKeyEvent false
        if (action != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onPress(action)
        key.action == KeyEvent.ACTION_DOWN || key.action == KeyEvent.ACTION_UP
    }.focusRequester(focus).focusable()
}

/** Translate semantic ratings to the slots actually exposed for the current card. */
internal fun MicroButton.ankiEase(buttons: Int): Int? = when (this) {
    MicroButton.X -> 1
    MicroButton.Y -> if (buttons == 4) 2 else null
    MicroButton.A -> when (buttons) { 2, 3 -> 2; 4 -> 3; else -> null }
    MicroButton.B -> when (buttons) { 3 -> 3; 4 -> 4; else -> null }
    else -> null
}
