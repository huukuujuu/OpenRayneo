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
    val record = state.records.firstOrNull { it.id == selected }
    if (record != null) {
        // Registered after the app-level handler, so system back closes the detail first and keeps the list filters.
        BackHandler { selected = null }
        ConversationDetail(model, record, actions) { selected = null }
        return
    }
    liveSpeaker?.let { segment -> state.activeRecord?.let { active ->
        SpeakerBindingDialog(model, active, segment, actions) { liveSpeaker = null }
    } }
    var source by rememberSaveable { mutableStateOf(ConversationSource.REALTIME) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ContentCard {
                Text("기록 및 기억", style = MaterialTheme.typography.headlineSmall)
                Note("종일 기록은 타임라인과 요약으로 표시되며, 개별 기록은 각자의 시작·종료 시간과 내용을 보존합니다. 본인, 타인, 미상／겹친 발화를 모두 저장하며, 원문을 탭하면 인물 귀속을 수정할 수 있습니다.")
                if (!state.running) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversationSource.entries.forEach { option -> FilterChip(source == option, { source = option }, label = { Text(option.title) }) }
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
        item {
            SectionLabel("기록 일정")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!monthly, { monthly = false }, label = { Text("일별") })
                FilterChip(monthly, { monthly = true }, label = { Text("월별") })
                TextButton(onClick = { dateText = LocalDate.now().toString() }) { Text("오늘") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { dateText = (if (monthly) date.minusMonths(1) else date.minusDays(1)).toString() }) { Text(if (monthly) "이전 달" else "이전 날") }
                Text(if (monthly) "${date.year}년 ${date.monthValue}월" else "${date.monthValue}월 ${date.dayOfMonth}일")
                TextButton(onClick = { dateText = (if (monthly) date.plusMonths(1) else date.plusDays(1)).toString() }) { Text(if (monthly) "다음 달" else "다음 날") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("이 기간의 원문, 인물, 기억 검색") }, singleLine = true)
        }
        val begin = (if (monthly) date.withDayOfMonth(1) else date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = (if (monthly) date.withDayOfMonth(1).plusMonths(1) else date.plusDays(1)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val matchingRecords = state.records.filter { row ->
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
        val days = (recordsByDay.keys + summariesByDay.keys).sorted()
        if (days.isEmpty()) item { Note("이 기간에는 일치하는 기록이 없습니다. 날짜를 바꾸거나 새 기록을 시작하세요.") }
        days.forEach { day ->
            val dayRecords = recordsByDay[day].orEmpty()
            val dayBegin = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            item(key = "day:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일 · ${dayRecords.size}개 기록") }
            summariesByDay[day].orEmpty().forEach { summary -> item {
                ContentCard { Text("일일 요약", style = MaterialTheme.typography.titleMedium); Text(summary.text); if (summary.stale) Note("연결된 내용이 수정되어 새로 고침 대기") }
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
                            Note("${row.source.title} · ${recordTime(row)} · 당일 ${daySegments.size}개 원문")
                            if (row.processingPending) Note("오디오 저장됨, 전사와 화자 분석 보충 대기")
                            if (row.source == ConversationSource.ALWAYS_ON) daySegments.take(3).forEach { segment ->
                                Note("${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(row.timeOf(segment)))} · ${segment.text}")
                            } else if (row.summary.isNotBlank()) Text(row.summary.take(160))
                            if (row.summaryStale) Note("귀속이 수정되어 요약 재정리 대기")
                            if (row.derived.any { it.stale }) Note("연결된 Agent 결과를 새로 고쳐야 함")
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
            val name = people.firstOrNull { it.id == segment.personId }?.name ?: if (segment.speaker == UNKNOWN_SPEAKER) "알 수 없는 화자" else "화자 ${displayLabel(segment.speaker)}"
            val start = record?.timeOf(segment) ?: segment.timelineAt.takeIf { it > 0 }
            val end = record?.endTimeOf(segment) ?: segment.timelineEndAt.takeIf { it > 0 }
            val clock = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
            val time = start?.let { clock.format(Date(it)) + (end?.let { until -> "–${clock.format(Date(until))}" } ?: "") } ?: formatOffset(segment.beginMS)
            Note((if (segment.timeBasis == "legacy-estimate") "과거 시각 추정" else "휴대폰 수신 시각") + if (segment.processingMode == "backfill") " · 과거 오디오 보충" else "")
            Note("채널 ${segment.sourceChannel} · " + (when (segment.identityBasis) { "channel1_default_self" -> "채널 1 기본 본인"; "voiceprint" -> "음성 지문 일치"; "manual" -> "수동 수정"; "mixed" -> "혼합 식별"; else -> "신원 식별 대기" }))
            Note("$time · $name${if (segment.mixed) " · 겹침 또는 혼합" else if (!segment.stable) " · 잠정" else ""}")
            if (segment.candidates.isNotEmpty()) {
                Note("음성 지문 후보 · " + segment.candidates.joinToString(" · ") { match ->
                    "${people.firstOrNull { it.id == match.personId }?.name ?: "삭제된 인물"} ${"%.2f".format(match.score)}"
                })
            } else segment.matchScore?.let { Note("목소리 유사도 ${"%.2f".format(it)}") }
            if (segment.matchReason.isNotBlank()) Note(displayLabel(segment.matchReason))
            Text(segment.text)
        }
    }
}

@Composable
private fun ConversationDetail(model: AppModel, record: ConversationRecord, actions: UiActions, back: () -> Unit) {
    val state = model.state.conversation
    var speakerEdit by remember { mutableStateOf<SpeechSegment?>(null) }
    var memoryEdit by remember { mutableStateOf<AttributedMemory?>(null) }
    var filter by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back) { Text("기록으로 돌아가기") }
            Text(record.title, style = MaterialTheme.typography.headlineSmall)
            Note("${record.source.title} · ${recordTime(record)}")
            Note(if (record.source == ConversationSource.ALWAYS_ON) "종일 기록 타임라인과 요약. 원문을 탭하면 인물을 수정할 수 있습니다." else "${record.mode.title} · 원문을 탭하면 화자를, 기억을 탭하면 서술 대상을 수정할 수 있습니다.")
            if (record.processingPending) Note("원본 오디오가 저장되었습니다. 전사와 화자 분석은 보충 대기 중이며 완료되면 함께 표시됩니다.")
            ToolButton(onClick = { model.service().conversations.summarize(record.id) }, enabled = !state.summarizing && record.segments.isNotEmpty()) {
                Text(if (state.summarizing) "정리 중…" else "요약 및 기억 정리")
            }
            if (record.audioSamples > 0) {
                Note("${record.audioChannels}채널 원본 오디오 ${record.audioSamples / 16000}초 저장됨. 휴대폰에 암호화 저장되며 컴퓨터는 필요할 때 가져옵니다.")
                Row {
                    TextButton(onClick = { actions.exportRecording(record.id) }) { Text("내보내기: WAV") }
                    TextButton(onClick = { model.attempt { model.service().conversations.retranscribe(record.id) } }, enabled = !state.running) { Text("원본 오디오에서 누락 구간 보충") }
                }
            }
            if (record.summary.isNotBlank()) Text(record.summary)
            if (record.summaryStale) Note("내용이 업데이트되었습니다. 요약을 다시 정리하세요.")
            record.summaries.filter { it.kind != "rolling" }.forEach { summary ->
                Text(if (summary.kind == "day_end") "${summary.day.ifBlank { "날짜 미기록" }} 일일 요약" else "기록 종료 요약", style = MaterialTheme.typography.titleSmall)
                Text(summary.text)
                if (summary.stale) Note("출처가 수정되어 새로 고침 대기")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (record.derived.isNotEmpty()) item { SectionLabel("연결된 Agent 분석 및 메모") }
        items(record.derived, key = { "derived:${it.id}" }) { derived ->
            ContentCard {
                Text(when (derived.kind) { "analysis" -> "분석"; "tags" -> "태그"; else -> "추가 메모" }, style = MaterialTheme.typography.titleSmall)
                if (derived.text.isNotBlank()) Text(derived.text)
                if (derived.tags.isNotEmpty()) Note(derived.tags.joinToString(" · "))
                if (derived.stale) Note("출처가 수정되어 원래 Agent가 새로 고쳐야 함")
            }
        }
        if (record.speakerSamples.isNotEmpty()) item {
            SectionLabel("수집된 목소리 · 탭하여 이름 지정")
            record.speakerSamples.forEach { (label, samples) ->
                val segment = record.segments.firstOrNull { it.speaker == label }
                    ?: SpeechSegment("voice:$label", 0, 0, "이번 세션에서 자동 추출: ${samples.size}개 음성 특징", speaker = label, personId = record.speakerPeople[label])
                TextButton(onClick = { speakerEdit = segment }) {
                    Text("화자 ${displayLabel(label)} · ${state.people.firstOrNull { it.id == segment.personId }?.name ?: "라벨 대기"} · ${samples.size}개 샘플")
                }
            }
        }
        if (record.memories.isNotEmpty()) item { SectionLabel("출처가 있는 기억") }
        items(record.memories, key = { it.id }) { memory ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { memoryEdit = memory }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${memory.subject} · ${displayLabel(memory.kind)}", style = MaterialTheme.typography.titleSmall)
                    Text(memory.text)
                    memory.sourceIds.forEach { id -> record.segments.firstOrNull { it.id == id }?.let { source -> Note("원문: ${source.text}") } }
                }
            }
        }
        item {
            SectionLabel("원문 타임라인")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter == null, { filter = null }, label = { Text("전체") })
                record.segments.map { it.speaker }.distinct().forEach { name ->
                    FilterChip(filter == name, { filter = name }, label = { Text(displayLabel(name)) })
                }
            }
        }
        record.segments.filter { filter == null || it.speaker == filter }.groupBy { localDate(record.timeOf(it)) }.forEach { (day, segments) ->
            item(key = "timeline:$day") { SectionLabel("${day.monthValue}월 ${day.dayOfMonth}일") }
            items(segments, key = { it.id }) { segment ->
                SpeechRow(segment, state.people, record) { speakerEdit = segment }
            }
        }
    }
    speakerEdit?.let { segment -> SpeakerBindingDialog(model, record, segment, actions) { speakerEdit = null } }
    memoryEdit?.let { memory ->
        var subject by remember(memory.id) { mutableStateOf(memory.subject) }
        var text by remember(memory.id) { mutableStateOf(memory.text) }
        AlertDialog(onDismissRequest = { memoryEdit = null }, title = { Text("기억 귀속 수정") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("이 일은 누구에 대한 것인가") })
                OutlinedTextField(text, { text = it }, label = { Text("기억 내용") })
                Note("화자와 원문 출처는 유지됩니다. 저장 후 요약을 다시 정리해야 합니다.")
            }
        }, confirmButton = { TextButton(onClick = {
            model.service().conversations.correctMemory(record.id, memory.id, subject.trim(), text.trim()); memoryEdit = null
        }, enabled = subject.isNotBlank() && text.isNotBlank()) { Text("수정 저장") } }, dismissButton = { TextButton(onClick = { memoryEdit = null }) { Text("취소") } })
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
    var all by remember { mutableStateOf(segment.speaker != UNKNOWN_SPEAKER) }
    var rememberVoice by remember { mutableStateOf(record.speakerSamples[segment.speaker]?.isNotEmpty() == true) }
    val picked = model.selectedContact
    LaunchedEffect(picked) { if (picked != null) { name = picked.name; personId = null } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("화자 ${displayLabel(segment.speaker)}") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(segment.text)
            TextButton(onClick = { history = true }) { Text("과거 발화를 선택해 일괄 수정") }
            Row { Checkbox(all, { all = it }, enabled = segment.speaker != UNKNOWN_SPEAKER); Text("이번 기록의 같은 화자에게 적용") }
            people.forEach { person ->
                Row(Modifier.fillMaxWidth().clickable { personId = person.id }) {
                    RadioButton(personId == person.id, { personId = person.id }); Text(person.name)
                }
            }
            OutlinedTextField(name, { name = it; personId = null }, label = { Text("또는 새 인물 이름") })
            TextButton(onClick = { model.selectedContact = null; actions.contact() }) { Text("연락처에서 선택") }
            Row { Checkbox(rememberVoice, { rememberVoice = it }, enabled = all && record.speakerSamples[segment.speaker]?.isNotEmpty() == true)
                Text("이 목소리를 기억해 다음에 식별") }
            Note("자동 수집됨: ${record.speakerSamples[segment.speaker].orEmpty().size}개 음성 지문. 체크하면 이 목소리를 인물에 저장해 내 목소리와 헷갈리지 않게 비교하는 데 쓰고(자동 표시는 내 목소리만), 끄면 이번 라벨만 변경됩니다. 유사도는 목소리 비교 점수입니다.")
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
            Note("먼저 기록을 선택하거나 펼쳐서 발화를 고르세요. 이번에 수정할 항목: 기록 ${selected.size}개, ${count}개 원문. 관련 요약과 Agent 결과는 새로 고침 필요로 표시되며 원문과 오디오는 유지됩니다.")
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
