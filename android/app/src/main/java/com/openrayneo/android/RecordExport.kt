// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Readable Markdown of records for "내보내기": titles, times, summaries, memories and the speaker-labelled transcript. */
internal object RecordExport {
    fun markdown(records: List<ConversationRecord>, people: List<VoicePerson>, heading: String? = null): String = buildString {
        val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        if (heading != null) append("# ").append(heading).append("\n\n").append("기록 ${records.size}개\n\n")
        records.sortedBy { it.createdAt }.forEach { record ->
            append(if (heading != null) "## " else "# ").append(record.title).append("\n\n")
            append("- 종류: ${record.source.title} · ${record.mode.title}\n")
            append("- 시간: ${day.format(Date(record.createdAt))} → ${record.endedAt?.let { day.format(Date(it)) } ?: "종료 시각 미기록"}\n")
            append("- 원문: ${record.segments.size}개\n\n")
            val sub = if (heading != null) "###" else "##"
            if (record.summary.isNotBlank()) append("$sub 요약\n\n${record.summary.trim()}\n\n")
            record.summaries.filter { it.kind != "rolling" && it.text.isNotBlank() }.forEach { summary ->
                append("$sub ${if (summary.kind == "day_end") "${summary.day} 일일 요약" else "기록 종료 요약"}\n\n${summary.text.trim()}\n\n")
            }
            if (record.memories.isNotEmpty()) {
                append("$sub 기억\n\n")
                record.memories.forEach { memory ->
                    val subject = memory.subject.takeUnless { it.isBlank() || it == "null" }
                    append("- ").append(subject?.let { "$it: " }.orEmpty()).append(memory.text.replace('\n', ' ')).append("\n")
                }
                append("\n")
            }
            if (record.segments.isNotEmpty()) {
                append("$sub 원문\n\n")
                var lastDay: String? = null
                record.segments.sortedBy { record.timeOf(it) }.forEach { segment ->
                    val at = Date(record.timeOf(segment))
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(at)
                    if (date != lastDay) { if (lastDay != null) append("\n"); append("**$date**\n\n"); lastDay = date }
                    append("- ${clock.format(at)} **${speakerName(segment, record, people)}**: ${segment.text.replace('\n', ' ')}\n")
                }
                append("\n")
            }
        }
    }

    fun speakerName(segment: SpeechSegment, record: ConversationRecord, people: List<VoicePerson>): String {
        val person = (segment.personId ?: record.speakerPeople[segment.speaker])?.let { id -> people.firstOrNull { it.id == id } }
        return person?.name ?: if (segment.speaker == UNKNOWN_SPEAKER) "알 수 없음" else "화자 ${speakerTag(segment.speaker)}"
    }

    /** File-name-safe part, e.g. a record title. */
    fun fileName(text: String): String = text.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(60).ifBlank { "record" }
}
