// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CompanionContentState(val dashboard: JSONObject? = null, val todos: List<JSONObject> = emptyList(),
    val schedules: List<JSONObject> = emptyList(), val busy: Boolean = false, val notice: String = "연결 후 안경 콘텐츠 읽기",
    val weatherCities: List<JSONObject> = emptyList(), val weather: JSONObject? = null, val automaticWeather: Boolean = false,
    /** Phone-side state below survives disconnects: to-dos live on the phone, the calendar is the phone's. */
    val todoNotice: String = "", val calendarAllowed: Boolean = false, val calendars: List<PhoneCalendar.Source> = emptyList(),
    /** Chosen calendar ids; null = every visible calendar. */
    val calendarSelection: Set<Long>? = null, val calendarEvents: List<PhoneCalendar.Event> = emptyList(),
    val calendarToGlasses: Boolean = true, val calendarNotice: String = "")

/** Device-backed content shared by manual pages, model tools and the local Agent API. */
internal class CompanionContent(private val context: Context, private val requests: GlassesRequests,
    private val address: () -> String?, private val changed: () -> Unit) {
    @Volatile var state = CompanionContentState(); private set
    /** App log (any thread): calendar and to-do sync outcomes with the glasses. */
    @Volatile var log: (String) -> Unit = {}
    /** Glasses thread: newly added stock, mail and world clock widgets get their data (HomeWidgets). */
    @Volatile var widgetsAdded: (List<Int>) -> Unit = {}
    private val stateLock = Any()
    /** Both the main and the glasses thread write the state: copy-and-replace under one lock so no update is lost. */
    private fun update(change: (CompanionContentState) -> CompanionContentState) { synchronized(stateLock) { state = change(state) } }
    private val phoneLocation = PhoneLocation(context)
    private val local = context.getSharedPreferences("device-content", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private fun device() = checkNotNull(address()) { "먼저 안경을 완전히 연결하세요" }
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    /** UI observers run on the main thread; the glasses thread hands its updates over. */
    private fun publish() { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) changed() else ui.post(changed) }
    private var weatherAttemptAt = 0L
    fun connected() {
        val id = device()
        update { it.copy(automaticWeather = local.getBoolean("weatherAutomatic:$id", false),
            weather = local.getString("weatherCard:$id", null)?.let(::JSONObject)) }; changed()
        automaticWeather()
        restoreLayout(id)
        syncTodosLater()
        calendarChanged = true; calendarTick()
        // Without calendar access no sync runs; still tell the glasses, as the official app does on every refresh.
        if (!state.calendarAllowed) execute({ pushCalendarAuth(); JSONObject() }) {}
    }
    fun setAutomaticWeather(enabled: Boolean) {
        check(local.edit().putBoolean("weatherAutomatic:" + device(), enabled).commit())
        update { it.copy(automaticWeather = enabled) }; changed(); if (enabled) automaticWeather(force = true)
    }
    fun automaticWeather(force: Boolean = false) {
        if (address() == null || !state.automaticWeather || state.busy || !force && System.currentTimeMillis() - weatherAttemptAt < 30 * 60_000) return
        val city = local.getString("weatherCity:" + device(), null)?.let(::JSONObject) ?: return
        weatherAttemptAt = System.currentTimeMillis()
        // A "current location" choice follows the phone: locate again on every update.
        run { weather(if (city.optBoolean("current")) currentCity() else city); pushWeather() }
    }
    fun refresh() = run { dashboard(); events(1); events(0); JSONObject().put("readBack", true) }
    fun disconnected() {
        weatherAttemptAt = 0; calendarSyncQueued = false; todoSyncQueued.set(false)
        update { it.copy(dashboard = null, schedules = emptyList(), busy = false, notice = CompanionContentState().notice,
            weatherCities = emptyList(), weather = null, automaticWeather = false) }; changed()
    }
    fun run(work: () -> JSONObject) {
        check(!state.busy) { "현재 안경 작업을 기다리세요" }
        update { it.copy(busy = true, notice = "안경과 동기화 중…") }; changed()
        execute(work) { result -> update { it.copy(busy = false, notice = result.fold({ it.optString("message").ifBlank { if (it.optBoolean("readBack")) "기기 재조회로 확인됨" else "전송됨, 자세한 상태는 결과 참조" } }, { it.message ?: "작업 실패" })) }; changed() }
    }
    fun execute(work: () -> JSONObject, completion: (Result<JSONObject>) -> Unit) = requests.perform(work, completion)
    fun execute(name: String, args: JSONObject, completion: (Result<JSONObject>) -> Unit) = execute({ tool(name, args) }, completion)

    fun dashboard(): JSONObject {
        val response = launcher("dashboard_config", "", "0")
        val raw = response.getJSONObject("payload").get("data")
        val config = if (raw is JSONObject) raw else JSONObject(raw.toString())
        update { it.copy(dashboard = config) }; return config
    }
    /**
     * The glasses fall back to their default home (to-do only) when another app has been connected (seen 2026-11-20:
     * the official app's five widgets were gone after OpenRayneo reconnected). Put back the layout last set here.
     */
    private fun restoreLayout(deviceId: String) {
        val saved = local.getString("layout:$deviceId", null)?.let(::JSONObject) ?: return
        execute({
            val current = dashboard()
            val widgets = saved.getJSONArray("widgets").ints(); val mode = saved.getInt("simple_mode")
            if (current.optInt("simple_mode") == mode && current.optJSONArray("widgets").ints() == widgets) JSONObject()
            else layout(mode, widgets).also { log("안경 홈 위젯을 휴대폰에 저장된 구성으로 되돌림: ${it.optString("message")}") }
        }) { result -> result.exceptionOrNull()?.let { log("안경 홈 위젯 되돌리기 실패: ${it.message}") } }
    }
    fun layout(mode: Int, widgets: List<Int>): JSONObject {
        require(mode in 0..2 && widgets.size in 1..5 && widgets.distinct().size == widgets.size)
        val previous = dashboard()
        launcher("dashboard_update", JSONObject().put("simple_mode", mode).put("widgets", JSONArray(widgets)).toString())
        val added = widgets.filter { id -> id !in previous.optJSONArray("widgets").ints() }
        if (1 in added && state.weather != null) pushWeather()
        runCatching { widgetsAdded(added) }.onFailure { log("새 위젯 데이터 보내기 실패: ${it.message}") }
        // Adding the schedule widget: the official app sends the permission state, then the schedule batch.
        // A failure here must not turn the applied layout into an error; the tick sends the calendar again.
        if (3 in added) runCatching { if (state.calendarAllowed && state.calendarToGlasses) syncCalendar() else pushCalendarAuth() }
            .onFailure { log("일정 위젯 추가 후 일정 보내기 실패: ${it.message}"); ui.post { calendarChanged = true } }
        val actual = dashboard()
        val same = actual.optInt("simple_mode") == mode && actual.optJSONArray("widgets").ints() == widgets
        if (same) address()?.let { id -> local.edit().putString("layout:$id", JSONObject().put("simple_mode", mode).put("widgets", JSONArray(widgets)).toString()).apply() }
        return JSONObject().put("readBack", same).put("config", actual).put("message", if (same) "홈 레이아웃이 재조회로 확인됨" else "기기 레이아웃이 요청과 다릅니다. 위젯 콘텐츠가 설정되었는지 확인하세요")
    }
    fun events(eventType: Int): List<JSONObject> {
        val rows = mutableListOf<JSONObject>(); var last: JSONObject? = null
        requests.request(22, 15, JSONObject().put("queryType", 0).put("eventType", eventType)
            .put("lastSyncTime", 0).put("eventIDList", JSONArray()).put("needFullData", true)) { type, body ->
            if (type != 16 || !body.has("dataList")) false else {
                rows += body.getJSONArray("dataList").objects().filter { it.optInt("eventType", -1) == eventType }
                if (body.optBoolean("isLastBatch")) { last = body; true } else false
            }
        }
        val response = checkNotNull(last)
        check(response.optBoolean("needFullData") && response.getInt(if (eventType == 1) "todoTotal" else "scheduleTotal") == rows.size &&
            rows.map { it.getLong("eventID") }.distinct().size == rows.size) { "기기 목록이 완전히 반환되지 않아 변경하지 않았습니다" }
        if (eventType == 0) update { it.copy(schedules = rows) }
        return rows
    }
    // ---- To-dos: the phone keeps the list; when connected the glasses mirror its open items. ----
    // Item fields besides the wire ones: rev (bumped on every phone edit), synced (rev the glasses confirmed),
    // reopened (the phone un-checked it), deleted (tombstone until the glasses drop it), everOnGlasses.
    private val todoLock = Any()
    private val todoSyncQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun storedTodos(): MutableList<JSONObject> {
        local.getString(TODOS, null)?.let { return JSONArray(it).objects().toMutableList() }
        // First run after the phone became the owner: take over the per-glasses caches of the old version,
        // so their IDs stay reserved and nothing on those glasses gets overwritten.
        val caches = local.all.filterKeys { it.startsWith("todos:") && it != TODOS }.mapValues { JSONArray(it.value as String).objects() }
        val migrated = caches.values.flatten().distinctBy { it.getLong("eventID") }
            .map { JSONObject(it.toString()).apply { remove("syncState"); put("rev", 0).put("synced", 0).put("everOnGlasses", true) } }
        val edit = local.edit().putString(TODOS, JSONArray(migrated).toString())
        // Those glasses held the cached open items: one they no longer hold was finished there.
        caches.forEach { (key, rows) -> edit.putString("todoSeen:" + key.removePrefix("todos:"),
            JSONArray(rows.filter { it.optInt("status") == 0 }.map { it.getLong("eventID") }).toString()) }
        check(edit.commit())
        return migrated.toMutableList()
    }
    private fun storeTodos(items: List<JSONObject>) {
        check(local.edit().putString(TODOS, JSONArray(items).toString()).commit())
        update { it.copy(todos = visible(items)) }; publish()
    }
    private fun dirty(item: JSONObject) = item.optInt("rev") != item.optInt("synced")
    /** IDs the glasses at [device] were last seen holding: only those can be inferred as finished there. */
    private fun seenOn(device: String) = JSONArray(local.getString("todoSeen:$device", null) ?: "[]").longs().toSet()
    /** Open first, important first, newest first; deleted ones wait only until the glasses confirm. */
    private fun visible(items: List<JSONObject>) = items.filter { !it.optBoolean("deleted") }
        .sortedWith(compareBy<JSONObject>({ it.optInt("status") }, { !it.optBoolean("isImportant") }, { -it.optLong("createTime") }))
    private fun freeId(items: List<JSONObject>, also: Collection<Long> = emptyList()): Long {
        val used = items.map { it.getLong("eventID") }.toSet() + also
        // The range the glasses accepted for phone-made to-dos before.
        return (80000L until 90000L).firstOrNull { it !in used } ?: error("휴대폰 할 일 ID가 모두 소진됨")
    }
    /** Phone-side change (any thread); the glasses follow when connected. */
    fun todoLocal(action: String, args: JSONObject): JSONObject {
        val id = synchronized(todoLock) {
            val items = storedTodos(); val now = System.currentTimeMillis()
            val target = if (action == "add") JSONObject().put("eventType", 1).put("eventID", freeId(items)).put("createTime", now).put("title", "")
                .put("isImportant", false).put("status", 0).put("eventSource", 0).put("rev", 0).put("synced", 0).also { items += it }
            else checkNotNull(items.firstOrNull { it.getLong("eventID") == args.getLong("event_id") && !it.optBoolean("deleted") }) {
                "해당 할 일을 찾을 수 없습니다. 먼저 목록을 읽으세요" }
            if (action == "delete") { if (target.optBoolean("everOnGlasses")) target.put("deleted", true) else items.remove(target) }
            else {
                if (args.has("title")) { val title = args.getString("title").trim(); require(title.isNotEmpty()) { "할 일 제목을 입력하세요" }; target.put("title", title) }
                if (args.has("important")) target.put("isImportant", args.getBoolean("important"))
                if (args.has("completed")) {
                    val completed = args.getBoolean("completed")
                    if (!completed && target.optInt("status") != 0) target.put("reopened", true)
                    if (completed) target.remove("reopened")
                    target.put("status", if (completed) 1 else 0)
                }
                require(target.optString("title").isNotBlank()) { "할 일 제목을 입력하세요" }
            }
            target.put("lastModifiedTime", now / 1000).put("rev", target.optInt("rev") + 1)
            storeTodos(items); target.getLong("eventID")
        }
        syncTodosLater()
        return JSONObject().put("eventID", id).put("items", JSONArray(state.todos))
            .put("message", if (address() == null) "휴대폰에 저장했습니다. 안경이 연결되면 안경 목록에도 반영합니다" else "휴대폰에 저장하고 안경에 보내는 중입니다")
    }
    private fun todoTool(action: String, args: JSONObject): JSONObject {
        val request = args.optString("request_id").takeIf { it.isNotBlank() }?.let { "todoRequest:$it" }
        request?.let { key -> local.getString(key, null)?.let { id ->
            return JSONObject().put("eventID", id.toLong()).put("duplicate", true).put("items", JSONArray(state.todos))
                .put("message", "같은 요청은 이미 처리했습니다. 중복 변경은 없습니다")
        } }
        val result = todoLocal(action, args)
        request?.let { check(local.edit().putString(it, result.getLong("eventID").toString()).commit()) }
        return result
    }
    fun syncTodosLater() {
        if (address() == null || !todoSyncQueued.compareAndSet(false, true)) return
        execute({ todoSyncQueued.set(false); syncTodos() }) { result ->
            // A disconnect cancels the queued sync; that is not a failure worth showing.
            result.exceptionOrNull()?.takeIf { address() != null }?.let { error -> update { it.copy(todoNotice = "안경과 할 일 맞추기 실패: ${error.message}") }; changed() }
            log(result.fold({ "${state.todoNotice.ifBlank { "안경 할 일 맞추기 끝" }} (휴대폰 할 일 ${it.optJSONArray("items")?.length() ?: 0}개)" },
                { "안경 할 일 맞추기 실패 또는 취소: ${it.message}" }))
        }
    }
    /**
     * Glasses thread. The glasses list holds open to-dos only. Their new items come to the phone; phone edits, adds and
     * deletes go to the glasses; an open item these glasses held before and dropped was finished there (unless the
     * phone re-opened it).
     */
    fun syncTodos(): JSONObject {
        val device = device()
        val glasses = events(1)
        val seen = seenOn(device)
        fun wire(item: JSONObject) = JSONObject(item.toString()).apply {
            listOf("rev", "synced", "reopened", "deleted", "everOnGlasses", "syncState").forEach(::remove) }
        fun same(a: JSONObject, b: JSONObject) = a.optString("title").trim() == b.optString("title").trim()
        val (upserts, deletes, open) = synchronized(todoLock) {
            val items = storedTodos(); val onGlasses = glasses.map { it.getLong("eventID") }.toSet()
            val upserts = mutableListOf<JSONObject>(); val deletes = mutableListOf<Long>()
            for (row in glasses) {
                var mine = items.firstOrNull { it.getLong("eventID") == row.getLong("eventID") }
                // A phone item these glasses never had that shares an ID with a different glasses item: move the phone one.
                if (mine != null && row.getLong("eventID") !in seen && !mine.optBoolean("everOnGlasses") && !same(mine, row)) {
                    mine.put("eventID", freeId(items, onGlasses)); mine = null
                }
                when {
                    mine == null -> items += JSONObject(row.toString()).put("rev", 0).put("synced", 0).put("everOnGlasses", true)
                    mine.optBoolean("deleted") -> deletes += row.getLong("eventID")
                    dirty(mine) -> upserts += mine
                    else -> {
                        listOf("title", "isImportant", "lastModifiedTime").forEach { if (row.has(it)) mine.put(it, row.get(it)) }
                        mine.put("status", 0)
                    }
                }
                mine?.put("everOnGlasses", true)
            }
            items.removeAll { it.optBoolean("deleted") && it.getLong("eventID") !in onGlasses }
            for (mine in items) if (mine.getLong("eventID") !in onGlasses && !mine.optBoolean("deleted")) {
                val id = mine.getLong("eventID")
                when {
                    mine.optInt("status") != 0 -> mine.put("synced", mine.optInt("rev"))
                    id in seen && !mine.optBoolean("reopened") -> mine.put("status", 1).put("synced", mine.optInt("rev"))
                    else -> upserts += mine
                }
            }
            storeTodos(items)
            Triple(upserts.map { JSONObject(it.toString()) }, deletes.toList(),
                items.filter { it.optInt("status") == 0 && !it.optBoolean("deleted") }.map(::wire))
        }
        if (upserts.isNotEmpty() || deletes.isNotEmpty()) {
            deletes.forEach { id -> requests.request(22, 7, JSONObject().put("eventType", 1).put("todoTotal", 1).put("eventIDList", JSONArray().put(id)), wait = false) }
            upserts.forEach { requests.request(22, 2, wire(it), wait = false) }
            // The whole open list as one batch, as the glasses expect after completions and re-opens.
            requests.request(22, 6, JSONObject().put("total", open.size).put("isLastBatch", true).put("eventList", JSONArray(open)), wait = false)
        }
        val actual = if (upserts.isEmpty() && deletes.isEmpty()) glasses else events(1)
        var unconfirmed = 0
        synchronized(todoLock) {
            val items = storedTodos()
            for (sent in upserts) {
                val mine = items.firstOrNull { it.getLong("eventID") == sent.getLong("eventID") } ?: continue
                val row = actual.firstOrNull { it.getLong("eventID") == sent.getLong("eventID") }
                // Accepted when the glasses hold an open item or dropped a finished one; their exact text may differ.
                if (if (sent.optInt("status") == 0) row != null else row == null) {
                    if (mine.optInt("rev") == sent.optInt("rev")) { mine.put("synced", sent.optInt("rev")); mine.remove("reopened") }
                } else unconfirmed++
            }
            for (id in deletes) if (actual.none { it.getLong("eventID") == id }) items.removeAll { it.getLong("eventID") == id && it.optBoolean("deleted") } else unconfirmed++
            storeTodos(items)
            check(local.edit().putString("todoSeen:$device", JSONArray(actual.map { it.getLong("eventID") }).toString()).commit())
        }
        update { it.copy(todoNotice = when {
            upserts.isEmpty() && deletes.isEmpty() -> "안경 할 일과 같습니다"
            unconfirmed == 0 -> "안경 할 일과 맞췄습니다"
            else -> "안경에서 아직 확인되지 않은 할 일 ${unconfirmed}개는 다음 연결 때 다시 보냅니다"
        }) }; publish()
        return JSONObject().put("readBack", unconfirmed == 0).put("items", JSONArray(state.todos))
    }

    // ---- Calendar: the phone's calendars (Google accounts on the phone), read only; today and tomorrow go to the glasses. ----
    @Volatile private var calendarSyncQueued = false
    private var calendarChanged = true
    private var calendarSyncedAt = 0L
    private var calendarDay: LocalDate? = null
    private var observing = false
    private val calendarObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { calendarChanged = true; reloadCalendar(); calendarTick() }
    }
    /** Main thread: re-read calendars and the coming week for the phone screens. */
    fun reloadCalendar() {
        val allowed = PhoneCalendar.allowed(context)
        if (allowed && !observing) { context.contentResolver.registerContentObserver(android.provider.CalendarContract.CONTENT_URI, true, calendarObserver); observing = true }
        val from = PhoneCalendar.dayStart(); val to = PhoneCalendar.dayStart(LocalDate.now().plusDays(7))
        update { it.copy(calendarAllowed = allowed, calendars = PhoneCalendar.sources(context),
            calendarEvents = PhoneCalendar.events(context, state.calendarSelection, from, to)) }; changed()
    }
    fun selectCalendars(ids: Set<Long>?) {
        check(local.edit().apply { if (ids == null) remove("calendarSelection") else putString("calendarSelection", JSONArray(ids.toList()).toString()) }.commit())
        update { it.copy(calendarSelection = ids) }; reloadCalendar(); calendarChanged = true; calendarTick()
    }
    fun setCalendarToGlasses(enabled: Boolean) {
        check(local.edit().putBoolean("calendarToGlasses", enabled).commit())
        update { it.copy(calendarToGlasses = enabled) }; changed(); calendarChanged = true; calendarTick(force = true)
    }
    /** Main thread, every 30 s and after changes: send today and tomorrow when the calendar or the day changed, else every 15 min. */
    fun calendarTick(force: Boolean = false) {
        if (address() == null || !state.calendarAllowed || calendarSyncQueued || !force && !state.calendarToGlasses) return
        val day = LocalDate.now()
        if (!force && !calendarChanged && day == calendarDay && System.currentTimeMillis() - calendarSyncedAt < 15 * 60_000) return
        calendarSyncQueued = true; calendarChanged = false
        execute({ syncCalendar() }) { result ->
            calendarSyncQueued = false; calendarSyncedAt = System.currentTimeMillis(); calendarDay = day
            if (result.isFailure) calendarChanged = true
            val notice = result.fold({ it.optString("message") }, { "안경 일정 보내기 실패: ${it.message}" })
            log(notice + result.getOrNull()?.let { " (보낸 캘린더 일정 ${it.optInt("count")}개, 안경 일정 ${it.optJSONArray("items")?.length() ?: 0}개, 읽어서 확인 ${it.optBoolean("readBack")})" }.orEmpty())
            update { it.copy(calendarNotice = notice) }; changed()
        }
    }
    /** Glasses thread. Replaces the calendar items sent before and keeps schedules made elsewhere (assistant, glasses). */
    fun syncCalendar(): JSONObject {
        val deviceId = device(); val zone = ZoneId.systemDefault(); val now = System.currentTimeMillis()
        // Turned off: one last send with no calendar items takes the ones sent before off the glasses.
        val occurrences = if (!state.calendarToGlasses) emptyList()
            else PhoneCalendar.events(context, state.calendarSelection, PhoneCalendar.dayStart(), PhoneCalendar.dayStart(LocalDate.now().plusDays(2)))
        val oldMapping = JSONObject(local.getString("calendarMapping", null) ?: "{}")
        val existing = events(0)
        val previous = JSONArray(local.getString("calendarImported:$deviceId", "[]")).longs().toSet()
        val used = (existing.map { it.getLong("eventID") } + oldMapping.keys().asSequence().map { oldMapping.getLong(it) }).toMutableSet()
        // 32-bit IDs like the official app's, kept in a high range away from its phone calendar row IDs; earlier millisecond
        // IDs are replaced once. A kept ID that another writer now uses on the glasses is given up.
        val base = 1_900_000_000L
        val mapping = JSONObject(); var next = base
        val items = occurrences.map { event ->
            val id = oldMapping.optLong(event.key).takeIf { it in base..Int.MAX_VALUE && (it in previous || existing.none { row -> row.getLong("eventID") == it }) }
                ?: kotlin.run { while (next in used) next++; next.also { used += it; next++ } }
            mapping.put(event.key, id)
            // Started before today (multi-day): show it from today, the glasses list only today and tomorrow.
            val start = maxOf(event.begin, PhoneCalendar.dayStart()) / 1000; val end = maxOf(event.end / 1000, start + 60)
            val instant = Instant.ofEpochSecond(start)
            // Field order and the always-present location/isAllDay follow the official app's batch.
            JSONObject().put("eventType", 0).put("eventID", id).put("createTime", now).put("title", event.title)
                .put("startTime", start).put("endTime", end).put("location", event.location)
                .put("isTomorrowSchedule", instant.atZone(zone).toLocalDate() == LocalDate.now(zone).plusDays(1)).put("isAllDay", event.allDay)
                .put("status", 0).put("localTimeOffset", zone.rules.getOffset(instant).totalSeconds).put("lastModifiedTime", now).put("eventSource", 0)
        }
        val ids = items.map { it.getLong("eventID") }
        // The glasses' schedule widget shows "check calendar settings in mobile app" until the phone reports its calendar
        // access (official app: 0x16 type 9 pushAuthStatus, SyncCalendarPermissionMsg; authStatus 1 authorized,
        // statusCode 0 refreshed).
        pushCalendarAuth()
        // Remember what is about to be sent first, so a lost reply is still replaced next time.
        check(local.edit().putString("calendarMapping", mapping.toString())
            .putString("calendarImported:$deviceId", JSONArray((previous + ids).toList()).toString()).commit())
        val list = existing.filter { it.getLong("eventID") !in previous && it.getLong("eventID") !in ids } + items
        requests.request(22, 5, JSONObject().put("scheduleTotal", list.size).put("isLastBatch", true).put("eventList", JSONArray(list)), wait = false)
        val actual = events(0)
        val confirmed = items.all { sent -> actual.any { row -> listOf("eventID", "title", "startTime", "endTime").all { row.opt(it)?.toString() == sent.opt(it)?.toString() } } } &&
            actual.none { it.getLong("eventID") in previous && it.getLong("eventID") !in ids }
        if (confirmed) check(local.edit().putString("calendarImported:$deviceId", JSONArray(ids).toString()).commit())
        return JSONObject().put("count", items.size).put("readBack", confirmed).put("items", JSONArray(actual))
            .put("message", when {
                !state.calendarToGlasses -> if (confirmed) "안경에서 캘린더 일정을 뺐습니다" else "안경에서 캘린더 일정을 빼는 중입니다"
                confirmed -> "오늘·내일 일정 ${items.size}개를 안경에 보냈습니다"
                else -> "일정을 보냈지만 안경 목록에서 아직 확인되지 않았습니다. 잠시 뒤 다시 보냅니다" })
    }
    /** Glasses thread. Unauthorized goes with statusCode 4 (noAvailableSource), as the official app's status codes read. */
    private fun pushCalendarAuth() {
        val authorized = state.calendarToGlasses && PhoneCalendar.allowed(context)
        // Same keys, order and values as the official app's frames (snoop capture 2026-11-20).
        requests.request(22, 9, JSONObject().put("eventType", 0).put("authStatus", if (authorized) 1 else 0)
            .put("scheduleThirdPartyBindStatus", 0).put("statusCode", if (authorized) 0 else 4), wait = false)
        // The official app sends the to-do counterpart alongside every time.
        requests.request(22, 9, JSONObject().put("eventType", 1).put("todoThirdPartyBindStatus", 0).put("statusCode", 0), wait = false)
    }
    /** The coming week of the phone's calendar for the assistant. */
    private fun calendarRead(): JSONObject {
        check(PhoneCalendar.allowed(context)) { "휴대폰 캘린더 권한이 없습니다. 앱 홈의 일정 카드에서 허용하세요" }
        val zone = ZoneId.systemDefault(); val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val rows = PhoneCalendar.events(context, state.calendarSelection, PhoneCalendar.dayStart(), PhoneCalendar.dayStart(LocalDate.now().plusDays(7))).map { e ->
            JSONObject().put("title", e.title).put("allDay", e.allDay).put("calendar", e.calendar).put("location", e.location)
                .put("start", Instant.ofEpochMilli(e.begin).atZone(zone).format(format)).put("end", Instant.ofEpochMilli(e.end).atZone(zone).format(format))
        }
        return JSONObject().put("items", JSONArray(rows)).put("range", "오늘부터 7일").put("source", "휴대폰 캘린더(구글 계정 포함), 읽기 전용")
    }
    fun schedule(action: String, args: JSONObject): JSONObject {
        val mutationKey = "scheduleMutation:" + device() + ":" + args.optString("request_id").ifBlank { UUID.randomUUID().toString() }
        local.getString(mutationKey, null)?.let { prior ->
            return JSONObject().put("eventID", prior.toLong()).put("duplicate", true).put("readBack", false)
                .put("items", JSONArray(events(0))).put("message", "해당 요청은 이미 전송되었습니다. 일정 목록을 확인하세요. 중복 기록은 없습니다")
        }
        val items = events(0).map { JSONObject(it.toString()) }.toMutableList()
        val id = if (action == "add") generateSequence(System.currentTimeMillis()) { it + 1 }.first { id -> items.none { it.getLong("eventID") == id } }
            else args.getLong("event_id")
        val existing = items.firstOrNull { it.getLong("eventID") == id }
        val target = if (action == "add") JSONObject().put("eventType", 0).put("eventID", id).put("status", 0).put("eventSource", 0).put("createTime", System.currentTimeMillis())
            else JSONObject(checkNotNull(existing) { "일정이 없습니다" }.toString())
        if (action == "delete") items.remove(existing) else {
            if (args.has("title")) target.put("title", args.getString("title"))
            if (args.has("start_time")) target.put("startTime", args.getLong("start_time"))
            if (args.has("end_time")) target.put("endTime", args.getLong("end_time"))
            require(target.optString("title").isNotBlank() && target.getLong("endTime") > target.getLong("startTime")) { "일정에는 제목, 시작 시간, 종료 시간이 필요합니다" }
            val instant = Instant.ofEpochSecond(target.getLong("startTime")); val zone = ZoneId.systemDefault()
            target.put("localTimeOffset", zone.rules.getOffset(instant).totalSeconds)
                .put("isTomorrowSchedule", instant.atZone(zone).toLocalDate() == LocalDate.now(zone).plusDays(1)).put("lastModifiedTime", System.currentTimeMillis())
            items.remove(existing); items += target
        }
        check(local.edit().putString(mutationKey, id.toString()).commit())
        requests.request(22, 5, JSONObject().put("scheduleTotal", items.size).put("isLastBatch", true).put("eventList", JSONArray(items)), wait = false)
        val actual = events(0); val row = actual.firstOrNull { it.getLong("eventID") == id }
        val confirmed = if (action == "delete") row == null else row != null && listOf("title", "startTime", "endTime").all { row.opt(it) == target.opt(it) }
        return JSONObject().put("eventID", id).put("readBack", confirmed).put("items", JSONArray(actual))
    }
    fun searchCities(query: String): JSONObject {
        fun search(name: String) = get("https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(name, "UTF-8") + "&count=10&language=ko&format=json").optJSONArray("results").objects()
        val trimmed = query.trim()
        // Open-Meteo lists Korean cities under full administrative names (서울특별시, 부산광역시, 수원시) and
        // short queries only match exactly, so "서울" alone finds nothing. Try those forms for Hangul input.
        val names = if (trimmed.any { it in '가'..'힣' }) {
            val base = trimmed.removeSuffix("특별시").removeSuffix("광역시").removeSuffix("시")
            listOf(trimmed) + listOf("특별시", "광역시", "특별자치시", "시", "군", "구").map { base + it }.filter { it != trimmed }
        } else listOf(trimmed)
        val cities = names.flatMap(::search).distinctBy { it.optLong("id") }
            .sortedByDescending { it.optLong("population", 0) }.take(10)
        update { it.copy(weatherCities = cities) }
        return JSONObject().put("results", JSONArray(cities))
    }
    /** The phone's position as a weather city, named by the system geocoder (e.g. 강남구) when it can. */
    fun currentCity(): JSONObject {
        val location = phoneLocation.current()
        val latitude = location.getDouble("latitude"); val longitude = location.getDouble("longitude")
        val place = if (!android.location.Geocoder.isPresent()) null else runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(context, java.util.Locale.KOREAN).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()
        val name = place?.run { subLocality ?: locality ?: subAdminArea ?: adminArea } ?: "현재 위치"
        return JSONObject().put("id", 0L).put("name", name).put("latitude", latitude).put("longitude", longitude).put("current", true)
    }
    fun currentWeather(): JSONObject {
        val location = phoneLocation.current()
        val result = forecast(location.getDouble("latitude"), location.getDouble("longitude"))
        result.getJSONObject("current").put("description", description(result.getJSONObject("current").getInt("weather_code")))
        return result
            .put("location", location).put("source", "Open-Meteo").put("fetchedAt", System.currentTimeMillis())
    }
    private fun forecast(latitude: Double, longitude: Double): JSONObject = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day,precipitation_probability&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3")
    fun weather(city: JSONObject): JSONObject {
        val deviceId = device()
        val latitude = city.getDouble("latitude"); val longitude = city.getDouble("longitude")
        val response = forecast(latitude, longitude)
        val current = response.getJSONObject("current"); val day = current.optInt("is_day", 1) == 1
        val hourly = response.getJSONObject("hourly"); val data = JSONObject()
        val currentTime = current.getString("time")
        val indices = (0 until hourly.getJSONArray("time").length()).filter { hourly.getJSONArray("time").getString(it) >= currentTime }.take(5)
        for (index in indices) {
            val time = LocalDateTime.parse(hourly.getJSONArray("time").getString(index)).hour.toString() + "시"
            data.put(time, JSONArray().put(kotlin.math.round(hourly.getJSONArray("temperature_2m").getDouble(index)).toInt())
                .put(icon(hourly.getJSONArray("weather_code").getInt(index), hourly.getJSONArray("is_day").getInt(index) == 1)))
        }
        val daily = response.getJSONObject("daily")
        val card = JSONObject().put("location", city.getString("name")).put("location_id", city.getLong("id").toString())
            .put("temp", kotlin.math.round(current.getDouble("temperature_2m")).toInt()).put("icon", icon(current.getInt("weather_code"), day))
            .put("des", description(current.getInt("weather_code"))).put("temp_range", "${kotlin.math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt()}°/${kotlin.math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt()}°")
            .put("hourly", data)
        check(address() == deviceId) { "기기 연결이 바뀌어 이전 요청의 날씨는 저장하지 않았습니다" }
        card.put("fetchedAt", System.currentTimeMillis())
        update { it.copy(weather = card) }
        check(local.edit().putString("weatherCity:" + device(), city.toString()).putString("weatherCard:" + device(), card.toString()).commit())
        return JSONObject().put("weather", card)
    }
    fun pushWeather(): JSONObject {
        val card = checkNotNull(state.weather) { "먼저 도시를 선택하고 날씨를 가져오세요" }
        launcher("current_weather_update", JSONObject().put("location", card.getString("location")).put("temp", card.getInt("temp")).put("icon", card.getInt("icon")).toString())
        launcher("weather_update", JSONArray().put(card).toString())
        return JSONObject().put("sent", "weather").put("readBack", false).put("message", "홈 온도와 날씨 카드 모두 응답을 받았습니다. 안경 표시는 확인이 필요합니다")
    }
    fun launcher(command: String, data: String, timestamp: String = (System.currentTimeMillis() / 1000).toString()): JSONObject {
        return checkNotNull(requests.request(15, 18, JSONObject().put("cmd", command).put("payload", JSONObject()
            .put("value", 0).put("mode", 0).put("data", data).put("ts", timestamp))) { type, body -> type == 19 && body.optString("cmd") == command })
    }
    fun tool(name: String, args: JSONObject): JSONObject = when (name) {
        "todo_read" -> JSONObject().put("items", JSONArray(state.todos)).put("source", "휴대폰 할 일 목록(안경과 동기화)")
        "todo_add", "todo_update", "todo_delete" -> todoTool(name.substringAfter('_'), args)
        "calendar_read" -> calendarRead()
        "schedule_read" -> JSONObject().put("items", JSONArray(events(0)))
        "schedule_add", "schedule_update", "schedule_delete" -> schedule(name.substringAfter('_'), args)
        "weather_search_cities" -> searchCities(args.getString("query"))
        "weather_current" -> currentWeather()
        "location_current" -> phoneLocation.current()
        "weather_get" -> weather(args.getJSONObject("city"))
        "weather_push" -> pushWeather()
        "dashboard_read" -> dashboard()
        "dashboard_update" -> layout(args.getInt("mode"), args.getJSONArray("widgets").ints())
        else -> error("구현되지 않은 기기 도구: $name")
    }
    /** Glasses-side to-do changes (checked off on the glasses). A finished state always wins; text only on unedited items. */
    fun observe(type: Int, body: JSONObject) {
        if (type !in setOf(4, 10) || address() == null) return
        val updates = (if (type == 4) listOf(body) else body.optJSONArray("eventList").objects()).filter { it.optInt("eventType", 1) == 1 }
        if (updates.isEmpty()) return
        var unknown = false
        synchronized(todoLock) {
            val items = storedTodos(); var touched = false
            for (update in updates) {
                val mine = items.firstOrNull { it.optLong("eventID") == update.optLong("eventID", -1) }
                if (mine == null) { unknown = true; continue }
                val fields = if (dirty(mine)) listOf("status") else listOf("status", "isImportant", "lastModifiedTime")
                fields.forEach { if (update.has(it) && mine.opt(it) != update.opt(it)) { mine.put(it, update.opt(it)); touched = true } }
                if (update.optInt("status") != 0) mine.remove("reopened")
            }
            if (touched) storeTodos(items)
        }
        // Added on the glasses (e.g. by voice): bring it over now rather than at the next connect.
        if (unknown) syncTodosLater()
    }
    // After every property above: the calendar observer and flags must exist before the first read.
    init {
        update { it.copy(todos = visible(storedTodos()), calendarToGlasses = local.getBoolean("calendarToGlasses", true),
            calendarSelection = local.getString("calendarSelection", null)?.let { JSONArray(it).longs().toSet() }) }
        reloadCalendar()
    }
    fun close() { if (observing) context.contentResolver.unregisterContentObserver(calendarObserver); http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    private fun get(url: String): JSONObject = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        check(response.isSuccessful) { "날씨 요청 실패: HTTP ${response.code}" }; JSONObject(checkNotNull(response.body).string())
    }
    private fun icon(code: Int, day: Boolean): Int = when (code) {
        0 -> if (day) 100 else 150; 1 -> if (day) 103 else 153; 2 -> if (day) 101 else 151; 3 -> 104
        45,48 -> 501; 51,53,55 -> 309; 56,57,66,67 -> 313; 61 -> 305; 63 -> 306; 65 -> 307
        71 -> 400; 73 -> 401; 75,77 -> 402; 80 -> if (day) 300 else 350; 81,82 -> if (day) 301 else 351
        85,86 -> if (day) 407 else 457; 95,97 -> 302; 96,99 -> 304; else -> 104
    }
    private fun description(code: Int) = when (code) { 0 -> "맑음"; 1,2 -> "구름 많음"; 3 -> "흐림"; 45,48 -> "안개"; in 51..67, in 80..82 -> "비"; in 71..77,85,86 -> "눈"; in 95..99 -> "뇌우"; else -> "날씨" }
}
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
internal fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else List(length()) { getInt(it) }
internal fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else List(length()) { getLong(it) }
private const val TODOS = "todos:phone"
