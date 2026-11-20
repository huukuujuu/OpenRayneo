// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Settings changed by the problem the user notices instead of by number: each tap moves the related settings one
 * step, saves at once and says what changed, with undo. The numbers stay editable under advanced settings.
 */
internal object SettingFixes {
    /**
     * One numeric setting: default when blank, the range a fix moves it in, how it is shown. Some settings use 0 for
     * "off/unlimited" (the far end above [max]) or for "the built-in default" ([zeroAs] when stepping); a fix never
     * writes 0 and never moves a value against the direction it asks for.
     */
    data class Knob(val key: String, val default: Double, val min: Double, val max: Double,
                    /** 0 = off/unlimited, beyond max: raising stays, lowering starts from [fromOff]. */ val fromOff: Double? = null,
                    /** 0 = built-in default, stepped as this value. */ val zeroAs: Double? = null,
                    val show: (Double) -> String)
    data class Step(val knob: Knob, val delta: Double)
    data class Fix(val id: String, val title: String, val what: String, val steps: List<Step> = emptyList(), val flag: Pair<String, Boolean>? = null)
    data class Group(val title: String, val fixes: List<Fix>)

    private fun int(unit: String): (Double) -> String = { "${it.roundToInt()}$unit" }
    val speaker = Knob("speakerThreshold", 0.7, 0.5, 0.9, show = { "%.2f".format(it) })
    val margin = Knob("selfVoiceMarginDb", 6.0, 1.0, 20.0, fromOff = 10.0, show = { if (it == 0.0) "끔" else "${it.roundToInt()} dB" })
    val floor = Knob("selfVoiceFloorDb", -50.0, -70.0, -20.0, show = int(" dBFS"))
    val hold = Knob("selfVoiceHoldMs", 300.0, 0.0, 1000.0, show = int(" ms"))
    val bargeLevel = Knob("bargeInLevelDb", -30.0, -60.0, -10.0, show = int(" dBFS"))
    val bargeTime = Knob("bargeInMinMs", 250.0, 50.0, 1000.0, show = int(" ms"))
    val idleClose = Knob("nativeIdleCloseSeconds", DEFAULT_NATIVE_IDLE_CLOSE_SECONDS.toDouble(), 5.0, 120.0, fromOff = 60.0,
        show = { if (it == 0.0) "닫지 않음" else "${it.roundToInt()}초" })
    val answerChars = Knob("answerMaxChars", DEFAULT_ANSWER_MAX_CHARS.toDouble(), 20.0, 300.0, fromOff = 200.0, show = { if (it == 0.0) "제한 없음" else "${it.roundToInt()}자" })
    val answerHold = Knob("answerClearSeconds", 0.0, 3.0, 600.0, zeroAs = 15.0, show = { if (it == 0.0) "기본" else "${it.roundToInt()}초" })

    val groups = listOf(
        Group("누가 말했는지", listOf(
            Fix("self-missed", "내 말이 다른 사람(A, B…)으로 표시돼요", "내 목소리로 인정하는 기준을 조금 낮춥니다.", listOf(Step(speaker, -0.05))),
            Fix("other-as-self", "다른 사람 말이 '나'로 표시돼요", "내 목소리로 인정하는 기준을 조금 높입니다.", listOf(Step(speaker, +0.05))),
        )),
        Group("실시간 듀얼 마이크", listOf(
            Fix("self-twice", "내 말이 상대방 말로 한 번 더 적혀요", "바깥 마이크에서 내 목소리를 더 적극적으로 걸러냅니다.", listOf(Step(margin, -2.0), Step(hold, +100.0))),
            Fix("other-cut", "상대방 말이 잘리거나 빠져요", "바깥 마이크를 덜 걸러서 상대방 말을 더 남깁니다.", listOf(Step(margin, +2.0), Step(hold, -100.0))),
            Fix("other-noise", "시끄러운 곳에서 상대방 말이 무시돼요", "주변 소음을 내 목소리로 착각하지 않게 판단 하한을 올립니다.", listOf(Step(floor, +5.0))),
        )),
        Group("종일 기록", listOf(
            Fix("gate-off", "시끄러울 때 내 말이 기록에서 빠져요", "말소리 판단 없이 모든 소리를 받아 적습니다. 빠지는 말은 줄지만 조용한 시간에도 인식 비용이 나옵니다.", flag = "glassesVoiceGate" to false),
            Fix("gate-on", "조용한데 잡음이 글자로 적혀요", "말소리가 들린 구간만 받아 적습니다. 조용한 시간은 인식 서버로 보내지 않아 비용도 줄어듭니다.", flag = "glassesVoiceGate" to true),
        )),
        Group("Hey RayNeo 깨우기", listOf(
            Fix("popup-long", "깨우기 창이 너무 오래 떠 있어요", "말이 없을 때 창을 더 빨리 닫습니다.", listOf(Step(idleClose, -5.0))),
            Fix("popup-short", "깨우기 창이 너무 빨리 닫혀요", "창을 닫기 전에 더 기다립니다.", listOf(Step(idleClose, +10.0))),
            Fix("barge-miss", "답변 중에 말해도 안 끊겨요", "더 작은 목소리, 더 짧은 말에도 답변을 끊습니다.", listOf(Step(bargeLevel, -4.0), Step(bargeTime, -50.0))),
            Fix("barge-false", "답변이 저절로 끊겨요", "답변 소리나 잡음에는 끊지 않도록 기준을 올립니다.", listOf(Step(bargeLevel, +4.0), Step(bargeTime, +100.0))),
        )),
        Group("안경 답변 화면", listOf(
            Fix("answer-cut", "답변이 화면 아래로 잘려요", "답변을 더 짧게 받습니다.", listOf(Step(answerChars, -10.0))),
            Fix("answer-short", "답변이 너무 짧아요", "답변을 더 길게 받습니다.", listOf(Step(answerChars, +20.0))),
            Fix("answer-gone", "답변이 너무 빨리 사라져요", "답변을 더 오래 보여 줍니다.", listOf(Step(answerHold, +10.0))),
            Fix("answer-stays", "답변이 화면에 너무 오래 남아요", "답변을 더 빨리 지웁니다.", listOf(Step(answerHold, -5.0))),
        )),
    )

    fun value(config: JSONObject, knob: Knob): Double = config.optString(knob.key).toDoubleOrNull() ?: knob.default

    /** New values for [fix] (only keys that change), plus one line per change; empty when already at the limit. */
    fun apply(config: JSONObject, fix: Fix): Pair<Map<String, String>, List<String>> {
        val values = mutableMapOf<String, String>(); val lines = mutableListOf<String>()
        fix.flag?.let { (key, on) ->
            val now = config.optString(key, "true") != "false"
            if (now != on) { values[key] = on.toString(); lines += if (on) "말소리 구간만 받아 적기: 꺼짐 → 켜짐" else "말소리 구간만 받아 적기: 켜짐 → 꺼짐" }
        }
        fix.steps.forEach { step ->
            val knob = step.knob
            val old = value(config, knob)
            val next = when {
                old == 0.0 && knob.fromOff != null -> if (step.delta > 0) old else knob.fromOff   // off = beyond max
                else -> {
                    val from = if (old == 0.0 && knob.zeroAs != null) knob.zeroAs else old
                    val target = from + step.delta
                    // Clamp into the range, but never move the opposite way (a value saved outside the range stays).
                    val clamped = target.coerceIn(knob.min, knob.max)
                    val moved = if (step.delta > 0) maxOf(from, clamped) else minOf(from, clamped)
                    if (moved == from) old else Math.round(moved * 100) / 100.0
                }
            }
            if (next != old) {
                values[step.knob.key] = if (next == Math.floor(next)) next.toLong().toString() else next.toString()
                lines += "${label(step.knob)}: ${step.knob.show(old)} → ${step.knob.show(next)}"
            }
        }
        return values to lines
    }

    fun label(knob: Knob) = when (knob.key) {
        "speakerThreshold" -> "내 목소리 인정 기준"
        "selfVoiceMarginDb" -> "본인 판단 기준"
        "selfVoiceFloorDb" -> "판단 하한"
        "selfVoiceHoldMs" -> "말 끝 유지 시간"
        "bargeInLevelDb" -> "끊기 소리 크기"
        "bargeInMinMs" -> "끊기 지속 시간"
        "nativeIdleCloseSeconds" -> "깨우기 창 자동 닫기"
        "answerMaxChars" -> "답변 최대 길이"
        "answerClearSeconds" -> "답변 표시 시간"
        else -> knob.key
    }
}

/** "불편한 점 고치기": tap a problem to nudge its settings one step; the last change can be undone. */
@Composable
internal fun SettingFixesSection(model: AppModel) {
    var last by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Note("겪고 있는 문제를 누르면 관련 설정이 한 단계씩 바뀌고 바로 저장됩니다. 나아질 때까지 여러 번 눌러도 됩니다.")
    last?.let { (message, previous) ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { model.saveSettingsQuietly(previous); last = null; model.notice = "되돌렸습니다" }) { Text("되돌리기") }
            }
        }
    }
    SettingFixes.groups.forEach { group ->
        val expanded = open == group.title
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column {
                TextButton(onClick = { open = if (expanded) null else group.title }, modifier = Modifier.fillMaxWidth()) {
                    Text(group.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (expanded) "접기" else "열기")
                }
                if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.fixes.forEach { fix ->
                        OutlinedButton(onClick = {
                            val config = model.config
                            val (values, lines) = SettingFixes.apply(config, fix)
                            if (values.isEmpty()) { model.notice = "이미 끝까지 조정했습니다. 고급 설정에서 직접 바꿀 수 있습니다."; return@OutlinedButton }
                            val previous = values.keys.associateWith { config.optString(it) }
                            model.saveSettingsQuietly(values)
                            last = lines.joinToString("\n") to previous
                        }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(fix.title)
                                Text(fix.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
