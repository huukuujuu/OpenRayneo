// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Encounter transport adapter. Device tools remain exclusively on the loopback Agent API. */
internal class RecordSyncServer(private val settings: AppSettings, private val repository: ConversationRepository,
    private val audio: MeetingAudioStore, private val main: Handler, private val changed: () -> Unit,
    /** Newest bytes of the app log; served to the PC so a device test can be read without copying it by hand. */
    private val logs: (Int) -> String = { "" }) {
    // Accept runs on its own thread; each accepted socket needs a slot, so idle connections cannot pile up past the pool.
    private val network = Executors.newFixedThreadPool(MAX_CLIENTS)
    private val slots = Semaphore(MAX_CLIENTS)
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var token = ""
    @Volatile var state = AgentServerState(notice = "근거리 동기화가 꺼져 있음"); private set
    fun tokenForUser() = token
    /** Reachable first: Wi-Fi LAN, then Tailscale (100.64/10); 192.0.0.x is the carrier's internal (CLAT) address, never reachable. */
    fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address && !it.hostAddress.orEmpty().startsWith("192.0.0.") }
        .sortedBy { val a = it.address.map { b -> b.toInt() and 0xff }
            when { it.isSiteLocalAddress -> 0; a[0] == 100 && a[1] in 64..127 -> 1; else -> 2 } }
        .map { "http://${it.hostAddress}:${state.port}" }
    fun start() {
        if (socket != null) return
        val config = settings.read()
        token = config.optString("recordSyncToken").ifBlank {
            Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE)
        }
        val server = ServerSocket(config.optInt("recordSyncPort", 8767), 8, InetAddress.getByName("0.0.0.0"))
        socket = server
        config.put("recordSyncToken", token).put("recordSyncEnabled", true); settings.save(config)
        state = AgentServerState(true, server.localPort, "컴퓨터 근거리 동기화 대기 중 · 오디오는 필요할 때만 전송"); changed()
        Thread({
            try { while (!server.isClosed) {
                val client = server.accept()
                if (!slots.tryAcquire()) { client.close(); continue }
                clients.add(client)
                try { network.execute { try { handle(client) } catch (_: Exception) {} finally { clients.remove(client); client.close(); slots.release() } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close(); slots.release() }
            } } catch (error: java.io.IOException) {
                if (!server.isClosed) main.post { state = state.copy(notice = "동기화 엔드포인트 중단: ${error.message}"); changed() }
            }
        }, "record-sync-accept").start()
    }
    fun stop() {
        closeSocket()
        val config = settings.read(); config.put("recordSyncEnabled", false); settings.save(config)
        state = AgentServerState(notice = "근거리 동기화가 꺼져 있음"); changed()
    }
    fun rotate() { stop(); val config = settings.read(); config.remove("recordSyncToken"); settings.save(config); start() }
    private fun closeSocket() { socket?.close(); socket = null; clients.forEach { it.close() }; clients.clear(); token = "" }
    fun close() { closeSocket(); network.shutdownNow(); deadlines.shutdownNow() }
    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        // Whole-request deadline until the token is checked, so a slow unauthenticated sender cannot hold a slot.
        val deadline = deadlines.schedule({ client.close() }, 20, TimeUnit.SECONDS)
        val output = client.getOutputStream()
        fun json(code: Int, data: JSONObject) {
            val bytes = data.toString().toByteArray()
            output.write("HTTP/1.1 $code Result\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes); output.flush()
        }
        try {
            val input = BufferedInputStream(client.getInputStream())
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) { val value = input.read(); require(value >= 0); if (value == 10) break; bytes.write(value); require(bytes.size() <= 8192) }
                return bytes.toString("US-ASCII").trimEnd('\r')
            }
            val request = line().split(' '); require(request.size == 3)
            val headers = mutableMapOf<String, String>(); var total = 0
            while (true) { val row = line(); if (row.isEmpty()) break; total += row.length; require(total <= 16384)
                headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
            if (token.isEmpty() || headers["authorization"] != "Bearer $token") { json(401, JSONObject().put("error", "동기화 토큰이 유효하지 않음")); return }
            if (headers.containsKey("origin")) { json(403, JSONObject().put("error", "웹 페이지의 교차 출처 호출은 허용하지 않음")); return }
            deadline.cancel(false)
            val path = request[1].substringBefore('?')
            if (request[0] == "POST" && path == "/v1/sync/exchange") {
                require(!headers.containsKey("transfer-encoding"))
                val length = headers["content-length"]?.toInt() ?: 0; require(length in 1..2097152)
                val bytes = input.readNBytes(length); require(bytes.size == length)
                val body = JSONObject(bytes.toString(Charsets.UTF_8)); require(body.getInt("protocolVersion") == 1)
                val incoming = body.optJSONArray("derived")?.let { a -> List(a.length()) { RecordDerived.parse(a.getJSONObject(it)) } } ?: emptyList()
                val result = repository.exchange(body.optLong("cursor"), body.optInt("limit", 100), incoming)
                json(200, result)
                main.post { state = state.copy(notice = "마지막 동기화 ${java.time.LocalTime.now().withNano(0)} · 원본 기록은 휴대폰에 저장됨"); changed() }
            } else if (request[0] == "GET" && path == "/v1/sync/audio") {
                val args = request[1].substringAfter('?', "").split('&').associate { row -> row.substringBefore('=') to URLDecoder.decode(row.substringAfter('=', ""), "UTF-8") }
                val record = repository.records.firstOrNull { it.id == args["recordId"] }
                if (record == null) { json(404, JSONObject().put("error", "사용 가능한 오디오 없음")); return }
                if (record.audioPending) { json(409, JSONObject().put("error", "오디오 기록 중")); return }
                if (record.audioSamples == 0L) { json(404, JSONObject().put("error", "사용 가능한 오디오 없음")); return }
                output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${44 + record.audioSamples * 2 * record.audioChannels}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                audio.exportWav(record.audioId ?: record.id, record.audioSamples, output); output.flush()
            } else if (request[0] == "GET" && path == "/v1/sync/logs") {
                val args = request[1].substringAfter('?', "").split('&').associate { row -> row.substringBefore('=') to URLDecoder.decode(row.substringAfter('=', ""), "UTF-8") }
                val bytes = logs((args["bytes"]?.toIntOrNull() ?: 300_000).coerceIn(1_000, 2_000_000)).toByteArray(Charsets.UTF_8)
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(bytes); output.flush()
            } else json(404, JSONObject().put("error", "해당 동기화 엔드포인트가 없음"))
        } catch (error: Exception) { if (!client.isClosed) runCatching { json(400, JSONObject().put("error", error.message ?: "동기화가 완료되지 않음")) } }
        finally { deadline.cancel(false) }
    }
    private companion object { const val MAX_CLIENTS = 4 }
}
