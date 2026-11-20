// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("위젯", "일정", "날씨", "주식", "세계 시계", "메일").forEachIndexed { i, name -> FilterChip(page == i, { page = i }, label = { Text(name) }) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Note("할 일은 앱 홈에서 관리합니다. 안경이 연결되면 안경 할 일 목록과 자동으로 맞춥니다.")
        when (page) {
            0 -> {
                ToolOutlinedButton(onClick = { model.attempt { model.service().content.refresh() } }, enabled = editable) { Text("안경 홈 화면 읽기") }
                Note(if (connected) state.notice else "먼저 안경을 연결하세요")
                DashboardLayoutEditor(model) { page = it }
            }
            3 -> StockSettings(model)
            4 -> ClockSettings(model)
            5 -> MailSettings(model)
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

/** Like the official dashboard settings: added widgets (n/5) in order, then the ones not added; every change applies at once. */
@Composable
private fun DashboardLayoutEditor(model: AppModel, open: (Int) -> Unit) {
    val state = model.state.content
    val config = state.dashboard
    if (config == null) { Note("안경을 연결하고 '안경 홈 화면 읽기'를 누르면 위젯을 고칠 수 있습니다."); return }
    val mode = config.optInt("simple_mode")
    val widgets = config.optJSONArray("widgets").ints()
    val enabled = !state.busy && model.state.glasses.link.phase == LinkPhase.FULL
    fun apply(nextMode: Int, next: List<Int>) = model.attempt { model.service().content.run { model.service().content.layout(nextMode, next) } }
    val w = model.state.widgets
    fun source(id: Int) = when (id) {
        1 -> state.weather?.optString("location")?.ifBlank { null } ?: "도시 미선택"
        2 -> "휴대폰 할 일"
        3 -> if (state.calendarAllowed) "휴대폰 캘린더" else "캘린더 권한 필요"
        4 -> "${w.stocks.size}개 종목"
        5 -> "Gmail 알림"
        6 -> w.clock?.optString("location") ?: "현지 시간"
        else -> ""
    }
    // Where each widget is set up: tab index in this screen, or null when there is nothing to set.
    val page = mapOf(1 to 2, 3 to 1, 4 to 3, 5 to 5, 6 to 4)
    SectionLabel("홈 스타일")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("표준", "간결", "대각 분할").forEachIndexed { index, title ->
            FilterChip(mode == index, { if (mode != index) apply(index, widgets) }, label = { Text(title) }, enabled = enabled)
        }
    }
    SectionLabel("위젯 추가됨 (${widgets.size}/5)")
    widgets.forEachIndexed { index, id ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(WIDGET_NAMES[id] ?: "기기 위젯 $id")
                Note(source(id))
            }
            page[id]?.let { target -> TextButton(onClick = { open(target) }) { Text("설정") } }
            IconlessButton("↑", enabled && index > 0) { apply(mode, widgets.toMutableList().apply { add(index - 1, removeAt(index)) }) }
            IconlessButton("↓", enabled && index < widgets.lastIndex) { apply(mode, widgets.toMutableList().apply { add(index + 1, removeAt(index)) }) }
            TextButton(onClick = { apply(mode, widgets.filter { it != id }) }, enabled = enabled && widgets.size > 1) { Text("제거") }
        }
    }
    val missing = WIDGET_NAMES.keys.filter { it !in widgets }
    if (missing.isNotEmpty()) {
        SectionLabel("추가되지 않음")
        missing.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(WIDGET_NAMES.getValue(id)); Note(source(id)) }
                TextButton(onClick = { apply(mode, widgets + id) }, enabled = enabled && widgets.size < 5) { Text("추가") }
            }
        }
        if (widgets.size >= 5) Note("위젯은 5개까지 넣을 수 있습니다. 하나를 제거하면 추가할 수 있습니다.")
    }
}

/** Mail widget: no Google sign-in. The newest Gmail notification on the phone, read with notification access. */
@Composable
private fun MailSettings(model: AppModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val access = model.state.notificationConnected
    SectionLabel("Gmail 연결 방식")
    Note("공식 앱처럼 Google 계정에 로그인하지 않습니다. 휴대폰 Gmail 앱이 띄운 알림을 읽어 최신 메일 1개와 알림 수를 안경 메일 위젯에 보냅니다.")
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(if (access) "알림 접근 권한: 허용됨" else "알림 접근 권한: 필요", Modifier.weight(1f))
        TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(if (access) "설정 열기" else "허용하기") }
    }
    Note("휴대폰 설정 > 알림 > 기기 및 앱 알림(알림 읽기)에서 OpenRayneo Android를 켭니다. Gmail 앱의 알림도 켜져 있어야 합니다.")
    ToolButton(onClick = { model.attempt { model.service().widgets.background("메일 보내기") { model.service().widgets.pushMail() } } },
        enabled = model.state.glasses.link.phase == LinkPhase.FULL) { Text("메일 위젯 지금 보내기") }
    model.state.widgets.mail.takeIf { it.isNotBlank() }?.let { Note(it) }
}

private val WIDGET_NAMES = linkedMapOf(1 to "날씨", 3 to "일정", 4 to "주식", 5 to "메일", 6 to "세계 시계", 2 to "할 일")

@Composable
private fun IconlessButton(label: String, enabled: Boolean, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) }

/** Stocks widget: up to 6 symbols, prices from Yahoo Finance (delayed). */
@Composable
private fun StockSettings(model: AppModel) {
    val w = model.state.widgets
    var query by rememberSaveable { mutableStateOf("") }
    SectionLabel("주식 추가됨 (${w.stocks.size}/6)")
    if (w.stocks.isEmpty()) Note("아직 종목이 없습니다. 아래에서 검색해 추가하세요.")
    w.stocks.forEach { stock ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stock.optString("symbol")); Note("${stock.optString("exchange")} · ${stock.optString("name")}") }
            TextButton(onClick = { model.attempt { model.service().widgets.removeStock(stock.getString("symbol")) } }) { Text("제거") }
        }
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("주식 검색 (예: AAPL, 삼성전자, 005930)") }, singleLine = true)
    ToolButton(onClick = { model.attempt { model.service().widgets.background("주식 검색") { model.service().widgets.searchStocks(query) } } },
        enabled = query.isNotBlank()) { Text("종목 찾기") }
    w.stockResults.forEach { row ->
        TextButton(onClick = { model.attempt { model.service().widgets.addStock(row) } }, enabled = w.stocks.size < 6) {
            Text("${row.optString("symbol")} · ${row.optString("name")} · ${row.optString("exchange")}")
        }
    }
    if (w.notice.isNotBlank()) Note(w.notice)
    Note("주식 데이터는 Yahoo Finance에서 가져오며 지연될 수 있고 참고용입니다. 연결 중 10분마다, 안경이 요청할 때 새로 보냅니다.")
}

/** World clock widget: one city, like the official app. */
@Composable
private fun ClockSettings(model: AppModel) {
    val w = model.state.widgets
    var query by rememberSaveable { mutableStateOf("") }
    SectionLabel("도시 추가됨 (${if (w.clock == null) 0 else 1}/1)")
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.clock?.optString("location") ?: "현지 시간 (휴대폰 시간대)")
            Note(w.clock?.optString("timezone") ?: ZoneId.systemDefault().id)
        }
        if (w.clock != null) TextButton(onClick = { model.attempt { model.service().widgets.setClock(null) } }) { Text("현지 시간으로") }
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("도시 검색 (예: 뉴욕, London)") }, singleLine = true)
    ToolButton(onClick = { model.attempt { model.service().widgets.background("도시 검색") { model.service().widgets.searchClock(query) } } },
        enabled = query.isNotBlank()) { Text("도시 찾기") }
    w.clockResults.forEach { row ->
        TextButton(onClick = { model.attempt { model.service().widgets.setClock(row) } }) {
            val now = LocalDateTime.now(ZoneId.of(row.getString("timezone"))).format(DateTimeFormatter.ofPattern("HH:mm"))
            Text("${row.optString("location")} · ${row.optString("timezone")} · $now")
        }
    }
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
                    val start = java.time.Instant.ofEpochSecond(row.optLong("startTime")).atZone(ZoneId.systemDefault())
                    Note(if (row.optBoolean("isAllDay")) start.format(DateTimeFormatter.ofPattern("M/d")) + " 종일" else start.format(formatter))
                }
                TextButton(onClick = { model.attempt { model.service().content.run {
                    model.service().content.schedule("delete", JSONObject().put("event_id", row.getLong("eventID")))
                } } }, enabled = editable) { Text("삭제") }
            }
        }
        Note("구글 캘린더에서 온 일정을 여기서 지워도 다음 자동 전송 때 다시 들어갑니다.")
    }
}
