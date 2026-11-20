// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.text.Html
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * AnkiDroid's public content provider (FlashCardsContract): decks, the next due card and answering it.
 * AnkiDroid keeps the collection and computes the schedule; this app only shows cards and passes the rating back.
 */
internal object AnkiDroid {
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private val base = Uri.parse("content://com.ichi2.anki.flashcards")
    data class Deck(val id: Long, val name: String, val learn: Int, val review: Int, val new: Int) { val due get() = learn + review + new }
    data class Card(val noteId: Long, val ord: Int, val question: String, val answer: String, val buttons: Int, val nextTimes: List<String>)

    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
    fun allowed(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun decks(context: Context): List<Deck> = query(context, base.buildUpon().appendPath("decks").build()) { c ->
        val counts = c.text("deck_count").let { runCatching { JSONArray(it) }.getOrNull() }
        Deck(c.long("deck_id"), c.text("deck_name"), counts?.optInt(0) ?: 0, counts?.optInt(1) ?: 0, counts?.optInt(2) ?: 0)
    }.sortedBy { it.name.lowercase() }

    /** The next card AnkiDroid would show for [deckId], or null when nothing is due. */
    fun next(context: Context, deckId: Long): Card? {
        val review = query(context, base.buildUpon().appendPath("schedule").build(), "limit=?,deckID=?", arrayOf("1", deckId.toString())) { c ->
            ReviewRow(c.long("note_id"), c.int("ord", "card_ord"), c.int("button_count"),
                runCatching { JSONArray(c.text("next_review_times")) }.getOrNull()?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty())
        }.firstOrNull() ?: return null
        val uri = base.buildUpon().appendPath("notes").appendPath(review.note.toString()).appendPath("cards").appendPath(review.ord.toString()).build()
        val texts = query(context, uri) { c ->
            val question = c.optional("question_simple") ?: c.text("question")
            // answer_pure has the back only; the full answer repeats the front above <hr id=answer>.
            val answer = c.optional("answer_pure") ?: (c.optional("answer_simple") ?: c.text("answer")).let { full ->
                full.substringAfter("<hr id=answer>", full) }
            question to answer
        }.single()
        return Card(review.note, review.ord, plain(texts.first), plain(texts.second), review.buttons.coerceIn(2, 4), review.times)
    }

    /** Ease 1 again … 4 easy (a 3-button card has no "hard": the provider maps by its own count). */
    fun answer(context: Context, card: Card, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put("note_id", card.noteId); put("ord", card.ord); put("answer_ease", ease); put("time_taken", timeTakenMs)
        }
        val updated = context.contentResolver.update(base.buildUpon().appendPath("schedule").build(), values, null, null)
        check(updated > 0) { "AnkiDroid가 답을 저장하지 않았습니다" }
    }

    private data class ReviewRow(val note: Long, val ord: Int, val buttons: Int, val times: List<String>)
    private fun <T> query(context: Context, uri: Uri, selection: String? = null, args: Array<String>? = null, row: (Cursor) -> T): List<T> {
        check(installed(context)) { "AnkiDroid가 설치되어 있지 않습니다" }
        check(allowed(context)) { "AnkiDroid 접근 권한을 허용하세요" }
        val cursor = checkNotNull(context.contentResolver.query(uri, null, selection, args, null)) {
            "AnkiDroid가 응답하지 않았습니다. AnkiDroid 설정 → 고급 → 'AnkiDroid API 사용'이 켜져 있는지 확인하세요"
        }
        return cursor.use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }
    private fun Cursor.index(vararg names: String) = names.map(::getColumnIndex).firstOrNull { it >= 0 }
        ?: error("AnkiDroid 응답에 ${names.first()} 열이 없습니다(열: ${columnNames.joinToString()})")
    private fun Cursor.text(vararg names: String) = getString(index(*names)).orEmpty()
    private fun Cursor.long(vararg names: String) = getLong(index(*names))
    private fun Cursor.int(vararg names: String) = getInt(index(*names))
    private fun Cursor.optional(name: String) = getColumnIndex(name).takeIf { it >= 0 }?.let(::getString)?.takeIf { it.isNotBlank() }
    /** Card HTML to glasses text: no tags, sound or style blocks, collapsed blank lines. */
    fun plain(html: String): String = Html.fromHtml(html.replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), "")
        .replace(Regex("\\[sound:[^]]*]"), ""), Html.FROM_HTML_MODE_COMPACT).toString()
        .replace('￼', ' ').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

internal data class AnkiState(val decks: List<AnkiDroid.Deck> = emptyList(), val deck: AnkiDroid.Deck? = null,
    val card: AnkiDroid.Card? = null, val flipped: Boolean = false, val reviewed: Int = 0, val busy: Boolean = false, val notice: String = "")

/** A review session: the card goes to the glasses' answer page and the phone screen; ratings go back to AnkiDroid. Main thread. */
internal class AnkiReview(private val context: Context, private val main: Handler, private val changed: () -> Unit,
    private val show: (String, String, Boolean) -> Unit, private val clear: () -> Unit) {
    var state = AnkiState(); private set
    private val io = Executors.newSingleThreadExecutor()
    private var shownAt = 0L
    /** What the glasses show now; rewritten every few seconds because the glasses hide an unchanged answer page after about 8 s. */
    private var shown: Pair<String, String>? = null
    private val keepAlive = object : Runnable {
        override fun run() { shown?.let { (title, body) -> show(title, body, true); main.postDelayed(this, KEEP_ALIVE_MS) } }
    }
    private fun display(title: String, body: String) {
        shown = title to body; show(title, body, false)
        main.removeCallbacks(keepAlive); main.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }
    private fun hide() { shown = null; main.removeCallbacks(keepAlive); clear() }
    /** Title and body of the card on the glasses, for "show again". */
    val shownCard get() = shown
    /** Bumped by [stop]: results of work started before it are dropped. */
    private var session = 0
    private fun work(block: () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, notice = ""); changed()
        io.execute { runCatching(block).onFailure { error -> main.post { state = state.copy(busy = false, notice = error.message ?: "AnkiDroid 작업 실패"); changed() } } }
    }
    fun loadDecks() = work {
        val decks = AnkiDroid.decks(context)
        main.post { state = state.copy(decks = decks, busy = false, notice = if (decks.isEmpty()) "AnkiDroid에 덱이 없습니다" else ""); changed() }
    }
    fun start(deck: AnkiDroid.Deck) { val token = session; work { val card = AnkiDroid.next(context, deck.id); main.post { if (token == session) present(deck, card) } } }
    fun flip() {
        val card = state.card ?: return
        if (state.flipped) return
        // The front stays on screen above the answer, as on a paper card.
        state = state.copy(flipped = true); display("Anki · ${state.deck?.name.orEmpty()}", card.question + "\n----\n" + card.answer); changed()
    }
    fun grade(ease: Int) {
        val card = state.card ?: return; val deck = state.deck ?: return
        require(state.flipped) { "먼저 뒷면을 보세요" }
        val taken = (System.currentTimeMillis() - shownAt).coerceIn(0, 60_000); val token = session
        work {
            AnkiDroid.answer(context, card, ease, taken)
            val next = AnkiDroid.next(context, deck.id)
            main.post { if (token == session) { state = state.copy(reviewed = state.reviewed + 1); present(deck, next) } }
        }
    }
    fun stop() {
        session++
        if (state.card != null) hide()
        // Work still running for the old session finishes in the background; its result is dropped.
        state = state.copy(deck = null, card = null, flipped = false, reviewed = 0, busy = false); changed()
        loadDecks()
    }
    private fun present(deck: AnkiDroid.Deck, card: AnkiDroid.Card?) {
        shownAt = System.currentTimeMillis()
        state = state.copy(deck = deck, card = card, flipped = false, busy = false,
            notice = if (card == null) "이 덱에서 지금 복습할 카드가 없습니다" + if (state.reviewed > 0) " (${state.reviewed}장 복습함)" else "" else "")
        if (card != null) display("Anki · ${deck.name}", card.question) else hide()
        changed()
    }
    fun close() { main.removeCallbacks(keepAlive); io.shutdownNow() }
    private companion object { const val KEEP_ALIVE_MS = 5_000L }
}
