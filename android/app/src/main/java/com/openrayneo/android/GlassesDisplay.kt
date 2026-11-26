// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Protocol executor. It reports facts; only GlassesCoordinator chooses pages. */
internal class GlassesDisplay(
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val report: (String) -> Unit,
    private val signal: (Long, PageSignal) -> Unit,
    private val onAudio: (Long, ByteArray) -> Unit,
    private val externalPage: (String) -> Unit,
    /** A caption/prompt frame that is not part of our session, e.g. the glasses menu opening Live captions. */
    private val foreign: (protocol: Int, type: Int, body: JSONObject?) -> Unit,
) {
    /**
     * Experiment (setting "alwaysOnDual"): the always-on record takes its two-channel audio from the prompt session.
     * Once the glasses accept it, a temporary layout with is_display=false is sent to hide the page; the reply
     * (type 8) is logged with the other session signals. Unverified whether audio keeps flowing while hidden.
     */
    @Volatile var hidePrompt: () -> Boolean = { false }
    // BLE frames arrive on the GATT thread, SPP frames on main.
    private val foreignSeen = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Sessions we closed recently: their late replies are ours, not the glasses opening something. */
    private val closedSids = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private var session: PageSession? = null
    private var sid: String? = null
    private var generation = 0
    private var audioAccepted = false
    private var accepted = false
    private var settled = false
    private var writing = false
    private var failed = false
    private var navigationFrameSent = false
    private var sequence = 0x4000
    private var pending: PageContent? = null
    private var lastWritten: PageContent? = null
    private val recentAudioSequences = ArrayDeque<Long>()
    // CAPTION: one screen that is replaced, never appended; only the newest screen matters.
    private var lastScreen = ""
    private var flashcardRedraw: Runnable? = null
    private var audioReported = false
    private var unmatchedAudioReported = false
    /** Non-audio frames the glasses send inside our session (e.g. Live answers), logged until the flow is mapped. */
    private var sessionSignals = 0
    private val timeout = Runnable { fail("안경이 시작 또는 레이아웃을 확인하지 않았습니다. 다시 시도하세요") }
    private val settle = Runnable { settled = true; flush() }

    fun start(next: PageSession) {
        check(session == null) { "이전 표시 세션이 아직 닫히지 않음" }
        reset()
        validate(next.mode, next.content)
        session = next
        next.offer?.let { pending = next.content; adopt(next, it); return }
        val id = UUID.randomUUID().toString().replace("-", "")
        sid = id; pending = next.content
        val body = JSONObject().put("sid", id).put("trigger", 1).put("force", false).put("code", 0)
            .put("settings", JSONObject().put("mode", if (next.mode == PageMode.NAVIGATION || next.mode == PageMode.FLASHCARD) "translation" else "conversation")
                .put("source_language", "zh-CN").put("target_language", "zh-CN")
                .put("save_audio", false).put("direction", "ahead"))
        schedule(timeout, 8_000)
        send(protocol(next.mode), 1, body) { if (sid == id && it.isFailure) fail("표시 시작 전송 실패") }
    }

    /** The glasses opened this session: answer their start request with their sid instead of opening our own. */
    private fun adopt(next: PageSession, offer: GlassesOffer) {
        val id = offer.sid
        sid = id
        val settings = offer.settings?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val starting = generation
        send(offer.protocol, 2, JSONObject().put("sid", id).put("code", 1).put("final_settings", settings)) { result ->
            if (sid != id || generation != starting) return@send
            if (result.isFailure) { fail("안경이 연 세션에 시작 결과를 보내지 못함"); return@send }
            report("안경이 연 세션을 이어받음: 0x${offer.protocol.toString(16)} sid=${id.take(8)}")
            audioAccepted = true
            signal(next.id, PageSignal.AudioAccepted(next.mode.channels))
            if (generation != starting) return@send
            accepted = true
            signal(next.id, PageSignal.Settling)
            if (generation != starting) return@send
            schedule(settle, 500)
        }
    }

    /** Refuse a glasses start request we will not serve, so the glasses do not wait for it. */
    fun decline(protocol: Int, id: String) {
        closedSids.addLast(id); while (closedSids.size > 8) closedSids.pollFirst()
        send(protocol, 3, JSONObject().put("sid", id).put("reason_code", if (protocol == 0x13) 10 else 2).put("text", "")) {}
    }

    fun update(id: Long, content: PageContent) {
        val active = session ?: return
        if (active.id != id || failed) return
        validate(active.mode, content)
        pending = content
        flush()
    }


    fun receive(frame: ProtocolCodec.Frame, source: String) {
        if (frame.protocol == 0x14) {
            val message = ProtocolCodec.message(frame.payload)
            val json = message.json?.let(::JSONObject)
            val activity = message.type == 9 && message.audio?.isNotEmpty() == true ||
                (message.type in setOf(2, 3, 5, 8) && !json?.optString("did").isNullOrBlank() && json?.optInt("code", 1) == 1)
            if (activity) externalPage("안경 프롬프터 활동이 감지되어 표시를 양보함")
            return
        }
        if ((frame.protocol == 0x13 || frame.protocol == 0x17) && !frame.acknowledgement) {
            val message = ProtocolCodec.message(frame.payload)
            if (message.type != 4) {
                val body = runCatching { message.json?.let(::JSONObject) }.getOrNull()
                val current = session
                val frameSid = body?.optString("sid").orEmpty()
                val ours = current != null && sid != null && frame.protocol == protocol(current.mode) && frameSid == sid ||
                    frameSid.isNotEmpty() && frameSid in closedSids
                if (!ours) {
                    // The first frames of each kind go to the log: the glasses-menu flow is not captured anywhere yet.
                    val key = "${frame.protocol}:${message.type}"
                    val count = foreignSeen.merge(key, 1, Int::plus) ?: 1
                    if (count <= 5) report("안경 쪽 화면 신호 수신: 0x${frame.protocol.toString(16)} type=${message.type} ${message.json.orEmpty().take(200)}")
                    foreign(frame.protocol, message.type, body)
                }
            }
        }
        val active = session ?: return
        val receivingGeneration = generation
        if (frame.protocol != protocol(active.mode) || sid == null || failed) return
        val message = ProtocolCodec.message(frame.payload)
        val json = message.json?.let(::JSONObject) ?: return
        if (json.optString("sid") != sid) {
            if (message.type == 4 && !unmatchedAudioReported) {
                unmatchedAudioReported = true
                report("수신: $source 오디오 패킷이지만 세션이 일치하지 않아 디코더로 보내지 않음")
            }
            return
        }
        if (audioAccepted && message.type == 4) {
            if (active.mode == PageMode.FLASHCARD) return
            message.audio?.takeIf { it.isNotEmpty() }?.let { packet ->
                val number = json.optLong("seq", -1)
                if (number >= 0) {
                    if (number in recentAudioSequences) return
                    recentAudioSequences.addLast(number)
                    if (recentAudioSequences.size > 64) recentAudioSequences.removeFirst()
                }
                if (!audioReported) {
                    audioReported = true
                    report("첫 안경 오디오 패킷 수신: $source, ${packet.size} 바이트")
                }
                onAudio(active.id, packet)
            }
            return
        }
        if (message.type != 4 && message.type != 2 && sessionSignals < 10) {
            sessionSignals++
            report("안경 세션 신호: 0x${frame.protocol.toString(16)} type=${message.type} ${json.toString().take(200)}")
        }
        // 0x09 = "request received" (confirm_type); 0x0B = state sync (idle/paused...). Neither ends the session here.
        if (message.type == 9 || message.type == 11) return
        if (message.type == 3) {
            val reason = json.optInt("reason_code", -1)
            reset()
            signal(active.id, PageSignal.Ended("안경이 현재 페이지 종료 또는 일시정지를 요청함 (사유 $reason), 표시를 양보함"))
            return
        }
        if (accepted) return
        if (!audioAccepted) {
            if (message.type != 2) return
            cancel(timeout)
            if (json.optInt("code") != 1) { fail("안경이 세션 시작을 거부함: ${json.optInt("code")}"); return }
            audioAccepted = true
            if (active.mode != PageMode.FLASHCARD) signal(active.id, PageSignal.AudioAccepted(active.mode.channels))
            if (generation != receivingGeneration) return
            if (active.mode == PageMode.PROMPT && hidePrompt()) {
                val id = sid
                sessionSignals = 0
                report("실험: 2채널 종일 기록용 프롬프트 세션을 숨김 (is_display=false 요청)")
                send(0x17, 7, JSONObject().put("sid", id).put("force", false).put("scope", "temporary")
                    .put("config", JSONObject().put("is_display", false))) { if (sid == id && it.isFailure) report("실험: 숨김 요청 전송 실패") }
            }
            if (active.mode == PageMode.NAVIGATION) {
                signal(active.id, PageSignal.LayoutPending)
                if (generation != receivingGeneration) return
                val id = sid
                val config = JSONObject().put("font_size", 2).put("content_width", 100).put("max_lines", 5)
                    .put("position", "center").put("is_display", true).put("straight_view", "original")
                schedule(timeout, 8_000)
                send(0x13, 7, JSONObject().put("sid", id).put("force", false)
                    .put("scope", "temporary").put("config", config)) {
                    if (sid == id && it.isFailure) fail("내비게이션 레이아웃 설정 전송 실패")
                }
                return
            }
        } else {
            if (active.mode != PageMode.NAVIGATION || message.type != 8) return
            cancel(timeout)
            val config = json.optJSONObject("effective_config")
            if (json.optInt("code") != 1 || config?.optInt("font_size") != 2 || config.optInt("content_width") != 100 || config.optInt("max_lines") != 5) {
                fail("안경이 반환한 레이아웃이 글꼴 2／너비 100／5줄이 아니어서 내비게이션 그리드 전송을 중지함")
                return
            }
        }
        accepted = true
        signal(active.id, PageSignal.Settling)
        if (generation != receivingGeneration) return
        schedule(settle, 500)
    }

    private fun flush() {
        if (!accepted || !settled || writing || failed) return
        val content = pending ?: return
        val id = sid ?: return
        val active = session ?: return
        if (active.mode == PageMode.CAPTION || active.mode == PageMode.FLASHCARD) { flushCaption(active, id, content); return }
        pending = null
        if (content == lastWritten) return
        writing = true
        val text = (if (active.mode == PageMode.NAVIGATION && navigationFrameSent) "\u3000" else "") + content.text
        val payload = if (active.mode == PageMode.NAVIGATION) NavigationFrame.result(id, text)
            else JSONObject().put("sid", id).put("mode", 4).put("status", 1).put("content", JSONObject()
                .put("source_transcript", text).put("target_translation", content.answer.orEmpty()).put("keyword_info", JSONObject.NULL).put("label", 0))
        send(protocol(active.mode), 5, payload) { result ->
            if (sid != id) return@send
            writing = false
            if (result.isSuccess) {
                lastWritten = content
                if (active.mode == PageMode.NAVIGATION) navigationFrameSent = true
                signal(active.id, PageSignal.Written(content))
                flush()
            } else fail("표시 업데이트 실패: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * The glasses' caption page appends finals (status 1) and replaces the open partial (status 0). Every screen goes out
     * as a partial so it replaces the last one, and in transcription mode 3, so the translation is part of the text:
     * mode 1 (classic bilingual) stacks lines and shows source or translation depending on head pitch.
     */
    private fun flushCaption(active: PageSession, id: String, content: PageContent) {
        pending = null
        val screen = if (active.mode == PageMode.FLASHCARD)
            content.text + "\n" + content.answer.orEmpty().lineSequence().joinToString(" ") { it.trim() }
        else captionScreen(content.text, content.answer.orEmpty())
        if (screen.isBlank() || screen == lastScreen) { lastWritten = content; signal(active.id, PageSignal.Written(content)); return }
        lastScreen = screen
        // Only a different screen replaces the pending first-screen repeat; an identical update keeps it.
        flashcardRedraw?.let(cancel); flashcardRedraw = null
        writing = true
        val firstFlashcard = active.mode == PageMode.FLASHCARD && lastWritten == null
        val payload = JSONObject().put("sid", id).put("mode", 3).put("status", 0)
            .put("content", JSONObject().put("source_transcript", screen).put("keyword_info", "").put("label", 0))
        send(0x13, 5, payload) { result ->
            if (sid != id) return@send
            writing = false
            if (result.isFailure) { fail("자막 전송 실패: ${result.exceptionOrNull()?.message}"); return@send }
            lastWritten = content
            if (firstFlashcard && pending == null) {
                // A successful transport write can precede the glasses' first render.
                // Repeat the initial partial once after startup; a different screen cancels it.
                flashcardRedraw = Runnable {
                    flashcardRedraw = null
                    if (sid == id && !failed) send(0x13, 5, payload) { redraw ->
                        if (sid == id && redraw.isFailure) report("Anki 첫 화면 재전송 실패: ${redraw.exceptionOrNull()?.message}")
                    }
                }.also { schedule(it, 750) }
            }
            signal(active.id, PageSignal.Written(content))
            flush()
        }
    }

    fun stop(token: Long) {
        val active = session; val id = sid
        if (active == null || active.id != token || id == null) { signal(token, PageSignal.Closed()); return }
        reset()
        val closingGeneration = generation
        send(protocol(active.mode), 3, JSONObject().put("sid", id)
            .put("reason_code", if (active.mode == PageMode.PROMPT) 2 else 10).put("text", "")) { result ->
            if (generation == closingGeneration) signal(token, PageSignal.Closed(result.exceptionOrNull()?.let { "닫기 명령 전송 실패: ${it.message}" }))
        }
    }
    private fun fail(message: String) {
        val active = session ?: return
        if (failed) return
        failed = true; cancel(timeout); cancel(settle)
        signal(active.id, PageSignal.Failed(message))
    }
    fun reset() {
        generation++
        cancel(timeout); cancel(settle); sessionSignals = 0
        flashcardRedraw?.let(cancel)
        flashcardRedraw = null
        sid?.let { closedSids.addLast(it); while (closedSids.size > 8) closedSids.pollFirst() }
        session = null; sid = null; audioAccepted = false; accepted = false; settled = false; writing = false; failed = false
        navigationFrameSent = false; pending = null; lastWritten = null; lastScreen = ""
        recentAudioSequences.clear(); audioReported = false; unmatchedAudioReported = false
    }
    private fun protocol(mode: PageMode) = mode.protocol
    private fun validate(mode: PageMode, content: PageContent) {
        if (mode == PageMode.NAVIGATION) require(NavigationFrame.isGrid(content.text)) { "내비게이션 화면은 20열 × 5행을 유지해야 함" }
    }
    private fun send(protocol: Int, type: Int, json: JSONObject, completion: (Result<Unit>) -> Unit) {
        try { write(ProtocolCodec.display(protocol, sequence++ and 65535, type, json.toString()), completion) }
        catch (error: Exception) { completion(Result.failure(error)) }
    }
}

internal object NavigationFrame {
    // A maneuver is always a complete frame. Partial ASR results must not share this path.
    fun result(sid: String, text: String) = JSONObject().put("sid", sid).put("mode", 3).put("status", 1)
        .put("content", JSONObject().put("source_transcript", text))
    fun isGrid(text: String): Boolean = text.split('\n').let { it.size == 5 && it.all { row -> row.length == 20 } }
    private fun grid(): Array<CharArray> = Array(5) { CharArray(20) { '　' } }
    private fun put(rows: Array<CharArray>, text: String, x: Int, y: Int) {
        text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(20 - x).forEachIndexed { i, c ->
            rows[y][x + i] = when (c) { ' ' -> '　'; in '!'..'~' -> (c.code + 0xfee0).toChar(); else -> c }
        }
    }
    private fun text(rows: Array<CharArray>) = rows.joinToString("\n") { String(it) }
    fun status(title: String, detail: String = ""): String = grid().let {
        put(it, title, 2, 1); put(it, detail, 2, 3); text(it)
    }
    fun render(direction: String, meters: Int, roadLabel: String = ""): String {
        val left = listOf("  █    ", " █     ", "██████ ", " █   █ ", "  █  █ ")
        val arrow = when (direction) {
            "좌회전", "좌전방", "좌후방" -> left
            "우회전", "우전방", "우후방" -> left.map { it.reversed() }
            "유턴" -> listOf("  ████ ", " █   █ ", " █   █ ", "███  █ ", " █   █ ")
            "직진" -> listOf("   █   ", "  ███  ", " █████ ", "   █   ", "   █   ")
            else -> List(5) { "       " }
        }
        val rows = grid()
        arrow.forEachIndexed { i, row -> put(rows, row, 2, i) }
        put(rows, if (meters <= 0) "지금" else "${meters.coerceAtMost(9999)}m", 12, 1)
        put(rows, direction, 12, 3)
        put(rows, roadLabel.trim(), 12, 4)
        return text(rows)
    }
}
