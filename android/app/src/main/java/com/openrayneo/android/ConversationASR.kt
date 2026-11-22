// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the recognized text is for. The sentence end is when the text goes to the LLM, so each use has its own model
 * and, for the streaming model, its own silence that ends a sentence.
 */
internal enum class AsrUse(val sentenceSilenceMs: Int) {
    /** Hey RayNeo: one spoken question; the short-input model refines the whole utterance before it ends. */
    WAKE(0),
    /** Live captions, translation, Live answers and the realtime hint: hand each sentence over quickly. */
    LIVE(700),
    /** Always-on, meetings and other records: tolerate pauses while thinking. */
    RECORD(1200),
    /** Gated always-on speech spans: nothing is shown live, so each span goes whole to the cheaper non-streaming model ([BatchASR]). */
    SPAN(0),
}

internal object AsrModels {
    const val SHORT = "qwen-audio-3.1-asr-flash-message"
    const val STREAMING = "qwen-audio-3.1-asr-flash-streaming"
    /** Non-streaming HTTP model for [AsrUse.SPAN]: about a sixth of the streaming model's input token price (international list, 2026-11). */
    const val BATCH = "qwen-audio-3.1-asr-flash"
    /** The service refused the default streaming model before starting (e.g. not offered on this endpoint): use SHORT until restart. */
    @Volatile var streamingRefused = false
    /** The service refused [BATCH] (model, path or parameters): always-on falls back to streaming until restart. */
    @Volatile var batchRefused = false
    fun model(config: JSONObject, use: AsrUse): String = if (use == AsrUse.WAKE) config.optString("asrModelWake").trim().ifEmpty { SHORT }
        else config.optString("asrModelLive").trim().ifEmpty { if (streamingRefused) SHORT else STREAMING }
    fun isDefaultStreaming(config: JSONObject, use: AsrUse) = use != AsrUse.WAKE && config.optString("asrModelLive").isBlank()
}

/** Existing Bailian run-task protocol; timestamps remain on the uncut 16kHz PCM timeline. */
internal class ConversationASR(config: JSONObject, private val use: AsrUse,
    private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit,
    /** When set, the service also streams unfinished sentences (live captions); records keep using finals only. */
    private val interim: ((String) -> Unit)? = null) : WebSocketListener(), SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val model = AsrModels.model(config, use)
    private val defaultStreaming = AsrModels.isDefaultStreaming(config, use) && model == AsrModels.STREAMING
    // The streaming model documents its own parameters; the short-input ones are not sent to it.
    private val streaming = "streaming" in model || "realtime" in model
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
        // The near-field VAD fits a mic on the wearer's face.
        val parameters = JSONObject().put("format", "pcm").put("sample_rate", 16000)
            .put("heartbeat", true).put("vad_model", "near_meeting_16k")
        // Streaming always sends partials; its sentence end follows the silence chosen for this use.
        if (streaming) { if (use.sentenceSilenceMs > 0) parameters.put("max_sentence_silence", use.sentenceSilenceMs) }
        // Short input: live partials and filler/repetition cleanup are opt-in.
        else parameters.put("intermediate_result_enabled", true).put("disfluency_removal_enabled", true)
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
            "task-failed" -> {
                val code = header.optString("error_code", "unknown")
                // Only a refusal of the model or its parameters; key, balance or throttling errors would hit the other model too.
                val refused = !started && defaultStreaming && Regex("model|param|unsupported|notfound|not_found", RegexOption.IGNORE_CASE).containsMatchIn(code)
                if (refused) AsrModels.streamingRefused = true
                fail("ASR 요청 실패($model): " + code +
                    header.optString("error_message").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty() +
                    if (refused) " · 앱을 다시 시작할 때까지 ${AsrModels.SHORT} 사용" else "")
            }
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

/**
 * Always-on speech spans over the non-streaming Bailian HTTP model: audio is held on the phone and sent as a WAV
 * when the span ends, or every [CHUNK_MS] of continuous speech (the model takes at most 5 minutes per request).
 * Results stream back as SSE sentence events; timestamps are rebased onto this round like [ConversationASR].
 */
internal class BatchASR(config: JSONObject, private val sentence: (String, Long, Long, String, List<SpeechWord>) -> Unit,
    private val failed: (String) -> Unit, private val completed: () -> Unit) : SpeechRound {
    private val task = UUID.randomUUID().toString()
    private val key = config.optString("asrKey").trim()
    private val model = config.optString("asrModelBatch").trim().ifEmpty { AsrModels.BATCH }
    // Same host as the streaming socket: wss://host/api-ws/v1/inference -> https://host/api/v1/services/aigc/multimodal-generation/generation
    private val address = config.optString("asrUrl").ifBlank { AppSettings.DEFAULT_ASR_URL }.trim()
        .replaceFirst("wss://", "https://").substringBefore("/api-ws/") + "/api/v1/services/aigc/multimodal-generation/generation"
    private val languages = config.optString("asrLanguage").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val sender = Executors.newSingleThreadExecutor()
    private val chunk = Buffer()
    private var chunkStartMs = 0L
    private var appended = 0L
    private var finishing = false
    @Volatile private var ended = false
    private val seen = mutableSetOf<String>()

    override fun start() { require(key.isNotEmpty()) { "먼저 Bailian ASR API Key를 입력하세요" } }
    @Synchronized override fun canAcceptAudio() = !ended && !finishing && !busy()
    @Synchronized override fun append(samples: FloatArray) {
        if (ended || finishing) return
        for (sample in samples) chunk.writeShortLe((sample * 32768).toInt().coerceIn(-32768, 32767))
        appended += samples.size
        if (chunk.size >= CHUNK_MS * 32) flush()
    }
    @Synchronized override fun finish() {
        if (ended || finishing) return
        finishing = true; flush()
        sender.execute { if (!ended) { ended = true; release(); completed() } }
        sender.shutdown()
    }
    // shutdown(), not shutdownNow(): queued uploads still run their finally (skipping the request) so [uploads] stays exact.
    @Synchronized override fun cancel() { if (!ended) { ended = true; chunk.clear(); sender.shutdown(); http.dispatcher.cancelAll(); release() } }
    private fun flush() {
        if (chunk.size == 0L) return
        val pcm = chunk.readByteArray(); val offset = chunkStartMs; chunkStartMs = appended * 1000 / 16000
        uploads.incrementAndGet()
        sender.execute {
            // Anything thrown here would kill the app from the executor thread or let the round complete without its text.
            try { if (!ended) send(pcm, offset) } catch (error: Throwable) { fail("ASR 응답 처리 실패($model): ${error.message ?: error.javaClass.simpleName}") }
            finally { uploads.decrementAndGet() }
        }
    }
    private fun send(pcm: ByteArray, offset: Long) {
        val audio = "data:audio/wav;base64," + android.util.Base64.encodeToString(wav(pcm), android.util.Base64.NO_WRAP)
        val parameters = JSONObject().put("format", "wav").put("sample_rate", "16000")
        if (languages.isNotEmpty()) parameters.put("language_hints", org.json.JSONArray(languages))
        val body = JSONObject().put("model", model).put("parameters", parameters).put("input", JSONObject().put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", org.json.JSONArray().put(JSONObject().put("type", "input_audio")
                .put("input_audio", JSONObject().put("data", audio)))))))
        val request = Request.Builder().url(address).header("Authorization", "Bearer $key").header("X-DashScope-SSE", "enable")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val events = text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList().ifEmpty { listOf(text.trim()) }
                    .filter { it.startsWith("{") }.map(::JSONObject)
                if (!response.isSuccessful) {
                    val detail = events.firstOrNull()?.let { "${it.optString("code")} ${it.optString("message")}".trim() }.orEmpty()
                    // A span with no recognizable speech (noise, a cough) is an empty result, not a failure: failing it paused
                    // the whole life log and sent it to catch-up (device log, 2026-11-22).
                    if (response.code == 400 && "HAVE_NO_WORDS" in detail) return
                    // Only a refusal of the model, path or access; key (401), balance, throttling or content errors (DataInspection, bad audio) are not.
                    val refused = response.code == 404 || response.code in setOf(400, 403) &&
                        Regex("model|notfound|not_found|accessdenied|access_denied|unsupported|url", RegexOption.IGNORE_CASE).containsMatchIn(detail)
                    if (refused) AsrModels.batchRefused = true
                    return fail("ASR 요청 실패($model): HTTP ${response.code} $detail".trim() + if (refused) " · 앱을 다시 시작할 때까지 실시간 인식 사용" else "")
                }
                handle(events, offset)
            }
        } catch (error: java.io.IOException) { fail("ASR 연결 끊김($model): ${error.javaClass.simpleName}") }
    }
    private fun handle(events: List<JSONObject>, offset: Long) {
        if (ended) return
        val sentences = mutableListOf<JSONObject>()
        // An event without text (e.g. a trailing usage-only event) is skipped; the response fails only if none has text.
        val outputs = events.mapNotNull { it.optJSONObject("output") }.filter { it.has("sentence") || it.has("sentences") || it.has("text") }
        if (outputs.isEmpty()) {
            val first = events.firstOrNull()
            return if (first?.optJSONObject("output") == null) fail("ASR 요청 실패($model): ${first?.optString("code").orEmpty()} ${first?.optString("message").orEmpty()}".trim())
                else fail("ASR 응답 형식이 예상과 다름($model): output ${first.getJSONObject("output").keys().asSequence().toList()}")
        }
        for (output in outputs) {
            val list = output.optJSONArray("sentences")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOfNotNull(output.optJSONObject("sentence"))
            sentences += list.filter { s -> s.optBoolean("sentence_end") && s.optString("text").isNotBlank() && listOf("begin_time", "end_time").all { s.has(it) && !s.isNull(it) } }
        }
        // SSE events are growing snapshots: one sentence comes back again and again from the same begin with more text
        // and a new sentence_id (device records held 98 copies of one sentence). Keep the longest version per begin,
        // then drop any sentence lying inside another kept one (an earlier split the model later merged).
        val longest = sentences.groupBy { it.getLong("begin_time") }.values.map { versions -> versions.maxBy { it.getLong("end_time") } }
        longest.filter { s -> longest.none { other -> other !== s && other.getLong("begin_time") <= s.getLong("begin_time") &&
                other.getLong("end_time") >= s.getLong("end_time") && other.getLong("end_time") - other.getLong("begin_time") > s.getLong("end_time") - s.getLong("begin_time") } }
            .sortedBy { it.getLong("begin_time") }.forEach { s ->
            val content = s.optString("text").trim(); val id = "$task:$offset:${s.getLong("begin_time")}"
            if (content.isEmpty() || !seen.add(id)) return@forEach
            val words = s.optJSONArray("words")?.let { a -> (0 until a.length()).mapNotNull { index ->
                val word = a.getJSONObject(index)
                if (!word.has("begin_time") || word.isNull("begin_time") || !word.has("end_time") || word.isNull("end_time")) null
                else SpeechWord(word.optString("text") + word.optString("punctuation"), offset + word.getLong("begin_time"), offset + word.getLong("end_time"))
            } } ?: emptyList()
            sentence(content, offset + s.getLong("begin_time"), offset + s.getLong("end_time"), id, words)
        }
    }
    private fun wav(pcm: ByteArray): ByteArray = Buffer().apply {
        writeUtf8("RIFF"); writeIntLe(36 + pcm.size); writeUtf8("WAVEfmt "); writeIntLe(16); writeShortLe(1); writeShortLe(1)
        writeIntLe(16000); writeIntLe(32000); writeShortLe(2); writeShortLe(16); writeUtf8("data"); writeIntLe(pcm.size); write(pcm)
    }.readByteArray()
    @Synchronized private fun fail(message: String) { if (ended) return; ended = true; sender.shutdown(); release(); failed(message) }
    private fun release() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    companion object {
        private const val CHUNK_MS = 3 * 60 * 1000L
        /** Uploads queued or running across all rounds: catch-up closes spans faster than real time, so it waits on this. */
        private val uploads = AtomicInteger()
        fun busy() = uploads.get() >= 2
    }
}
