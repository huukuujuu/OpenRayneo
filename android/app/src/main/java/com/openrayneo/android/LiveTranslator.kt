// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Korean ↔ English live interpretation on the phone. The glasses prompt page is only the window and
 * microphone: glasses PCM → Bailian ASR (ko,en hints, interim captions) → chat-completions translation.
 * Display: top line = latest speech (partial or final), bottom line = translation of the latest piece
 * ("…" while pending). Long unbroken speech (a video, a lecture) rarely ends a sentence, so the partial
 * text is also cut into pieces at punctuation, after [CHUNK_MS] or at [CHUNK_CHARS] and translated as it goes.
 * Only the newest piece is translated, so the glasses never fall behind.
 * Live captions ([captions] = true) reuse the same window and ASR without translation: the top line is the
 * last finished sentence, the bottom line the speech in progress, using the user's own ASR engine and hints.
 * All callbacks are funnelled onto [main]; nothing is stored or added to records.
 */
internal class LiveTranslator(
    private val context: android.content.Context,
    private val main: Handler,
    private val show: (source: String, target: String) -> Unit,
    private val changed: () -> Unit,
    /** The translator gave up on its own (e.g. repeated ASR failures); the owner should close the window. */
    private val gaveUp: () -> Unit,
) {
    @Volatile var active = false; private set
    @Volatile var status = OFF; private set
    @Volatile var captions = false; private set
    /** Captions for the glasses' own caption screen: send whole lines, the glasses wrap and scroll them. */
    private var fullLines = false

    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val translations = Executors.newSingleThreadExecutor()
    private var config = JSONObject()
    @Volatile private var generation = 0
    private var asr: SpeechRound? = null
    private var resampler = ConversationAudio()
    @Volatile private var call: Call? = null
    // Main-thread display state.
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var latestFinal = 0L
    private var sourceLine = ""
    private var targetLine = ""
    private var failures = 0
    /** Characters of the current partial already sent for translation, and when the last piece was cut (0 = none yet). */
    private var committed = ""
    private var pieceAt = 0L
    private var restartTask: Runnable? = null

    /** Throws with a user-facing message when ASR or the translation model is not configured. */
    fun validate(settings: JSONObject, captions: Boolean = false) {
        if (captions) { require(SpeechRounds.ready(context, settings)) { SpeechRounds.missing(settings) }; return }
        val speech = SpeechRounds.bilingual(context, settings)
        require(SpeechRounds.ready(context, speech)) { SpeechRounds.missing(speech) }
        require(MobileModelEndpoint.isConfigured(settings.optString("modelUrl"), settings.optString("modelName"), settings.optString("modelKey"))) {
            "번역에는 기록 요약 모델(주소, 모델 이름, API Key)이 필요합니다. 음성 설정에서 먼저 입력하세요"
        }
        MobileModelEndpoint.resolve(settings.optString("modelUrl"), settings.optString("modelKey"))
    }

    /** Must be called on the main thread. */
    fun start(settings: JSONObject, captions: Boolean = false, fullLines: Boolean = false) {
        validate(settings, captions)
        stop()
        synchronized(this) {
            this.captions = captions
            this.fullLines = captions && fullLines
            config = if (captions) JSONObject(settings.toString())
                else JSONObject(SpeechRounds.bilingual(context, settings).toString()).put("asrLanguage", "ko,en")
            generation++
            active = true
            resampler = ConversationAudio()
        }
        history.clear(); latestFinal = 0; sourceLine = ""; targetLine = ""; failures = 0; committed = ""; pieceAt = 0L
        status = listening()
        openASR()
        changed()
    }

    /** Must be called on the main thread. [reason] stays visible as the status after stopping. */
    fun stop(reason: String? = null) {
        val wasActive: Boolean
        synchronized(this) {
            wasActive = active
            active = false
            generation++
            asr?.cancel(); asr = null
        }
        restartTask?.let(main::removeCallbacks); restartTask = null
        call?.cancel()
        if (wasActive || reason != null) { status = reason ?: OFF; changed() }
    }

    /** Called on the audio worker with 48 kHz PCM from the glasses prompt page. */
    @Synchronized fun accept(left: ShortArray, right: ShortArray) {
        if (!active) return
        // Bidirectional: either speaker may be on either microphone, so feed one mixed stream.
        val mixed = if (right.size == left.size) ShortArray(left.size) { ((left[it].toInt() + right[it].toInt()) / 2).toShort() } else left
        val samples = resampler.convert(mixed)
        val target = asr ?: return
        // Stay live: drop audio rather than queueing behind a slow or reconnecting ASR socket.
        if (!target.canAcceptAudio()) return
        try { target.append(samples) } catch (error: Exception) {
            val token = generation
            main.post { asrEnded(token, error.message ?: "ASR 오디오 전송 실패") }
        }
    }

    /** Must be called on the main thread (service onDestroy). */
    fun close() { stop(); translations.shutdownNow(); http.dispatcher.executorService.shutdown() }

    private fun openASR() {
        val token: Int
        val round: SpeechRound
        synchronized(this) {
            if (!active) return
            token = generation
            resampler = ConversationAudio()
            round = SpeechRounds.create(context, config,
                { text, _, _, _, _ -> main.post { finished(token, text) } },
                { message -> main.post { asrEnded(token, message) } },
                { main.post { asrEnded(token, null) } },
                { text -> main.post { interim(token, text) } })
            asr = round
        }
        try { round.start() } catch (error: Exception) { asrEnded(token, error.message ?: "ASR 시작 실패") }
    }

    private fun asrEnded(token: Int, message: String?) {
        synchronized(this) {
            if (token != generation || !active) return
            asr?.cancel(); asr = null
        }
        // A normal end (null) just reopens; errors back off and give up after repeated failures without speech.
        if (message != null) failures++
        if (failures >= MAX_FAILURES) {
            stop("ASR 연결 실패로 ${if (captions) "자막" else "번역"}을 멈췄습니다: $message")
            gaveUp()
            return
        }
        status = if (message == null) "ASR 세션 종료, 다시 연결 중…" else "$message · 다시 연결 중 ($failures/$MAX_FAILURES)…"
        changed()
        restartTask?.let(main::removeCallbacks)
        restartTask = Runnable { restartTask = null; if (token == generation && active) openASR() }
            .also { main.postDelayed(it, 1_500L * maxOf(1, failures)) }
    }

    private fun heard() {
        if (failures != 0 || status != listening()) { failures = 0; status = listening(); changed() }
    }

    private fun listening() = if (captions) CAPTIONS_LISTENING else LISTENING
    private fun render() = if (fullLines) show(sourceLine.trim(), targetLine.trim())
        else show(clip(sourceLine, SOURCE_LIMIT), if (captions) clip(targetLine, TARGET_LIMIT) else targetLine)

    private fun interim(token: Int, text: String) {
        if (token != generation || !active || misheard(text)) return
        heard()
        if (captions) { targetLine = text; render(); return }
        sourceLine = text
        render()
        // The recognizer rewrote text that was already sent: keep only the part that still matches.
        if (!text.startsWith(committed)) { committed = committed.commonPrefixWith(text); if (committed.isEmpty()) pieceAt = 0L }
        val pending = text.substring(committed.length)
        val now = System.currentTimeMillis()
        // The time limit counts from the first partial of a sentence, not from the silence before it.
        if (pieceAt == 0L) pieceAt = now
        val boundary = pending.indexOfLast { it in BOUNDARIES } + 1
        val cut = when {
            boundary >= MIN_CHUNK_CHARS -> boundary
            pending.length >= CHUNK_CHARS || now - pieceAt >= CHUNK_MS && pending.length >= MIN_CHUNK_CHARS ->
                // Keep the last (still changing) word for the next piece.
                pending.lastIndexOf(' ').takeIf { it >= MIN_CHUNK_CHARS } ?: pending.length
            else -> return
        }
        val piece = pending.take(cut).trim()
        committed += pending.take(cut); pieceAt = now
        if (piece.isNotEmpty()) translatePiece(token, piece)
    }

    private fun finished(token: Int, text: String) {
        if (token != generation || !active) return
        heard()
        // Neither Korean nor English (typically noise or Korean misread as Chinese): never show or translate it.
        if (misheard(text)) { committed = ""; pieceAt = 0L; return }
        if (captions) { sourceLine = text; targetLine = ""; render(); return }
        // Pieces of this sentence were already translated from the partial text; translate only what is left.
        // If the final rewrote those words (e.g. filler removal), translate it whole rather than lose words.
        val rest = if (committed.isNotEmpty() && text.startsWith(committed)) text.drop(committed.length).trim() else text
        committed = ""; pieceAt = 0L
        sourceLine = text
        if (rest.isEmpty()) { render(); return }
        translatePiece(token, rest)
    }

    private fun translatePiece(token: Int, text: String) {
        val sequence = ++latestFinal
        val from = if (isKorean(text)) "Korean" else "English"
        val to = if (from == "Korean") "English" else "Korean"
        targetLine = "…"
        render()
        val context = history.toList()
        val snapshot = config
        translations.execute {
            // Skip sentences already superseded by a newer final: only the latest translation is ever shown.
            if (token != generation || sequence != latestFinal) return@execute
            val result = runCatching { translate(token, snapshot, text, from, to, context) }
            main.post {
                if (token != generation || !active) return@post
                result.fold({ translated ->
                    history.addLast(text to translated); while (history.size > 3) history.removeFirst()
                    if (sequence == latestFinal) { targetLine = clip(translated, TARGET_LIMIT); render() }
                }, { error ->
                    if (sequence == latestFinal) { targetLine = "(번역 실패)"; render() }
                    status = "번역 실패: ${error.message ?: error.javaClass.simpleName}"
                    changed()
                })
            }
        }
    }

    private fun translate(token: Int, settings: JSONObject, text: String, from: String, to: String, context: List<Pair<String, String>>): String {
        val endpoint = MobileModelEndpoint.resolve(settings.optString("modelUrl"), settings.optString("modelKey"))
        val previous = if (context.isEmpty()) "" else "\nRecent conversation for context only (do not translate it again):\n" +
            context.joinToString("\n") { (source, target) -> "- $source => $target" }
        val system = "You are a live interpreter between Korean and English shown on smart glasses. " +
            "Translate the user's utterance from $from to $to. Output only the translation in natural spoken $to, " +
            "with no quotes, notes, romanization or explanations. Keep names and numbers accurate.$previous"
        val body = JSONObject().put("model", settings.getString("modelName")).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
        val request = Request.Builder().url(endpoint).apply {
            settings.optString("modelKey").takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val current = http.newCall(request); call = current
        // stop() may have run between the generation check and assigning the call.
        if (token != generation) current.cancel()
        return current.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            JSONObject(checkNotNull(response.body).string()).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim().trim('"').ifBlank { error("빈 번역 결과") }
        }
    }

    companion object {
        private const val OFF = "실시간 번역 꺼짐"
        private const val LISTENING = "듣는 중 · 한국어 ↔ 영어"
        private const val CAPTIONS_LISTENING = "듣는 중 · 실시간 자막"
        private const val MAX_FAILURES = 5
        private const val SOURCE_LIMIT = 80
        private const val TARGET_LIMIT = 120
        private const val MIN_CHUNK_CHARS = 8
        private const val CHUNK_CHARS = 60
        private const val CHUNK_MS = 3_000L
        private val BOUNDARIES = setOf('.', '?', '!', ',', '。', '？', '！', '，', '、')
        fun clip(text: String, limit: Int): String { val value = text.trim(); return if (value.length <= limit) value else "…" + value.takeLast(limit - 1) }
        /** True when the text has no Hangul or Latin letters but does have CJK ideographs or kana. */
        fun misheard(text: String) = text.none { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' } &&
            text.any { it in '一'..'鿿' || it in '㐀'..'䶿' || it in '぀'..'ヿ' }
        /** Hangul share of letters decides the direction; mixed or Latin-only utterances count as English. */
        fun isKorean(text: String): Boolean {
            val hangul = text.count { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
            val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
            return hangul > 0 && hangul * 2 >= latin
        }
    }
}
