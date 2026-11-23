// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationScreen(model: AppModel, actions: UiActions, open: (String) -> Unit) {
    val state = model.state.conversation
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var liveSpeaker by remember { mutableStateOf<SpeechSegment?>(null) }
    LaunchedEffect(state.activeRecord?.id) { liveSpeaker = null }
    var query by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val openAudio = state.records.any { it.audioPending }
    LaunchedEffect(state.running, openAudio) {
        now = System.currentTimeMillis()
        while (state.running || openAudio) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val date = LocalDate.parse(dateText)
    val today = LocalDate.now()
    // Declared before the detail branch so the list keeps its scroll position when a record is closed.
    val listState = rememberLazyListState()
    val record = state.records.firstOrNull { it.id == selected }
    if (record != null) {
        // Registered after the app-level handler, so system back closes the detail first and keeps the list filters.
        BackHandler { selected = null }
        ConversationDetail(model, record, actions, open) { selected = null }
        return
    }
    liveSpeaker?.let { segment -> state.activeRecord?.let { active ->
        SpeakerBindingDialog(model, active, segment, actions) { liveSpeaker = null }
    } }
    var source by rememberSaveable { mutableStateOf(ConversationSource.REALTIME) }
    LazyColumn(state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ContentCard {
                Text("새 기록", style = MaterialTheme.typography.titleMedium)
                if (!state.running) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversationSource.entries.filter { it != ConversationSource.VOICE_MEMO }.forEach { option -> FilterChip(source == option, { source = option }, label = { Text(option.title) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton(onClick = { model.attempt { model.service().startConversation(ConversationMode.DAILY, source) } },
                            enabled = state.modelReady && model.state.glasses.link.phase == LinkPhase.FULL) { Text("일상 기록 시작") }
                        ToolOutlinedButton(onClick = { model.attempt { model.service().startConversation(ConversationMode.MEETING, source) } },
                            enabled = state.modelReady && model.state.glasses.link.phase == LinkPhase.FULL) { Text("회의 기록 시작") }
                    }
                } else {
                    Text("${state.mode.title} · ${state.source.title} · 오디오 ${state.decodedSeconds.toInt()}초 수신")
                    if (state.voiceGate.isNotEmpty()) Text(state.voiceGate)
                    if (state.source == ConversationSource.ALWAYS_ON) state.activeRecord?.let { active ->
                        val rows = active.segments
                        Note("문장 ${rows.size}개 · 채널2 ${rows.count { it.sourceChannel == 2 }}개 · 골전도로 본인 ${rows.count { it.identityBasis == "vpu" }}개")
                    }
                    if (state.agentDueAt > 0) Note("다음 AI 판단: 약 ${((state.agentDueAt - now + 59_999) / 60_000).coerceAtLeast(1)}분 뒤 (그동안 들은 말을 한 번에 판단)")
                    ToolButton(onClick = { model.service().conversations.stop() }, enabled = !state.stopping) { Text(if (state.stopping) "마무리 중…" else "기록 및 음성 수신 중지") }
                }
                Note(state.status)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.modelReady) Note("로컬 음성 모델을 준비 중입니다. 로드에 실패하면 위의 오류를 확인하세요.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open("voice-settings") }) { Text("음성 및 요약") }
                    TextButton(onClick = { open("voice-profiles") }) { Text("목소리 및 인물") }
                }
            }
        }
        if (state.lastHint.isNotBlank()) item {
            ContentCard { Text("최근 짧은 힌트", style = MaterialTheme.typography.titleMedium); Text(state.lastHint) }
        }
        if (state.running && state.segments.isNotEmpty()) {
            item { SectionLabel("현재 대화") }
            items(state.segments.takeLast(12), key = { it.id }) { segment -> SpeechRow(segment, state.people, state.activeRecord) { liveSpeaker = segment } }
        }
        stickyHeader(key = "calendar") {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!monthly, { monthly = false }, label = { Text("일별") })
                        FilterChip(monthly, { monthly = true }, label = { Text("월별") })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { dateText = today.toString() }) { Text("오늘") }
                    }
                    val atEnd = if (monthly) !date.withDayOfMonth(1).isBefore(today.withDayOfMonth(1)) else !date.isBefore(today)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { dateText = (if (monthly) date.minusMonths(1) else date.minusDays(1)).toString() }) { Text("‹ " + if (monthly) "이전 달" else "이전 날") }
                        Text(if (monthly) "${date.year}년 ${date.monthValue}월" else "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 (${weekday(date)})",
                            style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { dateText = (if (monthly) date.plusMonths(1) else date.plusDays(1)).toString() }, enabled = !atEnd) { Text((if (monthly) "다음 달" else "다음 날") + " ›") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("이 기간에서 찾기 (내용, 사람)") }, singleLine = true)
        }
        val begin = (if (monthly) date.withDayOfMonth(1) else date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = (if (monthly) date.withDayOfMonth(1).plusMonths(1) else date.plusDays(1)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A wake with nothing heard leaves an empty record: not worth a row (it is kept, just not listed).
        val matchingRecords = state.records.filter { row ->
            !(row.source == ConversationSource.NATIVE && row.segments.isEmpty() && row.derived.isEmpty() && !row.processingPending && !row.audioPending &&
                row.id != state.activeRecord?.id)
        }.filter { row ->
                query.isBlank() || row.title.contains(query, true) || row.summary.contains(query, true) ||
                    row.segments.any { it.text.contains(query, true) || state.people.firstOrNull { p -> p.id == it.personId }?.name?.contains(query, true) == true } ||
                    row.memories.any { it.subject.contains(query, true) || it.text.contains(query, true) } ||
                    row.summaries.any { it.text.contains(query, true) }
        }
        val records = matchingRecords.filter { row ->
            row.createdAt < end && calendarRecordEnd(row, now) > begin
        }.sortedBy { it.createdAt }
        val recordsByDay = records.flatMap { row -> calendarRecordDays(row, begin, end, now).map { it to row } }
            .groupBy({ it.first }, { it.second })
        val summariesByDay = matchingRecords.flatMap { it.summaries }.filter { it.kind == "day_end" && it.day.isNotBlank() }
            .sortedBy { it.stale }.distinctBy { it.day to it.text }
            .map { LocalDate.parse(it.day) to it }
            .filter { (day, _) -> day >= localDate(begin) && day < localDate(end) }
            .groupBy({ it.first }, { it.second })
        // Newest day first; within a day records stay in time order.
        val days = (recordsByDay.keys + summariesByDay.keys).sortedDescending()
        if (monthly) item(key = "month-grid") {
            MonthGrid(date, today, recordsByDay.mapValues { it.value.size }) { day -> dateText = day.toString(); monthly = false }
        }
        if (records.isNotEmpty()) item {
            val period = if (monthly) "${date.year}년 ${date.monthValue}월" else "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일"
            ToolOutlinedButton(onClick = { actions.exportRecords(records.map { it.id }, "$period 기록") }, modifier = Modifier.fillMaxWidth()) {
                Text("이 기간 기록 ${records.size}개 내보내기 (텍스트)")
            }
        }
        if (days.isEmpty()) item { Note(if (query.isBlank()) "이 기간에는 기록이 없습니다." else "이 기간에 '$query'와 맞는 기록이 없습니다.") }
        days.forEach { day ->
            val dayRecords = recordsByDay[day].orEmpty()
            val dayBegin = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            item(key = "day:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일 (${weekday(day)}) · 기록 ${dayRecords.size}개") }
            summariesByDay[day].orEmpty().forEach { summary -> item {
                ContentCard { Text("하루 요약", style = MaterialTheme.typography.titleMedium); Text(summary.text); if (summary.stale) Note("내용이 바뀌어 다시 요약할 예정") }
            } }
            dayRecords.groupBy { timePeriod(it.createdAt.coerceAtLeast(dayBegin)) }.forEach { (period, periodRecords) ->
                item(key = "period:$day:$period") { Note(period) }
                items(periodRecords, key = { "$day:${it.id}" }) { row ->
                    val daySegments = row.segments.filter { segment ->
                        row.timeOf(segment) < dayEnd && row.endTimeOf(segment) > dayBegin
                    }
                    OutlinedCard(Modifier.fillMaxWidth().clickable { selected = row.id }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Note("${row.source.title} · ${recordTime(row)} · 문장 ${daySegments.size}개")
                            if (row.processingPending) Note("녹음은 저장됨 · 받아 적기 대기 중")
                            if (row.source == ConversationSource.ALWAYS_ON && row.sessionId.isNotBlank() && row.sessionId != row.id) Note("깨우기 뒤 같은 세션으로 이어짐")
                            if (row.summary.isNotBlank()) Text(row.summary.take(160))
                            // The latest sentences, not the day's first ones.
                            if (row.source == ConversationSource.ALWAYS_ON) daySegments.takeLast(3).forEach { segment ->
                                Note("${SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(row.timeOf(segment)))} · ${segment.text}")
                            }
                            if (row.summaryStale && row.summary.isNotBlank() && row.id != state.activeRecord?.id) Note("요약 뒤 바뀐 내용이 있음")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechRow(segment: SpeechSegment, people: List<VoicePerson>, record: ConversationRecord? = null, action: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(onClick = action) else Modifier)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = people.firstOrNull { it.id == segment.personId }?.name ?: if (segment.speaker == UNKNOWN_SPEAKER) "누군지 모름" else "화자 ${speakerTag(segment.speaker)}"
            val start = record?.timeOf(segment) ?: segment.timelineAt.takeIf { it > 0 }
            val end = record?.endTimeOf(segment) ?: segment.timelineEndAt.takeIf { it > 0 }
            val clock = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
            val time = start?.let { clock.format(Date(it)) + (end?.let { until -> "–${clock.format(Date(until))}" } ?: "") } ?: formatOffset(segment.beginMS)
            Note("$time · $name${if (segment.mixed) " · 여러 목소리" else ""}${if (segment.processingMode == "backfill") " · 나중에 받아 적음" else ""}")
            Text(segment.text)
        }
    }
}

@Composable
private fun ConversationDetail(model: AppModel, record: ConversationRecord, actions: UiActions, open: (String) -> Unit, back: () -> Unit) {
    val state = model.state.conversation
    var speakerEdit by remember { mutableStateOf<SpeechSegment?>(null) }
    var memoryEdit by remember { mutableStateOf<AttributedMemory?>(null) }
    var filter by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.title, style = MaterialTheme.typography.headlineSmall)
                Note("${record.source.title} · ${recordTime(record)}" + if (record.audioSamples > 0) " · 녹음 ${formatOffset(record.audioSamples / 16)}" else "")
                if (record.processingPending) Note("녹음은 저장됐고, 받아 적기는 곧 이어서 합니다.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(onClick = { model.service().conversations.summarize(record.id) }, enabled = !state.summarizing && record.segments.isNotEmpty()) {
                        Text(if (state.summarizing) "요약하는 중…" else if (record.summary.isBlank()) "요약하기" else "다시 요약")
                    }
                    ToolOutlinedButton(onClick = { actions.exportRecords(listOf(record.id), record.title + " " +
                        SimpleDateFormat("yyyy-MM-dd HHmm", Locale.KOREA).format(Date(record.createdAt))) }) { Text("텍스트로 내보내기") }
                    if (record.audioSamples > 0) ToolOutlinedButton(onClick = { actions.exportRecording(record.id) }) { Text("녹음 내보내기") }
                }
                if (record.audioSamples > 0) TextButton(onClick = { model.attempt { model.service().conversations.retranscribe(record.id) } }, enabled = !state.running) {
                    Text("녹음에서 빠진 부분 다시 받아 적기")
                }
                // No own voice saved: every speaker is an anonymous label. Offer the quick fix for a solo recording.
                val labels = record.segments.map { it.speaker }.filter { it != UNKNOWN_SPEAKER }.distinct()
                // Only sentences nobody was named for: names set by hand stay.
                val unnamed = record.segments.filter { it.personId == null }
                var confirmSelf by remember(record.id) { mutableStateOf(false) }
                if (confirmSelf) AlertDialog(onDismissRequest = { confirmSelf = false }, title = { Text("모두 '나'로 표시할까요?") },
                    text = { Text("이름이 없는 문장 ${unnamed.size}개를 '나'로 표시합니다. 이미 이름을 붙인 문장은 그대로 둡니다. 나중에 문장을 눌러 다시 고칠 수 있습니다.") },
                    confirmButton = { TextButton(onClick = {
                        confirmSelf = false
                        model.attempt {
                            val conversations = model.service().conversations
                            val self = conversations.repository.ensureSelf()
                            conversations.correctSpeakers(mapOf(record.id to unnamed.map { it.id }.toSet()), self.id)
                        }
                    }) { Text("'나'로 표시") } },
                    dismissButton = { TextButton(onClick = { confirmSelf = false }) { Text("취소") } })
                // Channel labels (and the bone-conduction "me") are microphones, not voices: marking them all as me would be wrong.
                val voiceLabels = record.segments.none { it.identityBasis == "channel" || it.identityBasis == "vpu" }
                if (voiceLabels && state.people.none { it.self && it.samples.isNotEmpty() } && labels.size > 1 && unnamed.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("혼자 말했는데 여러 사람으로 나뉘었나요?", style = MaterialTheme.typography.titleSmall)
                        Note("내 목소리를 등록하면 다음 기록부터 '나'로 표시됩니다.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmSelf = true }) { Text("이 기록 전부 '나'로") }
                            TextButton(onClick = { open("voice-profiles") }) { Text("내 목소리 등록") }
                        }
                    }
                }
                if (record.summary.isNotBlank()) Text(record.summary)
                if (record.summaryStale && record.summary.isNotBlank() && !(state.running && state.activeRecord?.id == record.id))
                    Note("요약한 뒤 문장이나 화자가 바뀌었습니다. '다시 요약'을 누르면 반영됩니다.")
                record.summaries.filter { it.kind != "rolling" }.forEach { summary ->
                    Text(if (summary.kind == "day_end") "${summary.day.ifBlank { "날짜 없음" }} 하루 요약" else "끝난 뒤 요약", style = MaterialTheme.typography.titleSmall)
                    Text(summary.text)
                    if (summary.stale) Note("내용이 바뀌어 다시 요약할 예정")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        // AI answers are shown in the timeline at the time they came; other notes stay here.
        val notes = record.derived.filter { it.kind != "analysis" }
        if (notes.isNotEmpty()) item { SectionLabel("분석과 메모") }
        items(notes, key = { "derived:${it.id}" }) { derived ->
            ContentCard {
                Text(when (derived.kind) { "analysis" -> "분석"; "tags" -> "태그"; else -> "추가 메모" }, style = MaterialTheme.typography.titleSmall)
                if (derived.text.isNotBlank()) Text(derived.text)
                if (derived.tags.isNotEmpty()) Note(derived.tags.joinToString(" · "))
                if (derived.stale) Note("내용이 바뀌어 다시 만들어야 함")
            }
        }
        if (record.speakerSamples.isNotEmpty()) item {
            SectionLabel("목소리별 이름 붙이기")
            record.speakerSamples.forEach { (label, samples) ->
                val segment = record.segments.firstOrNull { it.speaker == label }
                    ?: SpeechSegment("voice:$label", 0, 0, "이 기록에서 모은 목소리", speaker = label, personId = record.speakerPeople[label])
                TextButton(onClick = { speakerEdit = segment }) {
                    Text("화자 ${speakerTag(label)} · ${state.people.firstOrNull { it.id == segment.personId }?.name ?: "이름 없음"} · 문장 ${record.segments.count { it.speaker == label }}개")
                }
            }
        }
        // A session summary on the newest record may quote sentences of the session's earlier records.
        val sessionSegments = if (record.sessionId.isNotBlank()) state.records.filter { it.sessionId == record.sessionId }.flatMap { it.segments } else record.segments
        if (record.memories.isNotEmpty()) item { SectionLabel("기억할 내용") }
        items(record.memories, key = { it.id }) { memory ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { memoryEdit = memory }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(listOf(memory.subject.takeUnless { it.isBlank() || it == "null" }, displayLabel(memory.kind)).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    Text(memory.text)
                    memory.sourceIds.forEach { id -> sessionSegments.firstOrNull { it.id == id }?.let { source -> Note("“${source.text}”") } }
                }
            }
        }
        item {
            SectionLabel("대화 내용")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter == null, { filter = null }, label = { Text("전체") })
                record.segments.map { it.speaker }.distinct().forEach { name ->
                    FilterChip(filter == name, { filter = name }, label = { Text(speakerTag(name)) })
                }
            }
        }
        val answers = if (filter == null) record.derived.filter { it.kind == "analysis" && it.text.isNotBlank() } else emptyList()
        val timeline: List<Pair<Long, Any>> = record.segments.filter { filter == null || it.speaker == filter }.map { record.timeOf(it) to it } +
            answers.map { it.updatedAt to it }
        timeline.sortedBy { it.first }.groupBy { localDate(it.first) }.forEach { (day, entries) ->
            item(key = "timeline:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일") }
            items(entries, key = { (_, entry) -> if (entry is SpeechSegment) entry.id else "answer:${(entry as RecordDerived).id}" }) { (time, entry) ->
                if (entry is SpeechSegment) SpeechRow(entry, state.people, record) { speakerEdit = entry }
                else ContentCard {
                    Note("${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(time))} · AI 답변")
                    Text((entry as RecordDerived).text)
                }
            }
        }
    }
    speakerEdit?.let { segment -> SpeakerBindingDialog(model, record, segment, actions) { speakerEdit = null } }
    memoryEdit?.let { memory ->
        var subject by remember(memory.id) { mutableStateOf(memory.subject) }
        var text by remember(memory.id) { mutableStateOf(memory.text) }
        AlertDialog(onDismissRequest = { memoryEdit = null }, title = { Text("기억 고치기") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("누구에 대한 내용인가요") })
                OutlinedTextField(text, { text = it }, label = { Text("내용") })
                Note("저장한 뒤 '다시 요약'을 누르면 반영됩니다.")
            }
        }, confirmButton = { TextButton(onClick = {
            model.service().conversations.correctMemory(record.id, memory.id, subject.trim(), text.trim()); memoryEdit = null
        }, enabled = subject.isNotBlank() && text.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = { memoryEdit = null }) { Text("취소") } })
    }
}

@Composable
private fun SpeakerBindingDialog(model: AppModel, record: ConversationRecord, segment: SpeechSegment, actions: UiActions, dismiss: () -> Unit) {
    val people = model.state.conversation.people
    var history by remember { mutableStateOf(false) }
    if (history) {
        HistoricalSpeakerDialog(model, segment.personId, record.id, segment.id, dismiss = dismiss)
        return
    }
    var personId by remember { mutableStateOf(segment.personId ?: record.speakerPeople[segment.speaker]) }
    var name by remember { mutableStateOf("") }
    // A channel label (or the bone-conduction "self") is not one voice: naming it would name everyone on that channel.
    val byVoice = segment.speaker != UNKNOWN_SPEAKER && segment.identityBasis != "channel" && segment.identityBasis != "vpu"
    var all by remember { mutableStateOf(byVoice) }
    var rememberVoice by remember { mutableStateOf(record.speakerSamples[segment.speaker]?.isNotEmpty() == true) }
    val picked = model.selectedContact
    LaunchedEffect(picked) { if (picked != null) { name = picked.name; personId = null } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("화자 ${speakerTag(segment.speaker)}") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(segment.text)
            if (segment.matchReason.isNotBlank()) Note("판정 근거: ${segment.matchReason}")
            TextButton(onClick = { history = true }) { Text("지난 기록에서 여러 문장 한꺼번에 고치기") }
            Row { Checkbox(all, { all = it }, enabled = byVoice); Text("이 기록에서 같은 목소리 전부") }
            people.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { personId = person.id }) {
                    RadioButton(personId == person.id, { personId = person.id }); Text(person.name)
                }
            }
            OutlinedTextField(name, { name = it; personId = null }, label = { Text("새 이름") })
            TextButton(onClick = { model.selectedContact = null; actions.contact() }) { Text("연락처에서 선택") }
            Row { Checkbox(rememberVoice, { rememberVoice = it }, enabled = all && record.speakerSamples[segment.speaker]?.isNotEmpty() == true)
                Text("이 목소리를 기억해 다음에 식별") }
            Note("체크하면 이 목소리를 그 사람에게 저장해, 다음 기록에서 내 목소리와 헷갈리지 않게 합니다.")
        }
    }, confirmButton = { TextButton(onClick = {
        val person = people.firstOrNull { it.id == personId } ?: VoicePerson(name = name.trim(), contactUri = picked?.uri)
        if (all) model.service().conversations.bind(record.id, segment.speaker, person, rememberVoice)
        else {
            model.service().conversations.correctSpeaker(record.id, setOf(segment.id), person.id, person)
        }
        model.selectedContact = null; dismiss()
    }, enabled = personId != null || name.isNotBlank()) { Text("저장") } }, dismissButton = {
        TextButton(onClick = { model.selectedContact = null; dismiss() }) { Text("취소") }
    })
}

private fun formatOffset(milliseconds: Long): String = "%02d:%02d".format(milliseconds / 60000, milliseconds / 1000 % 60)

private fun localDate(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
private fun calendarRecordEnd(record: ConversationRecord, now: Long): Long = record.endedAt
    ?: if (record.audioPending) now else record.segments.maxOfOrNull { record.endTimeOf(it) } ?: record.createdAt
private fun calendarRecordDays(record: ConversationRecord, begin: Long, end: Long, now: Long): List<LocalDate> {
    val start = record.createdAt.coerceAtLeast(begin)
    val until = calendarRecordEnd(record, now).coerceAtMost(end)
    if (until <= start) return emptyList()
    val lastDay = localDate(until - 1)
    return generateSequence(localDate(start)) { day -> day.plusDays(1).takeIf { it <= lastDay } }.toList()
}
private fun timePeriod(time: Long): String = when (Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).hour) {
    in 0..5 -> "새벽 00:00–06:00"
    in 6..11 -> "오전 06:00–12:00"
    in 12..17 -> "오후 12:00–18:00"
    else -> "저녁 18:00–24:00"
}
private fun recordTime(record: ConversationRecord): String {
    val format = SimpleDateFormat("M월 d일 HH:mm", Locale.CHINA)
    return format.format(Date(record.createdAt)) + " → " + (record.endedAt?.let { format.format(Date(it)) } ?: "종료 시각 미기록")
}

/** Select exact historical turns before changing identity; original ASR and audio remain intact. */
@Composable
internal fun HistoricalSpeakerDialog(
    model: AppModel, personId: String?, initialRecordId: String? = null, initialSegmentId: String? = null,
    split: Boolean = false, dismiss: () -> Unit,
) {
    val state = model.state.conversation
    val rows = state.records.map { record -> record to record.segments.filter { segment ->
        if (personId != null) segment.personId == personId else initialRecordId == null || record.id == initialRecordId
    } }.filter { it.second.isNotEmpty() }.sortedByDescending { it.first.createdAt }
    val selection = remember(personId, initialRecordId, initialSegmentId, split) {
        mutableStateMapOf<String, Set<String>>().apply {
            if (initialRecordId != null && initialSegmentId != null) put(initialRecordId, setOf(initialSegmentId))
        }
    }
    var expanded by remember { mutableStateOf(setOfNotNull(initialRecordId)) }
    var targetId by remember { mutableStateOf(personId) }
    var name by remember { mutableStateOf("") }
    val selected = selection.filterValues { it.isNotEmpty() }
    val count = selected.values.sumOf { it.size }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (split) "인물의 과거 발화 분리" else "과거 인물 귀속 일괄 수정") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("먼저 기록을 선택하거나 펼쳐서 발화를 고르세요. 이번에 수정할 항목: 기록 ${selected.size}개, ${count}개 원문. 관련 요약은 다시 만들어야 한다고 표시되고, 원문과 녹음은 그대로 남습니다.")
            if (split) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("분리할 새 인물 이름") })
            else {
                Text("다음으로 수정", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().clickable { targetId = null }) {
                    RadioButton(targetId == null, { targetId = null }); Text("알 수 없는 화자")
                }
                state.people.filter { it.mergedInto == null }.forEach { person ->
                    Row(Modifier.fillMaxWidth().clickable { targetId = person.id }) {
                        RadioButton(targetId == person.id, { targetId = person.id }); Text(person.name)
                    }
                }
            }
            HorizontalDivider()
            if (rows.isEmpty()) Note("수정할 수 있는 연결 발화가 없습니다.")
            rows.forEach { (record, turns) ->
                val ids = turns.map { it.id }.toSet()
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(ids.isNotEmpty() && selection[record.id].orEmpty().containsAll(ids), { checked ->
                        selection[record.id] = if (checked) ids else emptySet()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(record.title, style = MaterialTheme.typography.titleSmall)
                        Note("${recordTime(record)} · ${selection[record.id].orEmpty().size}/${turns.size}개")
                        TextButton(onClick = { expanded = if (record.id in expanded) expanded - record.id else expanded + record.id }) {
                            Text(if (record.id in expanded) "발화 접기" else "발화 고르기")
                        }
                    }
                }
                if (record.id in expanded) turns.forEach { turn ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(turn.id in selection[record.id].orEmpty(), { checked ->
                            val old = selection[record.id].orEmpty()
                            selection[record.id] = if (checked) old + turn.id else old - turn.id
                        })
                        Column(Modifier.weight(1f).padding(top = 10.dp)) { Note(formatOffset(turn.beginMS)); Text(turn.text) }
                    }
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = {
        model.attempt {
            if (split) model.service().conversations.splitPerson(requireNotNull(personId), name.trim(), selected)
            else model.service().conversations.correctSpeakers(selected, targetId)
        }
        dismiss()
    }, enabled = count > 0 && (!split || name.isNotBlank())) { Text("${count}개 ${if (split) "분리" else "수정"} 확인") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}

private fun weekday(day: LocalDate) = "월화수목금토일"[day.dayOfWeek.value - 1].toString()

/** Month at a glance: days with records are marked with their count; tapping a day opens that day. */
@Composable
private fun MonthGrid(month: LocalDate, today: LocalDate, counts: Map<LocalDate, Int>, pick: (LocalDate) -> Unit) {
    val first = month.withDayOfMonth(1)
    val lead = first.dayOfWeek.value % 7   // Sunday first
    val cells = List(lead) { null } + (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            "일월화수목금토".forEach { Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { index ->
                    val day = week.getOrNull(index)
                    val count = day?.let { counts[it] } ?: 0
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).then(if (day != null && count > 0) Modifier.clickable { pick(day) } else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (day != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                color = when { day == today -> MaterialTheme.colorScheme.primary; count > 0 -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) })
                            Text(if (count > 0) "●$count" else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
