// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home: the phone's to-do list. Works without the glasses; the glasses list follows when connected. */
@Composable
internal fun TodoCard(model: AppModel) {
    val content = model.state.content
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val open = content.todos.filter { it.optInt("status") == 0 }
    val done = content.todos.filter { it.optInt("status") != 0 }
    fun change(action: String, args: JSONObject) = model.attempt { model.service().content.todoLocal(action, args) }
    fun add() { if (draft.isNotBlank()) { change("add", JSONObject().put("title", draft.trim())); draft = "" } }
    @Composable
    fun TodoItem(item: JSONObject) {
        val id = item.getLong("eventID"); val finished = item.optInt("status") != 0
        if (editing == id) {
            OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (editText.isNotBlank()) change("update", JSONObject().put("event_id", id).put("title", editText.trim())); editing = null }),
                trailingIcon = { TextButton(onClick = { if (editText.isNotBlank()) change("update", JSONObject().put("event_id", id).put("title", editText.trim())); editing = null }) { Text("저장") } })
            return
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(finished, { change("update", JSONObject().put("event_id", id).put("completed", it)) })
            Text(item.optString("title"), Modifier.weight(1f).clickable { editing = id; editText = item.optString("title") },
                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (finished) TextDecoration.LineThrough else null,
                color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (!finished) IconButton(onClick = { change("update", JSONObject().put("event_id", id).put("important", !item.optBoolean("isImportant"))) }) {
                if (item.optBoolean("isImportant")) Icon(Icons.Outlined.Star, "중요 해제", tint = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Outlined.StarBorder, "중요 표시", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { change("delete", JSONObject().put("event_id", id)) }) {
                Icon(Icons.Outlined.Delete, "삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    ContentCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("할 일", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Note(if (open.isEmpty()) "모두 끝남" else "${open.size}개 남음")
        }
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("할 일 추가") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { IconButton(onClick = ::add, enabled = draft.isNotBlank()) { Icon(Icons.Outlined.Add, "추가") } })
        Column {
            open.forEach { item -> key(item.getLong("eventID")) { TodoItem(item) } }
            if (done.isNotEmpty()) {
                TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "완료한 ${done.size}개 숨기기" else "완료한 ${done.size}개 보기") }
                if (showDone) done.forEach { item -> key(item.getLong("eventID")) { TodoItem(item) } }
            }
        }
        Note(content.todoNotice.ifBlank {
            if (model.state.glasses.link.phase == LinkPhase.FULL) "안경 할 일 목록과 자동으로 맞춥니다" else "휴대폰에 저장됩니다. 안경이 연결되면 안경 목록에도 반영합니다"
        })
    }
}

/** Home: the phone's calendars (Google Calendar on the phone), read only, for the coming week. */
@Composable
internal fun CalendarCard(model: AppModel, actions: UiActions, settings: () -> Unit) {
    val content = model.state.content
    val context = LocalContext.current
    var later by rememberSaveable { mutableStateOf(false) }
    ContentCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("일정", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (content.calendarAllowed) TextButton(onClick = settings) { Text("캘린더 설정") }
        }
        if (!content.calendarAllowed) {
            Note("휴대폰에 로그인된 구글 계정의 캘린더 일정을 여기에 보여 주고, 오늘·내일 일정은 안경에도 보냅니다. 읽기만 하며 일정 추가와 수정은 구글 캘린더 앱에서 합니다.")
            ToolButton(onClick = actions.calendarPermission) { Text("캘린더 접근 허용") }
            return@ContentCard
        }
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val byDay = content.calendarEvents.groupBy { maxOf(Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate(), today) }.toSortedMap()
        if (byDay.isEmpty()) Note("오늘부터 7일 동안 일정이 없습니다")
        val soon = byDay.filterKeys { it <= today.plusDays(1) }; val rest = byDay.filterKeys { it > today.plusDays(1) }
        @Composable
        fun Day(date: LocalDate, events: List<PhoneCalendar.Event>) {
            Text(when (date) { today -> "오늘"; today.plusDays(1) -> "내일"; else -> date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)) },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            events.forEach { event -> EventRow(event) {
                model.attempt {
                    val id = event.key.substringBefore(':').toLong()
                    // All-day times are kept as local midnights here; the calendar app expects UTC midnights for them.
                    fun utc(local: Long) = if (!event.allDay) local else
                        Instant.ofEpochMilli(local).atZone(zone).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, utc(event.begin)).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, utc(event.end)))
                }
            } }
        }
        soon.forEach { (date, events) -> Day(date, events) }
        if (rest.isNotEmpty()) {
            TextButton(onClick = { later = !later }) { Text(if (later) "이후 일정 숨기기" else "이후 일정 ${rest.values.sumOf { it.size }}개 보기") }
            if (later) rest.forEach { (date, events) -> Day(date, events) }
        }
        Note(when {
            model.state.glasses.link.phase != LinkPhase.FULL -> "안경이 연결되면 오늘·내일 일정을 안경에 보냅니다"
            !content.calendarToGlasses -> "안경으로 자동 전송이 꺼져 있습니다"
            else -> content.calendarNotice.ifBlank { "오늘·내일 일정을 안경에 보내는 중입니다" }
        })
    }
}

@Composable
private fun EventRow(event: PhoneCalendar.Event, open: () -> Unit) {
    val zone = ZoneId.systemDefault(); val time = DateTimeFormatter.ofPattern("HH:mm")
    val start = Instant.ofEpochMilli(event.begin).atZone(zone); val end = Instant.ofEpochMilli(event.end).atZone(zone)
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height(36.dp).background(Color(event.color or 0xFF000000.toInt()), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Note(listOf(if (event.allDay) "종일" else "${start.format(time)}–${end.format(time)}", event.location).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}
