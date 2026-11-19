// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.util.UUID

/**
 * The glasses' own Voice memo (business 0x0e). The glasses start, pause, mark and stop the recording and stream
 * 240-byte Opus packets (type 3, uuid + byte offset); the phone answers each control request with action 2
 * (same shapes as the earlier Mac NativeRecordingController) and keeps the audio as a record that is transcribed later.
 */
internal class VoiceMemo(
    context: Context,
    private val repository: ConversationRepository,
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val saved: () -> Unit,
) {
    private val store = MeetingAudioStore(context)
    private val thread = HandlerThread("voice-memo").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var sequence = 0x5000

    private inner class Capture(val uuid: String) {
        val recordId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        private val resampler = ConversationAudio()
        /** Virtual voice has its own filter state: the decoder thread and the worker never share one. */
        val injectResampler = ConversationAudio()
        @Volatile var open = true
        var nextOffset = 0L
        var paused = false
        var stopping = false
        var gapReported = false
        val remainder = java.io.ByteArrayOutputStream()
        val decoder = PromptAudio { left, right ->
            if (injecting) return@PromptAudio
            val mixed = if (right.size == left.size) ShortArray(left.size) { ((left[it].toInt() + right[it].toInt()) / 2).toShort() } else left
            val pcm = resampler.convert(mixed)
            synchronized(store) { if (open) store.append(pcm) }
        }
    }
    @Volatile private var active: Capture? = null
    /** TEMPORARY test aid: while virtual voice plays, the glasses' own audio is ignored. */
    @Volatile var injecting = false
    val recording get() = active != null

    /** TEMPORARY test aid: 48 kHz mono PCM recorded as if it came from the glasses. */
    fun inject(pcm: ShortArray): Boolean {
        val capture = active ?: return false
        worker.post { val samples = capture.injectResampler.convert(pcm); synchronized(store) { if (capture.open) store.append(samples) } }
        return true
    }
    private val close = Runnable { runCatching { active?.takeIf { it.stopping }?.let { closeNow(it, "안경 음성 메모 저장") } }.onFailure { log("음성 메모 저장 실패: ${it.message}") } }

    /** Any thread: frames from BLE or SPP. */
    fun receive(frame: ProtocolCodec.Frame) {
        if (frame.protocol != 0x0e || frame.acknowledgement) return
        val message = ProtocolCodec.message(frame.payload)
        val body = message.json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val audio = message.audio
        worker.post { runCatching { handle(message.type, body, audio) }.onFailure { log("음성 메모 처리 실패: ${it.message}") } }
    }

    fun disconnected() = worker.post { runCatching { active?.let { closeNow(it, "연결이 끊겨 지금까지의 음성 메모를 저장") } }.onFailure { log("음성 메모 저장 실패: ${it.message}") } }

    private fun handle(type: Int, body: JSONObject, audio: ByteArray?) {
        val action = body.optInt("action")
        val uuid = body.optString("uuid")
        val current = active
        when (type) {
            1 -> if (action == 1 && uuid.isNotBlank()) {
                // code 1 = start; the same request comes back with another code (e.g. 25) when the phone did not answer.
                val code = if (body.has("code")) body.optInt("code") else 1
                if (code != 1) { log("안경 음성 메모 시작 요청 code=$code (응답 대기 실패로 보임)"); return }
                if (current?.uuid != uuid) { current?.let { closeNow(it, "새 음성 메모가 시작되어 이전 녹음을 저장") }; begin(uuid) }
                reply(1, JSONObject().put("uuid", uuid).put("type", 1).put("mode", body.optInt("mode", 1)).put("code", 1).put("action", 2)
                    .apply { body.optString("sid").takeIf { it.isNotBlank() }?.let { put("sid", it) } })
                log("안경 음성 메모 시작 → 휴대폰에 녹음 중")
            }
            3 -> {
                val data = audio?.takeIf { it.isNotEmpty() } ?: return
                val capture = when {
                    current?.uuid == uuid -> current
                    current == null && uuid.isNotBlank() -> begin(uuid)
                    else -> return
                }
                accept(capture, body.optLong("offset", capture.nextOffset), data)
                if (capture.stopping) { worker.removeCallbacks(close); worker.postDelayed(close, 3_000) }
            }
            4 -> {
                if (action == 1) reply(4, JSONObject().put("code", body.optInt("code", 1)).put("action", 2))
                // The glasses may still send buffered audio after stop: save once it goes quiet or type 6 says complete.
                current?.let { it.stopping = true; worker.removeCallbacks(close); worker.postDelayed(close, 3_000) }
            }
            6 -> if (body.optBoolean("completed") && current != null && (uuid.isBlank() || uuid == current.uuid)) closeNow(current, "안경 음성 메모 전송 완료, 저장")
            8 -> { if (action == 1) reply(8, JSONObject().put("uuid", uuid).put("code", body.optInt("code", 1)).put("action", 2)); current?.paused = true }
            9 -> { if (action == 1) reply(9, JSONObject().put("uuid", uuid).put("action", 2)); current?.paused = false }
            10 -> if (action == 1 && body.has("mode")) reply(10, JSONObject().put("uuid", uuid).put("mode", body.optInt("mode")).put("action", 2))
            11 -> if (action == 1) reply(11, JSONObject().put("uuid", uuid.ifBlank { current?.uuid.orEmpty() }).put("code", if (current != null) 1 else 3).put("action", 2))
            5 -> if (action == 1) reply(5, JSONObject().put("uuid", current?.uuid.orEmpty()).put("action", 2)
                .put("state", when { current == null -> 3; current.paused -> 2; else -> 1 }))
        }
    }

    private fun begin(uuid: String): Capture {
        val capture = Capture(uuid)
        synchronized(store) { store.start(capture.recordId, 1) }
        // Saved as audio-pending first: if the app dies mid-memo, ConversationController recovers the file on start.
        repository.save(record(capture, 0, audioPending = true))
        capture.decoder.start(2)
        active = capture
        return capture
    }

    private fun accept(capture: Capture, offset: Long, bytes: ByteArray) {
        var data = bytes
        var start = offset
        if (start < capture.nextOffset) {
            // Overlap with what we already have (a resend): keep only the new tail.
            val skip = capture.nextOffset - start
            if (skip >= data.size) return
            data = data.copyOfRange(skip.toInt(), data.size); start = capture.nextOffset
        } else if (start > capture.nextOffset) {
            if (!capture.gapReported) { capture.gapReported = true; log("안경 음성 메모 오디오 일부가 빠짐 (${start - capture.nextOffset}바이트), 이어서 저장") }
            capture.remainder.reset()
        }
        capture.nextOffset = start + data.size
        capture.remainder.write(data)
        val all = capture.remainder.toByteArray()
        var index = 0
        while (index + PACKET <= all.size) { capture.decoder.accept(all.copyOfRange(index, index + PACKET)); index += PACKET }
        capture.remainder.reset(); capture.remainder.write(all, index, all.size - index)
    }

    private fun closeNow(capture: Capture, message: String) {
        worker.removeCallbacks(close)
        if (active === capture) active = null
        // Let the decoder finish the packets already queued before closing the file.
        val deadline = System.currentTimeMillis() + 3_000
        while (capture.decoder.pending > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        capture.decoder.close()
        val samples = synchronized(store) { capture.open = false; store.finish() }
        if (samples < 16_000) log("안경 음성 메모가 1초보다 짧음")
        repository.save(record(capture, samples, audioPending = false))
        log("$message (${samples / 16_000}초), 전사는 기록 탭에서 이어집니다")
        main.post(saved)
    }

    private fun record(capture: Capture, samples: Long, audioPending: Boolean) = ConversationRecord(id = capture.recordId,
        mode = ConversationMode.DAILY, createdAt = capture.startedAt, title = "음성 메모", source = ConversationSource.VOICE_MEMO,
        audioSamples = samples, audioId = capture.recordId, audioPending = audioPending, processingPending = true,
        endedAt = if (audioPending) null else System.currentTimeMillis(), audioChannels = 1,
        channelPolicy = SpeakerChannelPolicy.policy(ConversationSource.VOICE_MEMO, 1), audioAnchors = listOf(AudioAnchor(0, capture.startedAt)))

    private fun reply(type: Int, body: JSONObject) {
        val frame = ProtocolCodec.display(0x0e, sequence++ and 65535, type, body.toString(), byteArrayOf())
        main.post { runCatching { write(frame) { result -> result.onFailure { log("음성 메모 응답 전송 실패: ${it.message}") } } }
            .onFailure { log("음성 메모 응답 전송 실패: ${it.message}") } }
    }

    fun close() { worker.post { active?.let { closeNow(it, "앱 종료 전 음성 메모 저장") } }; thread.quitSafely() }

    private companion object { const val PACKET = 240 }
}
