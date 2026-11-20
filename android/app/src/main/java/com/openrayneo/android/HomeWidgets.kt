// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal data class HomeWidgetsState(
    val stocks: List<JSONObject> = emptyList(), val stockResults: List<JSONObject> = emptyList(),
    val clock: JSONObject? = null, val clockResults: List<JSONObject> = emptyList(),
    val userName: String = "", val notice: String = "", val mail: String = "")

/**
 * Stock, world clock and mail widgets and the user name, in the official app's formats (snoop capture 2026-11-20):
 * widget 4 `stock_update`, 5 `mail_update`, 6 `clock_update`, plus `sync_user_name` (0x0f type 78, reply 79).
 * Settings live on the phone; data is pushed on connect, on change, when the widget is added and on a timer.
 */
internal class HomeWidgets(context: Context, private val content: CompanionContent, private val requests: GlassesRequests,
    private val address: () -> String?, private val changed: () -> Unit) {
    @Volatile var state = HomeWidgetsState(); private set
    @Volatile var log: (String) -> Unit = {}
    private val local = context.getSharedPreferences("home-widgets", Context.MODE_PRIVATE)
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var stocksAt = 0L; @Volatile private var mailVersion = -1

    init {
        state = HomeWidgetsState(stocks = JSONArray(local.getString("stocks", "[]")).objects(),
            clock = local.getString("clock", null)?.let(::JSONObject), userName = local.getString("userName", "").orEmpty())
    }
    private fun update(change: (HomeWidgetsState) -> HomeWidgetsState) {
        synchronized(this) { state = change(state) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) changed() else ui.post(changed)
    }
    private fun widgets(): List<Int> = content.state.dashboard?.optJSONArray("widgets").ints()

    /** Main thread, on connect: name and every added widget's data. */
    fun connected() {
        stocksAt = 0; mailVersion = -1
        background("홈 위젯 보내기") {
            // Each step on its own: one failure (no name ack, stocks offline) must not drop the others.
            if (state.userName.isNotBlank()) step("이름 보내기") { syncUserName() }
            step("홈 화면 읽기") { content.dashboard() }
            if (6 in widgets()) step("세계 시계 보내기") { pushClock() }
            if (5 in widgets()) step("메일 보내기") { pushMail() }
            if (4 in widgets()) step("주식 보내기") { pushStocks() }
        }
    }
    /** Main thread, every 30 s: stocks every 10 minutes, mail when Gmail notifications changed. */
    fun tick() {
        if (address() == null || running.get() > 0) return
        val list = widgets()
        val stocks = 4 in list && state.stocks.isNotEmpty() && System.currentTimeMillis() - stocksAt > 10 * 60_000
        val mail = 5 in list && MailFeed.version != mailVersion
        if (stocks || mail) background("홈 위젯 업데이트") { if (stocks) step("주식 보내기") { pushStocks() }; if (mail) step("메일 보내기") { pushMail() } }
    }
    /** Called by the layout change for newly added widgets. Glasses thread; failures are logged, the layout stands. */
    fun added(ids: List<Int>) {
        if (4 in ids) step("주식 보내기") { pushStocks() }
        if (5 in ids) step("메일 보내기") { pushMail() }
        if (6 in ids) step("세계 시계 보내기") { pushClock() }
    }
    private fun step(name: String, work: () -> Unit) { runCatching(work).onFailure { log("$name 실패: ${it.message}") } }
    /** Launcher messages from the glasses (0x0f type 19): the stock widget asks for fresh prices. */
    fun observe(type: Int, body: JSONObject) {
        if (type == 19 && body.optString("cmd") == "stock_request" && address() != null) background("주식 새로 고침") { pushStocks() }
    }
    private val running = java.util.concurrent.atomic.AtomicInteger()
    fun background(name: String, work: () -> Unit) {
        running.incrementAndGet()
        content.execute({ work(); JSONObject() }) { result ->
            running.decrementAndGet()
            val failure = result.exceptionOrNull()
            failure?.let { log("$name 실패: ${it.message}") }
            update { s -> s.copy(notice = failure?.let { "$name 실패: ${it.message}" }.orEmpty()) }
        }
    }

    // Stocks: prices from Yahoo Finance (public, no key, may be delayed). Yahoo search rejects Korean text (HTTP 400),
    // so Korean names go through Naver's stock autocomplete and are mapped to Yahoo symbols.
    fun searchStocks(query: String): JSONObject {
        val text = query.trim()
        val rows = if (text.any { it.code > 0x7f }) naverStocks(text) else yahooStocks(text)
        update { it.copy(stockResults = rows) }
        return JSONObject().put("results", JSONArray(rows)).put("message", if (rows.isEmpty()) "찾은 종목이 없습니다" else "종목 ${rows.size}개를 찾았습니다")
    }
    private fun yahooStocks(query: String): List<JSONObject> {
        val url = "https://query2.finance.yahoo.com/v1/finance/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("quotesCount", "10").addQueryParameter("newsCount", "0").build().toString()
        return get(url).optJSONArray("quotes").objects().filter { it.optString("quoteType") in setOf("EQUITY", "ETF", "INDEX") }.map {
            JSONObject().put("symbol", it.getString("symbol")).put("name", it.optString("longname").ifBlank { it.optString("shortname") })
                .put("exchange", it.optString("exchDisp").ifBlank { it.optString("exchange") })
        }
    }
    private fun naverStocks(query: String): List<JSONObject> {
        val url = "https://ac.stock.naver.com/ac".toHttpUrl().newBuilder().addQueryParameter("q", query)
            .addQueryParameter("target", "stock,index").build().toString()
        return get(url).optJSONArray("items").objects().mapNotNull { item ->
            val code = item.optString("code")
            val symbol = when {
                item.optString("category") == "index" -> NAVER_INDEXES[code]
                item.optString("nationCode") == "KOR" -> when (item.optString("typeCode")) { "KOSPI" -> "$code.KS"; "KOSDAQ" -> "$code.KQ"; else -> null }
                item.optString("nationCode") == "USA" -> code.replace('.', '-')
                item.optString("typeCode") == "TOKYO" -> "$code.T"
                else -> null
            } ?: return@mapNotNull null
            JSONObject().put("symbol", symbol).put("name", item.optString("name")).put("exchange", item.optString("typeName"))
        }.take(10)
    }
    fun addStock(row: JSONObject) {
        check(state.stocks.size < 6) { "주식은 6개까지 추가할 수 있습니다" }
        if (state.stocks.any { it.getString("symbol") == row.getString("symbol") }) return
        saveStocks(state.stocks + row)
    }
    fun removeStock(symbol: String) = saveStocks(state.stocks.filter { it.getString("symbol") != symbol })
    private fun saveStocks(list: List<JSONObject>) {
        check(local.edit().putString("stocks", JSONArray(list).toString()).commit())
        update { it.copy(stocks = list, stockResults = emptyList()) }; stocksAt = 0; tick()
    }
    fun pushStocks(): JSONObject {
        // Counted before fetching: a failing source is retried on the next 10-minute turn, not every tick.
        stocksAt = System.currentTimeMillis()
        val rows = state.stocks.map { stock -> quote(stock) }
        content.launcher("stock_update", JSONArray(rows).toString())
        log("주식 ${rows.size}개를 안경에 보냈습니다")
        return JSONObject().put("stocks", JSONArray(rows))
    }
    private fun quote(stock: JSONObject): JSONObject {
        val symbol = stock.getString("symbol")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/".toHttpUrl().newBuilder().addPathSegment(symbol)
            .addQueryParameter("range", "1d").addQueryParameter("interval", "1d").build().toString()
        val result = get(url).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        val previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
        val open = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("open")?.optDouble(0)?.takeIf { !it.isNaN() } ?: price
        val period = meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        val now = System.currentTimeMillis() / 1000
        val trading = period != null && now in period.optLong("start")..period.optLong("end")
        fun round(value: Double) = Math.round(value * 100) / 100.0
        val whole = !symbol.startsWith("^") && meta.optString("currency") in setOf("KRW", "JPY")
        return JSONObject().put("code", symbol.substringBefore('.')).put("exchange", stock.optString("exchange")).put("micCode", "")
            .put("name", stock.optString("name")).put("percent", round(if (previous == 0.0) 0.0 else (price - previous) / previous * 100))
            .put("open", round(open)).put("realtime", round(price)).put("realtime_str", if (whole) String.format(Locale.ROOT, "%.0f", price) else String.format(Locale.ROOT, "%.2f", price))
            .put("name_reverse", false).put("is_open", trading).put("total", 0).put("last_close", round(previous)).put("quarter", JSONArray())
    }

    // World clock: one city, like the official app (max 1).
    fun searchClock(query: String): JSONObject {
        val text = query.trim()
        val named = CITIES.filter { (name, _) -> name.contains(text) }.map { (name, zone) -> name to zone }
        val zones = ZoneId.getAvailableZoneIds().filter { id -> id.contains('/') && !id.startsWith("Etc") &&
            (id.contains(text.replace(' ', '_'), ignoreCase = true) || TimeZone.getTimeZone(id).getDisplayName(Locale.KOREAN).contains(text)) }
            .sorted().take(20).map { id -> id.substringAfterLast('/').replace('_', ' ') to id }
        val rows = (named + zones).distinctBy { it.second }.take(20).map { (name, zone) -> city(name, zone) }
        update { it.copy(clockResults = rows) }
        return JSONObject().put("results", JSONArray(rows)).put("message", if (rows.isEmpty()) "찾은 도시가 없습니다" else "도시 ${rows.size}개를 찾았습니다")
    }
    private fun city(name: String, zone: String) = JSONObject().put("location", name).put("location_id", zone).put("timezone", zone)
    fun setClock(row: JSONObject?) {
        check(local.edit().apply { if (row == null) remove("clock") else putString("clock", row.toString()) }.commit())
        update { it.copy(clock = row, clockResults = emptyList()) }
        if (address() != null && 6 in widgets()) background("세계 시계 보내기") { pushClock() }
    }
    fun pushClock(): JSONObject {
        val chosen = state.clock ?: city("현지 시간", ZoneId.systemDefault().id)
        val zone = ZoneId.of(chosen.getString("timezone"))
        val row = JSONObject(chosen.toString()).put("offset", ZonedDateTime.now(zone).offset.totalSeconds)
        content.launcher("clock_update", JSONArray().put(row).toString())
        return JSONObject().put("clock", row)
    }

    // Mail: the newest Gmail notification on the phone (no Google sign-in); unread = Gmail notifications showing.
    fun pushMail(): JSONObject {
        val version = MailFeed.version; val mails = MailFeed.snapshot(); mailVersion = version
        val format = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
        val rows = mails.take(1).map { mail ->
            JSONObject().put("sender", mail.account.ifBlank { mail.sender }).put("sender_name", mail.sender).put("title", mail.subject).put("preview", mail.preview)
                .put("time", Instant.ofEpochMilli(mail.postedAt).atZone(ZoneId.systemDefault()).format(format))
        }
        content.launcher("mail_update", JSONArray().put(JSONObject().put("provider", "gmail").put("provider_id", "1")
            .put("unread", mails.size).put("mails", JSONArray(rows))).toString())
        val summary = if (mails.isEmpty()) "휴대폰에 떠 있는 Gmail 알림이 없어 빈 메일함을 보냈습니다"
            else "Gmail 알림 ${mails.size}개 중 최신 메일을 보냈습니다: ${mails.first().sender} · ${mails.first().subject}"
        log(summary); update { it.copy(mail = summary) }
        return JSONObject().put("unread", mails.size)
    }

    // User name shown by the glasses (official app sends it on every connect).
    fun setUserName(name: String) {
        check(local.edit().putString("userName", name.trim()).commit())
        update { it.copy(userName = name.trim()) }
        if (address() != null && name.isNotBlank()) background("이름 보내기") { syncUserName() }
    }
    private fun syncUserName() {
        requests.request(15, 78, JSONObject().put("cmd", "sync_user_name").put("payload", JSONObject().put("value", 0).put("mode", 0).put("data", state.userName))) { type, body ->
            type == 79 && body.optString("cmd") == "sync_user_name_ack" }
    }

    private fun get(url: String): JSONObject = http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { response ->
        check(response.isSuccessful) { "요청 실패: HTTP ${response.code}" }; JSONObject(checkNotNull(response.body).string())
    }
    fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }

    private companion object {
        val CITIES = listOf("서울" to "Asia/Seoul", "도쿄" to "Asia/Tokyo", "베이징" to "Asia/Shanghai", "상하이" to "Asia/Shanghai",
            "홍콩" to "Asia/Hong_Kong", "타이베이" to "Asia/Taipei", "싱가포르" to "Asia/Singapore", "방콕" to "Asia/Bangkok",
            "하노이" to "Asia/Ho_Chi_Minh", "호찌민" to "Asia/Ho_Chi_Minh", "자카르타" to "Asia/Jakarta", "마닐라" to "Asia/Manila",
            "뉴델리" to "Asia/Kolkata", "두바이" to "Asia/Dubai", "이스탄불" to "Europe/Istanbul", "모스크바" to "Europe/Moscow",
            "런던" to "Europe/London", "파리" to "Europe/Paris", "베를린" to "Europe/Berlin", "프랑크푸르트" to "Europe/Berlin",
            "로마" to "Europe/Rome", "마드리드" to "Europe/Madrid", "암스테르담" to "Europe/Amsterdam", "카이로" to "Africa/Cairo",
            "뉴욕" to "America/New_York", "워싱턴" to "America/New_York", "토론토" to "America/Toronto", "시카고" to "America/Chicago",
            "덴버" to "America/Denver", "로스앤젤레스" to "America/Los_Angeles", "샌프란시스코" to "America/Los_Angeles",
            "시애틀" to "America/Los_Angeles", "밴쿠버" to "America/Vancouver", "호놀룰루" to "Pacific/Honolulu",
            "멕시코시티" to "America/Mexico_City", "상파울루" to "America/Sao_Paulo", "시드니" to "Australia/Sydney",
            "멜버른" to "Australia/Melbourne", "오클랜드" to "Pacific/Auckland")
        val NAVER_INDEXES = mapOf("KOSPI" to "^KS11", "KOSDAQ" to "^KQ11", "KPI200" to "^KS200")
    }
}
