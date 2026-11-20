// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Review AnkiDroid decks: the card shows here and on the glasses' answer page; ratings go back to AnkiDroid. */
@Composable
internal fun AnkiScreen(model: AppModel, actions: UiActions) {
    val context = LocalContext.current
    val anki = model.state.anki
    val installed = remember { AnkiDroid.installed(context) }
    LaunchedEffect(model.ankiAllowed) {
        if (installed && model.ankiAllowed && anki.decks.isEmpty() && anki.card == null) model.attempt { model.service().anki.loadDecks() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!installed) {
            Note("AnkiDroid 앱의 덱을 복습합니다. 먼저 휴대폰에 AnkiDroid를 설치하고 덱을 만들거나 가져오세요.")
            ToolButton(onClick = { model.attempt { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AnkiDroid.PACKAGE}"))) } }) {
                Text("AnkiDroid 설치하기")
            }
            return@Column
        }
        if (!model.ankiAllowed) {
            Note("AnkiDroid 덱과 오늘 복습할 카드를 읽고, 평가 결과를 AnkiDroid에 저장하려면 접근 권한이 필요합니다. 복습 간격 계산은 AnkiDroid가 합니다.")
            ToolButton(onClick = actions.ankiPermission) { Text("AnkiDroid 접근 허용") }
            return@Column
        }
        if (anki.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (anki.notice.isNotBlank()) Note(anki.notice)
        val deck = anki.deck; val card = anki.card
        if (deck != null && card != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(deck.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Note("이번에 ${anki.reviewed}장 복습")
            }
            ContentCard {
                Text(card.question, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (anki.flipped) {
                    HorizontalDivider()
                    Text(card.answer, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
            if (!anki.flipped) ToolButton(onClick = { model.attempt { model.service().anki.flip() } }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("뒷면 보기")
            } else {
                // AnkiDroid numbers the buttons 1…count: 4 = again/hard/good/easy, 3 = again/good/easy, 2 = again/good.
                val labels = when (card.buttons) { 2 -> listOf("다시", "알맞음"); 3 -> listOf("다시", "알맞음", "쉬움"); else -> listOf("다시", "어려움", "알맞음", "쉬움") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, label ->
                        val button: @Composable RowScope.() -> Unit = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label)
                                card.nextTimes.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        val modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                        if (index == 0) ToolOutlinedButton(onClick = { model.attempt { model.service().anki.grade(1) } }, modifier, enabled = !anki.busy, content = button)
                        else ToolButton(onClick = { model.attempt { model.service().anki.grade(index + 1) } }, modifier, enabled = !anki.busy, content = button)
                    }
                }
            }
            TextButton(onClick = { model.attempt { model.service().anki.stop() } }) { Text("복습 끝내기") }
            Note(if (model.state.glasses.link.phase == LinkPhase.FULL) "카드가 안경 답변 화면에도 표시됩니다. 긴 카드는 몇 초마다 다음 쪽으로 넘어갑니다."
                else "안경이 연결되어 있으면 카드가 안경 답변 화면에도 표시됩니다.")
            Note("안경 다이얼로 넘기기는 아직 확인하지 않았습니다. 복습 중 안경 다이얼을 눌러 보시면 안경이 보내는 신호가 앱 로그에 남아, 그걸 보고 연결할 수 있는지 판단합니다.")
        } else {
            if (deck != null) ToolOutlinedButton(onClick = { model.attempt { model.service().anki.stop() } }) { Text("덱 목록으로") }
            SectionLabel("덱")
            if (anki.decks.isEmpty() && !anki.busy) Note("덱을 읽지 못했습니다. AnkiDroid를 한 번 열어 컬렉션을 준비한 뒤 다시 읽어 보세요.")
            anki.decks.forEach { item ->
                OutlinedCard(onClick = { model.attempt { model.service().anki.start(item) } }, Modifier.fillMaxWidth(), enabled = !anki.busy) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Note(if (item.due == 0) "오늘 끝" else "새 ${item.new} · 학습 ${item.learn} · 복습 ${item.review}")
                    }
                }
            }
            ToolOutlinedButton(onClick = { model.attempt { model.service().anki.loadDecks() } }, enabled = !anki.busy) { Text("덱 다시 읽기") }
        }
    }
}
