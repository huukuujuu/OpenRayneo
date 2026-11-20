// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionContentScreen(model: AppModel) {
    val state = model.state.content
    val connected = model.state.glasses.link.phase == LinkPhase.FULL
    val editable = connected && !state.busy
    var page by rememberSaveable { mutableIntStateOf(0) }
    var cityQuery by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("레이아웃", "일정", "날씨").forEachIndexed { i, name -> FilterChip(page == i, { page = i }, label = { Text(name) }) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Note("할 일은 앱 홈에서 관리합니다. 안경이 연결되면 안경 할 일 목록과 자동으로 맞춥니다.")
        when (page) {
            0 -> {
                ToolOutlinedButton(onClick = { model.attempt { model.service().content.refresh() } }, enabled = editable) { Text("안경 홈 화면 읽기") }
                Note(if (connected) state.notice else "먼저 안경을 연결하세요")
                DashboardLayoutEditor(model)
            }
            1 -> CalendarSettings(model, editable)
            2 -> {
                ToolButton(onClick = { model.attempt { val content = model.service().content
                    content.run { content.weather(content.currentCity()); content.pushWeather() }
                } }, enabled = editable) { Text("현재 위치 날씨를 안경에 표시") }
                Note("휴대폰 위치로 날씨를 가져와 안경에 보냅니다. 자동 업데이트를 켜면 매번 현재 위치를 다시 찾습니다. 다른 도시는 아래에서 검색하세요.")
                OutlinedTextField(cityQuery, { cityQuery = it }, Modifier.fillMaxWidth(), label = { Text("도시 검색") })
                ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.searchCities(cityQuery) } } }, enabled = !state.busy && cityQuery.isNotBlank()) { Text("도시 찾기") }
                state.weatherCities.forEach { city -> TextButton(onClick = { model.attempt { model.service().content.run { model.service().content.weather(city) } } }, enabled = editable) {
                    Text(listOf(city.optString("name"), city.optString("admin1"), city.optString("country")).filter { it.isNotBlank() }.joinToString(" · "))
                } }
                state.weather?.let { card ->
                    Text("${card.getString("location")} · ${card.getInt("temp")}° · ${card.getString("des")}", style = MaterialTheme.typography.titleMedium)
                    ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.pushWeather() } } }, enabled = editable) { Text("홈 온도와 날씨 카드 동기화") }
                    Row {
                        Checkbox(state.automaticWeather, { model.attempt { model.service().content.setAutomaticWeather(it) } }, enabled = editable)
                        Text("연결 시 및 30분마다 날씨 업데이트")
                    }
                    ToolOutlinedButton(onClick = { model.attempt { model.service().content.automaticWeather(force = true) } }, enabled = editable && state.automaticWeather) { Text("지금 자동 업데이트") }
                    Note("홈 온도는 여기서 고른 도시나 현재 위치를 사용합니다. 날씨는 Open-Meteo에서 제공하며 연결이 끊기면 업데이트가 중지됩니다.")
                }
            }
        }
    }
}

@Composable
private fun DashboardLayoutEditor(model: AppModel) {
    val state = model.state.content
    val config = state.dashboard
    if (config == null) { Note("안경 콘텐츠를 읽은 뒤 홈 스타일과 위젯 순서를 설정할 수 있습니다."); return }
    var mode by remember(config.toString()) { mutableIntStateOf(config.optInt("simple_mode")) }
    var widgets by remember(config.toString()) { mutableStateOf(config.optJSONArray("widgets").ints()) }
    val names = mapOf(1 to "날씨", 2 to "할 일", 3 to "일정", 4 to "주식", 5 to "메일", 6 to "세계 시계")
    val enabled = !state.busy && model.state.glasses.link.phase == LinkPhase.FULL
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("표준", "간결", "대각 분할").forEachIndexed { index, title -> FilterChip(mode == index, { mode = index }, label = { Text(title) }) }
    }
    widgets.forEachIndexed { index, id ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(names[id] ?: "기기 위젯$id", Modifier.weight(1f))
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Text("위로") }
            TextButton(onClick = { widgets = widgets.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < widgets.lastIndex) { Text("아래로") }
            TextButton(onClick = { widgets = widgets.filter { it != id } }, enabled = widgets.size > 1) { Text("제거") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1,2,3,6).filter { it !in widgets }.forEach { id ->
            TextButton(onClick = { widgets = widgets + id }, enabled = widgets.size < 5 && (id != 1 || state.weather != null)) { Text("추가: ${names[id]}") }
        }
    }
    Note("날씨는 먼저 날씨 탭에서 도시를 설정하세요. 세계 시계는 휴대폰의 현재 시간대를 사용합니다. 기존 주식·메일 위젯은 유지되며 데이터 소스는 원래 설정을 따릅니다.")
    ToolButton(onClick = { model.attempt { model.service().content.run { model.service().content.layout(mode, widgets) } } }, enabled = enabled) { Text("홈 레이아웃 적용") }
}

/** Which phone calendars feed the app and the glasses, plus what the glasses hold now. */
@Composable
private fun CalendarSettings(model: AppModel, editable: Boolean) {
    val state = model.state.content
    val formatter = remember { DateTimeFormatter.ofPattern("M/d HH:mm") }
    if (!state.calendarAllowed) { Note("앱 홈의 일정 카드에서 캘린더 접근을 먼저 허용하세요."); return }
    Note("휴대폰에 로그인된 구글 계정의 캘린더를 읽습니다(읽기 전용). 고른 캘린더의 일정이 앱 홈에 보이고, 오늘·내일 일정은 안경 일정에 들어갑니다.")
    SectionLabel("보여 줄 캘린더")
    if (state.calendars.isEmpty()) Note("휴대폰에 표시되는 캘린더가 없습니다. 구글 캘린더 앱에서 계정 동기화를 확인하세요.")
    state.calendars.forEach { source ->
        val checked = state.calendarSelection?.contains(source.id) ?: true
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked, { on ->
                val all = state.calendars.map { it.id }.toSet()
                val next = (state.calendarSelection ?: all).let { if (on) it + source.id else it - source.id }
                model.attempt { model.service().content.selectCalendars(next.takeIf { it != all }) }
            })
            Column { Text(source.name); Note(source.account) }
        }
    }
    SectionLabel("안경으로 보내기")
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(state.calendarToGlasses, { model.attempt { model.service().content.setCalendarToGlasses(it) } })
        Spacer(Modifier.width(12.dp)); Text("오늘·내일 일정을 안경에 자동으로 보내기")
    }
    ToolOutlinedButton(onClick = { model.attempt { model.service().content.calendarTick(force = true) } }, enabled = editable) { Text("지금 안경에 보내기") }
    if (state.calendarNotice.isNotBlank()) Note(state.calendarNotice)
    if (state.schedules.isNotEmpty()) {
        SectionLabel("지금 안경에 있는 일정")
        state.schedules.sortedBy { it.optLong("startTime") }.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.optString("title"))
                    Note(java.time.Instant.ofEpochSecond(row.optLong("startTime")).atZone(ZoneId.systemDefault()).format(formatter))
                }
                TextButton(onClick = { model.attempt { model.service().content.run {
                    model.service().content.schedule("delete", JSONObject().put("event_id", row.getLong("eventID")))
                } } }, enabled = editable) { Text("삭제") }
            }
        }
        Note("구글 캘린더에서 온 일정을 여기서 지워도 다음 자동 전송 때 다시 들어갑니다.")
    }
}
