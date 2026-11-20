// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class TeleprompterState(val documents: List<JSONObject> = emptyList(), val active: String? = null,
    val paused: Boolean = false, val busy: Boolean = false, val notice: String = "원고를 저장한 뒤 안경으로 전송", val activeText: String? = null)
internal class TeleprompterLibrary(
    context: Context,
    private val requests: GlassesRequests,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val folder = File(context.filesDir, "scripts").apply { mkdirs() }
    @Volatile var state = TeleprompterState(documents = folder.listFiles().orEmpty().filter { it.extension == "json" }.map { JSONObject(it.readText()) }); private set
    private val messages = LinkedBlockingQueue<Pair<Int, JSONObject>>(64)
    /** Requests the glasses send on their own (action 1), e.g. "start reading" after they picked a script. */
    private val glassesRequests = LinkedBlockingQueue<Pair<Int, JSONObject>>(16)
    @Volatile private var linked = false
    @Volatile private var lastSpeed = 2
    private val transfer = LinkedBlockingQueue<WireFields.Value>(64)
    @Volatile private var task: String? = null
    @Volatile private var disconnected = false
    private var sequence = 0x5300
    /** [at] and [paused] tell whether the glasses could have moved on since: auto-scroll keeps running unless paused. */
    private data class ScriptPosition(val id: String, val offset: Int,
        val at: Long = android.os.SystemClock.elapsedRealtime(), val paused: Boolean = false)
    private val scrollPosition = AtomicReference<ScriptPosition?>()
    // Readback can be rejected during glasses-initiated playback. Probe once until a new script starts.
    private var queryScrollPosition = true

    private fun beginPlaybackPosition(id: String) {
        scrollPosition.set(ScriptPosition(id, 0))
        queryScrollPosition = true
    }
    fun save(title: String, text: String, id: String = UUID.randomUUID().toString(), refresh: Boolean = true): String {
        require(title.isNotBlank() && text.isNotBlank()); require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val document = JSONObject().put("did", id).put("title", title).put("text", text).put("updated", System.currentTimeMillis())
        val file = android.util.AtomicFile(File(folder, "$id.json")); val out = file.startWrite()
        try { out.write(document.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
        state = state.copy(documents = listOf(document) + state.documents.filter { it.getString("did") != id }); changed()
        if (refresh) refreshGlasses()
        return id
    }

    /** Text files (name, already read bytes) become scripts titled by the file name; all are checked before any is saved. */
    fun import(files: List<Pair<String, ByteArray>>): Int {
        val scripts = files.map { (name, bytes) ->
            val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n').trim()
            require(text.isNotEmpty()) { "$name: 빈 파일" }
            name.substringBeforeLast('.').ifBlank { name }.take(80) to text
        }
        scripts.forEach { (title, text) -> save(title, text, refresh = false) }
        refreshGlasses()
        return scripts.size
    }

    /** UTF-8 (with or without BOM) first; Korean Windows text files are often CP949. */
    private fun decode(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, charset("MS949")) }.removePrefix("\uFEFF")
    }

    fun connected() { linked = true; refreshGlasses() }

    /** The glasses' own teleprompter menu lists only what the phone sent; push the whole library. */
    private fun refreshGlasses() {
        if (!linked) return
        requests.perform({ syncList() }) { result -> result.exceptionOrNull()?.let { state = state.copy(notice = "안경 원고 목록 동기화 실패: ${it.message}"); changed() } }
    }

    private fun item(document: JSONObject): JSONObject {
        val text = document.getString("text")
        return JSONObject().put("did", document.getString("did")).put("pin", false).put("title", document.getString("title")).put("offset", 0)
            .put("scroll", 2).put("total", text.toByteArray(Charsets.UTF_8).size).put("timestamp", document.optLong("updated") / 1000)
            .put("preview", text.take(80)).put("timezone", ZonedDateTime.now().offset.totalSeconds)
    }

    /** List type 1 = full sync, sent in small chunks so each frame stays short. */
    private fun syncList() {
        val documents = state.documents
        val chunks = documents.chunked(3).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { index, chunk ->
            send(1, JSONObject().put("action", 2).put("type", 1).put("total", documents.size)
                .put("chunkinfo", JSONObject().put("chunkid", index + 1).put("total_items", documents.size).put("islast", index == chunks.lastIndex))
                .put("items", JSONArray(chunk.map(::item))))
        }
    }

    private fun settings(speed: Int) = JSONObject().put("countdown", 3).put("scroll", 2).put("speed", speed).put("gear", 1)
        .put("depth", 1).put("size", 18).put("width", 492).put("leading", 4)

    private fun checksum(content: ByteArray): String {
        var value = 0x811c9dc5u
        for (byte in content) value = (value xor (byte.toUInt() and 255u)) * 0x01000193u
        return value.toString(16).padStart(8, '0')
    }

    /**
     * The user picked a script in the glasses' teleprompter menu: answer with its length (code 1, or 5 if the phone
     * no longer has it), send the body over the file channel, then confirm the glasses' "start reading" request.
     */
    private fun glassesOpen(id: String): JSONObject {
        val document = state.documents.firstOrNull { it.getString("did") == id }
        if (document == null) {
            send(2, JSONObject().put("action", 2).put("did", id).put("code", 5))
            refreshGlasses()
            error("안경이 고른 원고가 휴대폰에 없어 목록을 다시 보냄")
        }
        val text = document.getString("text")
        val content = text.toByteArray(Charsets.UTF_8)
        val settings = settings(lastSpeed)
        messages.clear(); transfer.clear(); disconnected = false
        send(2, JSONObject(settings.toString()).put("action", 2).put("did", id).put("total", content.size)
            .put("pageOffset", 0).put("highLightOffset", 0).put("code", 1))
        beginPlaybackPosition(id)
        state = state.copy(active = id, paused = true, activeText = text)
        transferFile(id, content)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            check(!disconnected) { "안경 연결이 끊김" }
            val remaining = deadline - System.nanoTime()
            val (type, body) = glassesRequests.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: return JSONObject().put("did", id).put("message", "원고를 안경에 보냈습니다(재생 시작 요청은 받지 못함)")
            if (type != 3 || body.optString("did") != id) continue
            send(3, JSONObject(settings.toString()).put("action", 2).put("did", id).put("total", content.size)
                .put("pageOffset", 0).put("highLightOffset", 0).put("code", 1).put("checksum", checksum(content)))
            state = state.copy(active = id, paused = false)
            return JSONObject().put("did", id).put("message", "안경에서 고른 원고 \"${document.getString("title")}\" 재생 중")
        }
    }

    fun observe(frame: ProtocolCodec.Frame) {
        if (frame.protocol == 0x11 && task != null) {
            WireFields.parse(frame.payload).firstOrNull()?.let { field ->
                val fields = field.bytes?.let(WireFields::parse).orEmpty()
                if (fields.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) == task)
                    check(transfer.offer(field)) { "프롬프터 파일 응답 대기열이 가득 참" }
            }
        } else if (frame.protocol == 0x14) {
            val message = ProtocolCodec.message(frame.payload); val body = message.json?.let(::JSONObject) ?: return
            if (body.optInt("action") == 2) { if (!messages.offer(message.type to body)) { messages.poll(); messages.offer(message.type to body) } }
            if (body.optInt("action") == 1) when (message.type) {
                1 -> refreshGlasses()
                2 -> body.optString("did").takeIf { it.isNotBlank() }?.let { id ->
                    if (state.busy) return@let
                    glassesRequests.clear()
                    operation({ glassesOpen(id) }) {}
                }
                3 -> if (!glassesRequests.offer(message.type to body)) { glassesRequests.poll(); glassesRequests.offer(message.type to body) }
            }
            if (message.type == 8) {
                val id = body.optString("did")
                val offset = body.optInt("pageOffset", -1)
                if (offset >= 0) scrollPosition.updateAndGet {
                    if (it?.id == id) ScriptPosition(id, offset, paused = state.paused) else it
                }
            }
            if (body.optInt("action") == 1 && body.optString("did") == state.active && message.type == 6) {
                scrollPosition.set(null)
                state = state.copy(active = null, paused = false, activeText = null, notice = "안경이 프롬프터를 종료함"); changed()
            }
        }
    }
    fun start(id: String, speed: Int, completion: (Result<JSONObject>) -> Unit) = operation({ play(id, speed) }, completion)
    fun startText(title: String, text: String, speed: Int, completion: (Result<JSONObject>) -> Unit) = start(save(title, text), speed, completion)
    private fun play(id: String, speed: Int): JSONObject {
        require(speed in 1..10)
        lastSpeed = speed
        val document = state.documents.first { it.getString("did") == id }
        val text = document.getString("text"); val content = text.toByteArray(Charsets.UTF_8)
        if (state.active != null) controlBlocking("stop")
        messages.clear(); transfer.clear(); disconnected = false
        val settings = settings(speed)
        val item = item(document)
        send(1, JSONObject().put("action", 2).put("type", 3).put("total", state.documents.size)
            .put("chunkinfo", JSONObject().put("chunkid", 1).put("total_items", 1).put("islast", true)).put("items", JSONArray().put(item)))
        send(2, JSONObject(settings.toString()).put("action", 1).put("did", id).put("total", content.size).put("pageOffset", 0).put("highLightOffset", 0))
        beginPlaybackPosition(id)
        state = state.copy(active = id, paused = true, activeText = text)
        transferFile(id, content)
        accepted(2, id)
        Thread.sleep(350) // Firmware startup transition, measured on 1.0.5.12.
        send(3, JSONObject(settings.toString()).put("action", 1).put("did", id).put("total", content.size)
            .put("pageOffset", 0).put("highLightOffset", 0).put("code", 1).put("checksum", checksum(content)))
        accepted(3, id)
        state = state.copy(active = id, paused = false)
        return JSONObject().put("did", id).put("accepted", true).put("message", "안경이 프롬프터 재생을 확인함")
    }

    /** Body transfer over the generic file channel: the glasses ask for ranges until they report the result. */
    private fun transferFile(id: String, content: ByteArray) {
        val taskId = "task_2_${System.currentTimeMillis()}"; task = taskId
        try {
            // MD5 and FNV are required wire fields, matching the official protocol.
            val md5 = MessageDigest.getInstance("MD5").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            val info = WireFields.string(1, taskId) + WireFields.string(2, id) + WireFields.integer(3, content.size.toLong()) + WireFields.string(4, md5) + WireFields.integer(5, 17)
            requests.sendRaw(ProtocolCodec.frame(17, 0, WireFields.bytes(1, info)))
            while (true) {
                check(!disconnected) { "연결이 끊겨 프롬프터 전송이 취소됨" }
                val field = checkNotNull(transfer.poll(30, TimeUnit.SECONDS)) { "안경이 파일 데이터를 요청하지 않아 전송 시간 초과" }
                val values = WireFields.parse(checkNotNull(field.bytes))
                fun number(n: Int) = values.firstOrNull { it.number == n }?.integer
                if (field.number == 6) error("안경이 프롬프터 파일 전송을 취소함")
                if (field.number == 5) { check(number(2) == 0L) { "안경이 파일 전송을 거부함" }; break }
                if (field.number != 3) continue
                val offset = number(3) ?: 0; val size = minOf(checkNotNull(number(4)), content.size - offset)
                require(offset in 0 until content.size.toLong() && size in 1..60000)
                val chunk = content.copyOfRange(offset.toInt(), (offset + size).toInt())
                val entry = WireFields.string(1, taskId) + WireFields.integer(2, number(2) ?: 0) + WireFields.integer(3, offset) + WireFields.integer(4, size) + WireFields.bytes(5, chunk)
                requests.sendRaw(ProtocolCodec.frame(17, 0, WireFields.bytes(4, entry)))
            }
        } finally { task = null }
    }
    private fun accepted(type: Int, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            check(!disconnected) { "안경 연결이 끊김" }
            val remaining = deadline - System.nanoTime(); check(remaining > 0) { "안경이 프롬프터 요청을 확인하지 않음$type" }
            val (received, body) = checkNotNull(messages.poll(remaining, TimeUnit.NANOSECONDS)) { "프롬프터 확인 시간 초과" }
            if (received != type || body.optString("did") != id) continue
            check(body.optInt("code") == 1 || type == 2 && body.optInt("code") == 7) { "안경이 프롬프터를 거부함: ${body.optInt("code")}" }; return
        }
    }
    fun scroll(down: Boolean, fromRemote: Boolean) {
        val direction = if (down) "아래로" else "위로"
        log("프롬프터 스크롤 입력: ${if (fromRemote) "리모컨" else "휴대폰"} ($direction)")
        operation({ scrollBlocking(down, direction) }) { result ->
            result.fold(
                { log("프롬프터 스크롤: ${it.optString("message")} (${it.optInt("beforeOffset", -1)} → ${it.optInt("requestedOffset", -1)}, 응답 ${it.optInt("actualOffset", -1)})") },
                { log("프롬프터 스크롤 실패: ${it.message}") },
            )
        }
    }

    private fun scrollBlocking(down: Boolean, direction: String): JSONObject {
        val id = checkNotNull(state.active) { "재생 중인 원고가 없습니다" }
        val text = checkNotNull(state.activeText) { "재생 중인 원고의 내용을 찾지 못했습니다" }
        val freshOffset = readScrollOffset(id)
        val lastPosition = scrollPosition.get()?.takeIf { it.id == id } ?: error("재생 중인 원고가 바뀌었습니다")
        // Without a readback the last known position goes stale while the script auto-scrolls: refuse instead of jumping back.
        check(freshOffset != null || lastPosition.paused && state.paused ||
            android.os.SystemClock.elapsedRealtime() - lastPosition.at < 5_000) { "안경의 현재 원고 위치를 알 수 없어 스크롤하지 않았습니다. 휴대폰의 일시정지 버튼으로 멈춘 뒤 다시 누르세요" }
        val pageOffset = freshOffset ?: lastPosition.offset
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(pageOffset in 0..bytes.size) { "안경에서 현재 원고 위치를 읽지 못했습니다" }
        // Protocol offsets are UTF-8 bytes. Snap the position to a character before measuring a line.
        var startByte = pageOffset
        while (startByte in 1 until bytes.size && (bytes[startByte].toInt() and 0xc0) == 0x80) startByte--
        val start = String(bytes, 0, startByte, Charsets.UTF_8).length
        if (down && start == text.length) return JSONObject().put("message", "원고 끝입니다")
        if (!down && start == 0) return JSONObject().put("message", "원고 처음입니다")
        val paint = android.graphics.Paint().apply { textSize = 22f }
        val target = if (down) {
            val fit = paint.breakText(text, start, text.length, true, 492f, null)
            var end = (start + fit.coerceAtLeast(1)).coerceAtMost(text.length)
            val newline = text.indexOf('\n', start)
            if (newline in start until end) end = newline + 1
            if (end < text.length && text[end].isLowSurrogate()) end++
            startByte + text.substring(start, end).toByteArray(Charsets.UTF_8).size
        } else {
            // At a logical line start, measure backward from before the preceding newline.
            val end = if (text[start - 1] == '\n') start - 1 else start
            val lineStart = text.lastIndexOf('\n', end - 1) + 1
            val fit = paint.breakText(text, lineStart, end, false, 492f, null)
            var previous = (end - fit.coerceAtLeast(1)).coerceAtLeast(lineStart)
            if (text[previous].isLowSurrogate()) previous--
            startByte - text.substring(previous, start).toByteArray(Charsets.UTF_8).size
        }
        send(8, JSONObject().put("action", 1).put("did", id)
            .put("previewSync", true).put("autoSync", false)
            .put("pageOffset", target).put("highLightOffset", target).put("code", 1))
        val requested = ScriptPosition(id, target, paused = state.paused)
        // Keep newer progress reported by the glasses while the request was on the wire.
        scrollPosition.compareAndSet(lastPosition, requested)
        val actualOffset = readScrollOffset(id) ?: scrollPosition.get()
            ?.takeIf { it.id == id && it !== requested && it !== lastPosition }?.offset
        val moved = actualOffset != null && if (down) actualOffset >= target else actualOffset <= target
        val message = when {
            actualOffset == null -> "안경에 $direction 스크롤 요청을 보냈습니다"
            moved -> "안경 원고를 $direction 이동했습니다"
            else -> "안경에 이동을 요청했지만 새 위치를 확인하지 못했습니다"
        }
        return JSONObject().put("message", message)
            .put("beforeOffset", pageOffset).put("requestedOffset", target).put("actualOffset", actualOffset ?: -1)
    }

    private fun readScrollOffset(id: String): Int? {
        if (!queryScrollPosition) return null
        // No reply (timeout) is the same as a rejected readback: stop asking for this script.
        val reply = runCatching { queryPlaybackState() }.getOrNull() ?: run { queryScrollPosition = false; return null }
        val offset = reply.optInt("pageOffset", -1)
        val actualId = reply.optString("did")
        check(actualId.isBlank() || actualId == id) { "안경의 현재 원고가 바뀌었습니다" }
        if (actualId == id && offset >= 0 && reply.optInt("state") in 1..2) return offset
        queryScrollPosition = false
        return null
    }

    private fun queryPlaybackState(): JSONObject {
        val query = JSONObject().put("action", 1).put("checksum", "").put("state", JSONObject.NULL)
            .put("pageOffset", 0).put("highLightOffset", 0)
        return checkNotNull(requests.request(20, 10, query) { reply, body -> reply == 10 && body.optInt("action") == 2 })
    }

    fun control(action: String, completion: (Result<JSONObject>) -> Unit) = operation({ controlBlocking(action) }, completion)
    private fun controlBlocking(action: String): JSONObject {
        val id = checkNotNull(state.active) { "이 앱이 시작한 프롬프터가 없음" }
        val type = when (action) { "pause" -> 4; "resume" -> 5; "stop" -> 6; else -> error("프롬프터 동작은 다음 중 하나여야 함: pause/resume/stop") }
        messages.clear()
        send(type, JSONObject().put("action", 1).put("did", id).apply { if (type == 4) put("offset", 0).put("code", 1).put("isCompleted", false) })
        val actual = queryPlaybackState()
        check(!actual.has("code") || actual.optInt("code") == 1) { "안경이 프롬프터 상태 조회를 받아들이지 않음" }
        val expected = when (action) { "pause" -> 2; "resume" -> 1; else -> 3 }
        val matched = actual.optInt("state") == expected && (expected == 3 || actual.optString("did") == id)
        if (matched) {
            if (expected == 3) scrollPosition.set(null)
            // The pause readback carries where the script stopped: scrolling while paused can start from it.
            if (expected == 2) actual.optInt("pageOffset", -1).takeIf { it >= 0 }?.let { scrollPosition.set(ScriptPosition(id, it, paused = true)) }
            state = state.copy(active = if (expected == 3) null else id, paused = expected == 2,
                activeText = if (expected == 3) null else state.activeText)
        }
        return JSONObject().put("did", id).put("readBack", matched).put("deviceState", actual)
            .put("message", if (matched) "프롬프터 상태가 재조회로 확인됨" else "프롬프터 제어를 전송했지만 현재 재조회 값이 요청과 다름")
    }
    private fun send(type: Int, body: JSONObject) = requests.sendRaw(ProtocolCodec.display(20, sequence++ and 65535, type, body.toString(), byteArrayOf()))
    private fun operation(work: () -> JSONObject, completion: (Result<JSONObject>) -> Unit) {
        check(!state.busy) { "프롬프터 전송이 끝날 때까지 기다리세요" }; state = state.copy(busy = true, notice = "안경과 원고 동기화 중…"); changed()
        requests.perform(work) { result -> state = state.copy(busy = false, notice = result.fold({ it.optString("message").ifBlank { "기기에서 확인됨" } }, { it.message ?: "프롬프터 실패" })); changed(); completion(result) }
    }
    fun disconnected() { scrollPosition.set(null); linked = false; disconnected = true; task = null; glassesRequests.offer(0 to JSONObject()); transfer.offer(WireFields.Value(6)); messages.offer(0 to JSONObject())
        state = state.copy(active = null, paused = false, activeText = null, notice = "안경 연결이 끊겼습니다. 원고는 휴대폰에 저장되어 있습니다"); changed() }
}
