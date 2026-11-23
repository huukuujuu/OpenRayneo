// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class ConversationSummarizer {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    /**
     * [lines]: each sentence with the record it belongs to (a day or an always-on session spans several records).
     * Returns the summary and the memories whose source sentences exist.
     */
    fun summarize(mode: ConversationMode, lines: List<Pair<ConversationRecord, SpeechSegment>>, people: List<VoicePerson>, config: JSONObject): Pair<String, List<AttributedMemory>> {
        val clock = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA)
        // Compact input (id, time, speaker, text): a long always-on record sent with word timings and speaker
        // candidates did not finish within the timeout and was never summarized (device record, 2026-11-22).
        val segments = JSONArray(lines.map { (row, segment) ->
            JSONObject().put("id", segment.id).put("time", clock.format(java.util.Date(row.timeOf(segment))))
                .put("speaker", people.firstOrNull { it.id == segment.personId }?.let { if (it.self) "나" else it.name } ?: speakerTag(segment.speaker))
                .put("text", segment.text)
        })
        val endpoint = MobileModelEndpoint.resolve(config.optString("modelUrl"), config.optString("modelKey"))
        val body = JSONObject().put("model", config.getString("modelName")).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
                ConversationPrompts.render(config.optString("memoryPrompt"), ConversationPrompts.memory) +
                    "\nJSON 반환: summary 문자열, memories 배열. 각 항목에는 source_ids 배열, subject, text, kind가 있고 subject_id는 선택. 각 항목은 반드시 기존 원문 문장 ID를 참조해야 함. 텍스트는 한국어로 작성. speaker는 사람 이름, '나'(착용자), 또는 마이크 구분('채널1', '채널2')이나 자동 목소리 라벨(A, B 등)이다. 채널과 라벨은 사람이 아니라서 한 채널에 여러 사람이 섞일 수 있으니, 채널이나 라벨 이름을 요약에 쓰지 말고 people에 있는 이름이나 '나', '상대방'처럼 쓴다. subject를 모르면 빈 문자열."))
                .put(JSONObject().put("role", "user").put("content", JSONObject().put("mode", mode.name)
                    .put("people", JSONArray(people.map { JSONObject().put("id", it.id).put("name", it.name).put("self", it.self) }))
                    .put("segments", segments).toString())))
        val request = Request.Builder().url(endpoint).apply {
            config.optString("modelKey").takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val current = http.newCall(request); call = current
        return current.execute().use { response ->
            check(response.isSuccessful) { "기억 정리 실패: HTTP ${response.code}" }
            val objectBody = JSONObject(checkNotNull(response.body).string())
            val choice = objectBody.getJSONArray("choices").getJSONObject(0)
            check(choice.optString("finish_reason") == "stop") { "기억 정리 결과가 완전히 반환되지 않음" }
            val content = choice.getJSONObject("message").getString("content").trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val result = JSONObject(content); val sources = lines.associate { it.second.id to it.second }
            val memories = result.getJSONArray("memories")
            // A memory pointing at a sentence that does not exist is left out alone (it used to discard the whole summary).
            val facts = (0 until memories.length()).mapNotNull { index ->
                val item = memories.getJSONObject(index)
                val ids = item.getJSONArray("source_ids").let { list -> List(list.length()) { list.getString(it) } }
                if (ids.isEmpty() || !ids.all { it in sources }) return@mapNotNull null
                val speakerIds = ids.map { sources[it]?.personId }.distinct()
                val subjectId = item.optString("subject_id").takeIf { id -> people.any { it.id == id } }
                AttributedMemory(sourceIds = ids, speakerId = speakerIds.singleOrNull(), subjectId = subjectId,
                    subject = item.optString("subject").takeUnless { it == "null" }.orEmpty(), text = item.getString("text"), kind = item.optString("kind", "진술"))
            }
            result.getString("summary") to facts
        }
    }
    fun cancel() { call?.cancel() }
    fun close() { cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
