// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Renders the logical cells from the exact payload, without generating a second arrow or road
 * label.
 */
@Composable
internal fun NavigationGrid(frame: String, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
    }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(20f / 7f).semantics {
            contentDescription =
                "안경 내비게이션 레이아웃, 20열 5행." + frame.replace('█', ' ').replace('　', ' ').trim()
        }
    ) {
        val cellWidth = size.width / 20
        val cellHeight = size.height / 5
        paint.color = SignalGreen.toArgb()
        paint.textSize = cellWidth * .95f
        val baselineOffset = -(paint.ascent() + paint.descent()) / 2
        frame.split('\n').forEachIndexed { row, text ->
            text.forEachIndexed { column, character ->
                if (character == '█') {
                    drawRect(
                        SignalGreen,
                        Offset(column * cellWidth, row * cellHeight),
                        Size(cellWidth, cellHeight),
                    )
                } else if (character != '　' && character != ' ') {
                    drawContext.canvas.nativeCanvas.drawText(
                        character.toString(),
                        (column + .5f) * cellWidth,
                        (row + .5f) * cellHeight + baselineOffset,
                        paint,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GridPreview(frame: String, label: String) {
    Surface(
        color = LensBlack,
        contentColor = SignalGreen,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            NavigationGrid(frame)
        }
    }
}

@Composable
internal fun GlassesOutputPreview(state: GlassesState) {
    val session = state.session
    val content = session?.writtenContent
    val available =
        state.link.phase == LinkPhase.FULL &&
            state.ownership == DisplayOwnership.APP &&
            content != null
    val title =
        when {
            state.ownership == DisplayOwnership.YIELDED -> "표시를 양보함"
            state.link.phase != LinkPhase.FULL -> "아직 안경에 동기화되지 않음"
            session == null -> "앱 표시가 켜져 있지 않음"
            session.phase == PagePhase.CLOSING ->
                if (state.intent == DisplayIntent.Idle) "화면 닫는 중" else "화면 전환 중"
            content == null -> "첫 화면 전송 대기"
            session.content != content -> "업데이트 중 · 아래는 마지막으로 전송된 내용"
            session.mode == PageMode.NAVIGATION -> "내비게이션 · 전송된 내용"
            session.mode == PageMode.CAPTION -> "안경 실시간 자막 · 전송된 내용"
            session.mode == PageMode.FLASHCARD -> "Anki · 전송된 카드"
            else -> "실시간 힌트 · 전송된 내용"
        }
    Surface(
        color = LensBlack,
        contentColor = SignalGreen,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            if (available) {
                if (session.mode == PageMode.NAVIGATION) NavigationGrid(content.text)
                else
                    Column(
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(content.text, style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(color = SignalGreen.copy(alpha = .2f))
                        Text(content.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    }
            } else {
                Text(
                    when {
                        state.ownership == DisplayOwnership.YIELDED -> "안경의 현재 페이지는 기기가 제어하므로 휴대폰에서 읽을 수 없습니다."
                        state.link.phase != LinkPhase.FULL -> "완전히 연결되면 이번 세션에서 전송한 내용을 표시합니다."
                        session != null -> state.displayDescription
                        else -> "내비게이션을 선택하거나 실시간 힌트를 여세요."
                    },
                    Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Note("전송 기록 미리보기이며 실시간 화면 캡처가 아닙니다.", Modifier.padding(top = 8.dp))
}
