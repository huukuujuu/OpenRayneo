// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * TEMPORARY test aid (remove after the glasses-menu verification): speech made by the phone's TTS or read from a
 * 16-bit PCM WAV file, played in real time as 48 kHz mono PCM into the same paths that decoded glasses audio uses.
 */
internal class VirtualVoice(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    /** Main thread only: requests waiting for the TTS engine, with their failure callbacks. */
    private val waiting = mutableListOf<Pair<() -> Unit, (Throwable) -> Unit>>()
    @Volatile private var synthesizing = false
    @Volatile private var playing: Thread? = null
    val active get() = playing != null

    /** Main thread. [done] runs on a background thread with 48 kHz mono PCM. One sentence at a time. */
    fun synthesize(text: String, done: (Result<ShortArray>) -> Unit) {
        check(!synthesizing) { "앞의 가상 음성을 아직 만드는 중입니다" }
        synthesizing = true
        val finish = { result: Result<ShortArray> -> synthesizing = false; done(result) }
        val engine = tts ?: TextToSpeech(context) { status ->
            val queued = waiting.toList(); waiting.clear()
            if (status == TextToSpeech.SUCCESS) { ready = true; queued.forEach { it.first() } }
            else {
                tts?.shutdown(); tts = null; ready = false
                queued.forEach { it.second(IllegalStateException("휴대폰 TTS 엔진을 시작하지 못함")) }
            }
        }.also { tts = it }
        val request = request@{
            val locale = if (text.any { it in '가'..'힣' }) Locale.KOREAN else Locale.US
            val support = engine.setLanguage(locale)
            if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
                finish(Result.failure(IllegalStateException("휴대폰 TTS에 ${locale.displayLanguage} 음성이 없습니다. 시스템 TTS 설정에서 음성 데이터를 설치하세요")))
                return@request
            }
            val file = File(context.cacheDir, "virtual-voice.wav")
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id) finish(runCatching { wav(file.readBytes()) }) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) finish(Result.failure(IllegalStateException("TTS 합성 실패"))) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) finish(Result.failure(IllegalStateException("TTS 합성 실패 ($errorCode)"))) }
            })
            if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) finish(Result.failure(IllegalStateException("TTS 합성 요청 실패")))
        }
        if (ready) request() else waiting += request to { error: Throwable -> finish(Result.failure(error)) }
    }

    /** Real-time playback: 0.3 s silence, the speech in 20 ms chunks, then 1.5 s silence so the recognizer ends the sentence. */
    @Synchronized fun play(pcm: ShortArray, sink: (ShortArray) -> Boolean, finished: (String?) -> Unit) {
        check(playing == null) { "가상 음성을 이미 재생 중입니다" }
        val lead = RATE * 3 / 10
        val total = lead + pcm.size + RATE * 3 / 2
        val thread = Thread {
            var error: String? = null
            try {
                val begin = System.nanoTime()
                var index = 0
                while (index < total && !Thread.currentThread().isInterrupted) {
                    val size = minOf(CHUNK, total - index)
                    // Silence before and after the speech, the speech samples in between.
                    val chunk = ShortArray(size) { offset -> pcm.getOrElse(index + offset - lead) { 0 } }
                    if (!sink(chunk)) { error = "받을 기능이 닫혀 가상 음성을 멈춤"; break }
                    index += size
                    val wait = begin + index * 1_000_000_000L / RATE - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            } catch (_: InterruptedException) { error = "가상 음성 중지됨"
            } catch (failure: Exception) { error = "가상 음성 재생 실패: ${failure.message}"
            } finally {
                synchronized(this) { playing = null }
                finished(error)
            }
        }
        thread.name = "virtual-voice"
        playing = thread
        thread.start()
    }

    fun stop() { playing?.interrupt() }
    fun close() { stop(); tts?.shutdown(); tts = null }

    companion object {
        const val RATE = 48_000
        const val MAX_FILE_BYTES = 10_000_000
        private const val CHUNK = 960

        /** 16-bit PCM WAV (plain or extensible) of any rate/channel count → 48 kHz mono. */
        fun wav(bytes: ByteArray): ShortArray {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "WAV 파일이 아님" }
            var channels = 0; var rate = 0; var bits = 0
            var position = 12L
            while (position + 8 <= bytes.size) {
                val at = position.toInt()
                val id = String(bytes, at, 4)
                // Unsigned, and clamped to the file (streamed WAVs write 0xFFFFFFFF).
                val size = minOf(data.getInt(at + 4).toLong() and 0xFFFFFFFFL, bytes.size - position - 8)
                val body = at + 8
                if (id == "fmt " && size >= 16) {
                    val format = data.getShort(body).toInt() and 0xFFFF
                    require(format == 1 || format == 0xFFFE) { "압축되지 않은 PCM WAV만 지원" }
                    channels = data.getShort(body + 2).toInt(); rate = data.getInt(body + 4); bits = data.getShort(body + 14).toInt()
                }
                if (id == "data") {
                    require(channels > 0 && rate > 0 && bits == 16) { "16비트 PCM WAV만 지원" }
                    val frames = (size / (2 * channels)).toInt()
                    val mono = ShortArray(frames) { frame ->
                        var sum = 0
                        for (channel in 0 until channels) sum += data.getShort(body + (frame * channels + channel) * 2)
                        (sum / channels).toShort()
                    }
                    return resample(mono, rate)
                }
                position = body + size + (size and 1)
            }
            error("WAV 오디오 데이터가 없음")
        }

        private fun resample(input: ShortArray, rate: Int): ShortArray {
            if (rate == RATE || input.isEmpty()) return input
            val count = (input.size.toLong() * RATE / rate).toInt()
            return ShortArray(count) { index ->
                val position = index.toDouble() * rate / RATE
                val left = position.toInt().coerceAtMost(input.size - 1)
                val right = (left + 1).coerceAtMost(input.size - 1)
                val fraction = position - left
                (input[left] * (1 - fraction) + input[right] * fraction).toInt().toShort()
            }
        }
    }
}
