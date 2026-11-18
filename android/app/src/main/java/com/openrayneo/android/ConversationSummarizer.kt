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
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    fun summarize(record: ConversationRecord, people: List<VoicePerson>, config: JSONObject): ConversationRecord {
        val endpoint = MobileModelEndpoint.resolve(config.optString("modelUrl"), config.optString("modelKey"))
        val body = JSONObject().put("model", config.getString("modelName")).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content",
                ConversationPrompts.render(config.optString("memoryPrompt"), ConversationPrompts.memory) +
                    "\nJSON 반환: summary 문자열, memories 배열. 각 항목에는 source_ids 배열, subject, text, kind가 있고 subject_id는 선택. 각 항목은 반드시 기존 원문 문장 ID를 참조해야 함. 텍스트는 한국어로 작성."))
                .put(JSONObject().put("role", "user").put("content", JSONObject().put("mode", record.mode.name)
                    .put("people", JSONArray(people.map { JSONObject().put("id", it.id).put("name", it.name).put("self", it.self) }))
                    .put("segments", JSONArray(record.segments.map { it.json() })).toString())))
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
            val result = JSONObject(content); val sources = record.segments.associateBy { it.id }
            val memories = result.getJSONArray("memories")
            val facts = (0 until memories.length()).map { index ->
                val item = memories.getJSONObject(index)
                val ids = item.getJSONArray("source_ids").let { list -> List(list.length()) { list.getString(it) } }
                check(ids.isNotEmpty() && ids.all { it in sources }) { "모델의 기억이 존재하지 않는 원문을 참조해 저장하지 않음" }
                val speakerIds = ids.map { sources[it]?.personId }.distinct()
                val subjectId = item.optString("subject_id").takeIf { id -> people.any { it.id == id } }
                AttributedMemory(sourceIds = ids, speakerId = speakerIds.singleOrNull(), subjectId = subjectId,
                    subject = item.getString("subject"), text = item.getString("text"), kind = item.optString("kind", "진술"))
            }
            record.copy(memories = facts, summary = result.getString("summary"), summaryStale = false)
        }
    }
    fun cancel() { call?.cancel() }
    fun close() { cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
