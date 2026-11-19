// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.*
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Existing Bailian run-task protocol; timestamps remain on the uncut 16kHz PCM timeline. */
internal class ConversationASR(config: JSONObject,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit,
    /** When set, the service also streams unfinished sentences (live captions); records keep using finals only. */
    private val interim: ((String) -> Unit)? = null) : WebSocketListener(), SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val model = config.optString("asrModel").ifBlank { "qwen-audio-3.1-asr-flash-message" }
    private val key = config.optString("asrKey").trim()
    private val address = config.optString("asrUrl").ifBlank { AppSettings.DEFAULT_ASR_URL }
    // Comma-separated ISO codes (e.g. "ko,en"); blank keeps the service's automatic language detection.
    private val languages = config.optString("asrLanguage").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val pending = Buffer()
    private var socket: WebSocket? = null
    private var started = false
    private var finishing = false
    private var ended = false
    private var finishSent = false
    private val seen = mutableSetOf<String>()

    @Synchronized override fun start() {
        require(key.isNotEmpty()) { "먼저 Bailian ASR API Key를 입력하세요" }
        require(address.startsWith("wss://")) { "ASR 서비스 주소는 wss://로 시작해야 합니다" }
        socket = http.newWebSocket(Request.Builder().url(address).header("Authorization", "Bearer $key").build(), this)
    }
    @Synchronized override fun canAcceptAudio() = !ended && !finishing && pending.size + (socket?.queueSize() ?: 0) < 128000
    @Synchronized override fun append(samples: FloatArray) {
        if (ended || finishing) return
        check(pending.size + samples.size * 2 <= 480000) { "ASR 전송 대기 오디오가 15초를 넘어 이번 라운드를 중지했습니다(오류 상태 유지)" }
        for (sample in samples) pending.writeShortLe((sample * 32768).toInt().coerceIn(-32768, 32767))
        pump()
    }
    @Synchronized override fun finish() { if (!ended) { finishing = true; pump() } }
    @Synchronized override fun cancel() { if (!ended) { ended = true; pending.clear(); socket?.cancel(); release() } }
    override fun onOpen(webSocket: WebSocket, response: Response) {
        val parameters = JSONObject().put("format", "pcm").put("sample_rate", 16000)
            // Live partials, filler/repetition cleanup, and the near-field VAD that fits a mic on the wearer's face.
            .put("intermediate_result_enabled", true).put("disfluency_removal_enabled", true)
            .put("heartbeat", true).put("vad_model", "near_meeting_16k")
        if (languages.isNotEmpty()) parameters.put("language_hints", org.json.JSONArray(languages))
        webSocket.send(JSONObject().put("header", header("run-task")).put("payload", JSONObject()
            .put("task_group", "audio").put("task", "asr").put("function", "recognition")
            .put("model", model).put("parameters", parameters).put("input", JSONObject())).toString())
    }
    @Synchronized override fun onMessage(webSocket: WebSocket, text: String) {
        if (ended) return
        try {
        val data = JSONObject(text); val header = data.optJSONObject("header") ?: return
        if (header.optString("task_id") != task) return
        when (header.optString("event")) {
            "task-started" -> { started = true; pump() }
            "result-generated" -> {
                val s = data.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence") ?: return
                if (s.optBoolean("heartbeat")) return
                if (!s.optBoolean("sentence_end")) { s.optString("text").trim().takeIf { it.isNotEmpty() }?.let { interim?.invoke(it) }; return }
                val content = s.optString("text").trim()
                val id = task + ":" + s.optString("sentence_id")
                if (content.isNotEmpty() && seen.add(id)) {
                    val words = s.optJSONArray("words")?.let { a -> (0 until a.length()).mapNotNull { index ->
                        val word = a.getJSONObject(index)
                        if (!word.has("begin_time") || word.isNull("begin_time") || !word.has("end_time") || word.isNull("end_time")) null
                        else SpeechWord(word.optString("text") + word.optString("punctuation"), word.getLong("begin_time"), word.getLong("end_time"))
                    } } ?: emptyList()
                    sentence(content, s.getLong("begin_time"), s.getLong("end_time"), id, words)
                }
            }
            "task-finished" -> { ended = true; socket?.close(1000, null); release(); completed() }
            "task-failed" -> fail("ASR 요청 실패: " + header.optString("error_code", "unknown"))
        }
        } catch (error: Exception) { fail("ASR 응답 처리 실패: ${error.message}") }
    }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
    @Synchronized override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!ended) fail("ASR 연결 끊김: " + (response?.code?.let { "HTTP $it" } ?: t.javaClass.simpleName))
    }
    @Synchronized override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!ended) fail("ASR 정상 종료 전에 닫힘: $code")
    }
    private fun header(action: String) = JSONObject().put("action", action).put("task_id", task).put("streaming", "duplex")
    private fun pump() {
        val target = socket ?: return
        if (!started || ended) return
        check(target.queueSize() <= 480000) { "ASR 네트워크 전송 적체가 15초를 초과함" }
        while (pending.size >= 6400 || finishing && pending.size > 0) {
            check(target.send(pending.readByteString(minOf(pending.size, 6400)))) { "ASR 오디오 전송 실패" }
        }
        if (finishing && !finishSent) {
            finishSent = true
            check(target.send(JSONObject().put("header", header("finish-task"))
                .put("payload", JSONObject().put("input", JSONObject())).toString())) { "ASR 종료 신호 전송 실패" }
        }
    }
    private fun fail(message: String) { ended = true; pending.clear(); socket?.cancel(); release(); failed(message) }
    private fun release() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
