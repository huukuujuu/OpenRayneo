// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // Activity grants SCAN and CONNECT before invoking this controller.
internal class RayneoConnection(private val context: Context, private val listener: Listener) {
    data class Device(val name: String, val bluetooth: BluetoothDevice, val classicAddress: String?)
    interface Listener {
        fun onDevice(device: Device)
        fun onState(text: String, ready: Boolean, busy: Boolean)
        fun onLog(text: String)
        fun onFrame(frame: ProtocolCodec.Frame, source: String) {}
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var gatt: BluetoothGatt? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var scanning = false
    private var ready = false
    private var expectedAddress: String? = null
    val resolvedClassicAddress: String? get() = expectedAddress
    private var classicAddress = byteArrayOf()
    private var identity = byteArrayOf()
    private var send: BluetoothGattCharacteristic? = null
    private var pairingSequence = 0
    private var notificationSequence = 0
    private var bleDecoder = ProtocolCodec.StreamDecoder()
    private var authenticationHandled = false
    private var bleOnly = false
    private var blePayloadSize = 20
    private data class BleWrite(val bytes: ByteArray, val completion: String?)
    private val bleWrites = ArrayDeque<BleWrite>()
    private var bleWriting = false
    private var generation = 0
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionStage = "연결 대기 중"
    private val timeout = Runnable { fail("연결 시간 초과: $connectionStage") }
    private val stopScanTask = Runnable { stopScan(); state("스캔 완료, 연결할 안경을 선택하세요") }

    fun showBonded() {
        check(adapter.isEnabled) { "먼저 휴대폰 블루투스를 켜세요" }
        adapter.bondedDevices.filter { it.name?.contains("RayNeo", true) == true }.forEach {
            listener.onDevice(Device(it.name ?: "RayNeo iO", it, it.address))
        }
    }

    fun scan() {
        check(adapter.isEnabled) { "먼저 휴대폰 블루투스를 켜세요" }
        check(gatt == null) { "먼저 현재 연결을 끊으세요" }
        stopScan()
        state("스캔 중: 안경 찾는 중…", busy = true)
        scanning = true
        adapter.bluetoothLeScanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        main.postDelayed(stopScanTask, 12_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: ""
                val manufacturer = record?.getManufacturerSpecificData(0x5716)
                val matches = name.contains("RayNeo iO", true) ||
                    record?.serviceUuids?.any { it.uuid == SERVICE } == true ||
                    (manufacturer != null && manufacturer.size >= 7 && manufacturer[0] == 6.toByte())
                if (!matches) return@post
                val address = manufacturer?.takeIf { it.size >= 7 && it[0] == 6.toByte() }
                    ?.copyOfRange(1, 7)?.let(::addressString)
                listener.onDevice(Device(name.ifEmpty { "RayNeo iO" }, result.device, address))
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { stopScan(); state("BLE 스캔 실패: $errorCode") }
        }
    }

    fun connect(device: Device, bleOnly: Boolean = false) {
        disconnect()
        this.bleOnly = bleOnly
        expectedAddress = device.classicAddress
        pairingSequence = 0
        bleDecoder = ProtocolCodec.StreamDecoder()
        // Match the verified Mac sequence: classic bond first, then BLE application auth.
        val address = device.classicAddress
        if (!bleOnly && address != null) {
            ensureBond(adapter.getRemoteDevice(address)) { startBle(device) }
        } else {
            startBle(device)
        }
    }

    private fun startBle(device: Device) {
        main.removeCallbacks(timeout)
        state("연결 중: BLE…", busy = true)
        main.postDelayed(timeout, 25_000)
        gatt = device.bluetooth.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, main)
        check(gatt != null) { "GATT 연결을 만들 수 없음" }
    }

    private fun ensureBond(target: BluetoothDevice, continuation: () -> Unit) {
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            log("클래식 블루투스 페어링됨")
            continuation()
            return
        }
        state("휴대폰에서 시스템 블루투스 페어링을 완료하세요…", busy = true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (bondReceiver !== this || intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device?.address != target.address) return
                val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                log("클래식 블루투스 페어링 상태=$bond")
                when (bond) {
                    BluetoothDevice.BOND_BONDED -> {
                        stopObservingBond()
                        main.removeCallbacks(timeout)
                        guarded(continuation)
                    }
                    BluetoothDevice.BOND_NONE -> fail("클래식 블루투스 페어링이 완료되지 않았습니다. 안경을 페어링 가능 상태로 두고 다시 연결하세요")
                }
            }
        }
        bondReceiver = receiver
        // Bluetooth broadcasts can originate from the privileged Bluetooth process.
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 45_000)
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            stopObservingBond()
            main.removeCallbacks(timeout)
            continuation()
        } else if (target.bondState == BluetoothDevice.BOND_NONE) {
            if (!target.createBond()) fail("시스템이 클래식 블루투스 페어링 요청을 받아들이지 않음")
        }
    }

    private fun stopObservingBond() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        context.unregisterReceiver(receiver)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("BLE 연결 종료: status=$status, state=$newState")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) guarded {
                state("BLE 연결됨, 서비스 탐색 중…", busy = true)
                check(connection.discoverServices()) { "서비스 탐색을 시작할 수 없음" }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (connection !== gatt) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "서비스 탐색 실패: $status" }
                val service = checkNotNull(connection.getService(SERVICE)) { "기기에 B81D 서비스가 없음" }
                send = checkNotNull(service.getCharacteristic(SEND)) { "송신 특성이 없음" }
                checkNotNull(service.getCharacteristic(RECEIVE)) { "수신 특성이 없음" }
                checkNotNull(service.getCharacteristic(PAIR_INFO)) { "신원 특성이 없음" }
                state("B81D 발견, 협상 중: BLE MTU…", busy = true)
                check(connection.requestMtu(512)) { "MTU 협상을 시작할 수 없음" }
            }
        }

        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) {
            if (connection !== gatt) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS && mtu >= 160) {
                    "BLE MTU  부족: mtu=$mtu, status=$status"
                }
                log("BLE MTU=$mtu")
                blePayloadSize = mtu - 3
                state("안경 신원 읽는 중…", busy = true)
                check(connection.readCharacteristic(connection.getService(SERVICE).getCharacteristic(PAIR_INFO))) {
                    "신원 특성을 읽을 수 없음"
                }
            }
        }

        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            if (connection !== gatt || characteristic.uuid != PAIR_INFO) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS && value.size >= 13) { "안경 신원 읽기 실패: $status" }
                classicAddress = value.copyOfRange(0, 6)
                val address = addressString(classicAddress)
                check(expectedAddress == null || expectedAddress.equals(address, true)) { "BLE 가 선택한 클래식 블루투스 기기와 일치하지 않음" }
                expectedAddress = address
                val bound = value.copyOfRange(7, 13)
                // Preserve the Mac bridge's bound-identity baseline; unbound devices use the
                // official Android client's six-byte Android ID convention (last 12 hex digits).
                identity = if (bound.any { it != 0.toByte() }) bound else androidIdentity()
                log("신원 읽음; 상태=${value[6].toInt() and 255}; 신원 출처=${if (bound.any { it != 0.toByte() }) "안경 바인딩" else "이 앱의 Android ID"}")
                val receive = connection.getService(SERVICE).getCharacteristic(RECEIVE)
                check(connection.setCharacteristicNotification(receive, true)) { "BLE 알림을 켤 수 없음" }
                val descriptor = checkNotNull(receive.getDescriptor(CCCD)) { "누락: CCCD" }
                state("BLE 알림 구독 중…", busy = true)
                check(connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS) {
                    "쓰기 불가: CCCD"
                }
            }
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (connection !== gatt || descriptor.uuid != CCCD) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE 알림 구독 실패: $status" }
                state("기기 정보 교환 중…", busy = true)
                writePairing(17, listOf(
                    16 to byteArrayOf(1), 17 to identity,
                    18 to (adapter.name ?: "OpenRayneo Android").toByteArray(),
                    19 to byteArrayOf(2), 22 to Build.MANUFACTURER.toByteArray(),
                    23 to Build.MODEL.toByteArray(), 27 to byteArrayOf(1)
                ))
            }
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (connection !== gatt || characteristic.uuid != SEND) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE  쓰기 실패: $status" }
                bleWriting = false
                bleWrites.removeFirstOrNull()?.completion?.let(::log)
                writeNextBleChunk()
            }
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (connection !== gatt || characteristic.uuid != RECEIVE) return
            guarded {
                bleDecoder.append(value).forEach { frame ->
                    if (frame.acknowledgement) {
                        if (bleOnly) log("BLE 전송 확인 수신: seq=${frame.sequence}")
                    } else {
                        if (frame.flags and 1 != 0) {
                            enqueueBleFrame(ProtocolCodec.acknowledgement(frame), null)
                        }
                        if (frame.protocol == 0x10) processPairing(frame)
                        else listener.onFrame(frame, "BLE")
                    }
                }
            }
        }
    }

    private fun processPairing(frame: ProtocolCodec.Frame) {
        check(frame.payload.size >= 3) { "인증 응답이 불완전함" }
        val command = frame.payload[0].toInt() and 255
        log("BLE 명령 수신 $command")
        when (command) {
            17 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                check(25 !in fields) { "안경이 계정 바인딩 ECDH를 요구하지만 현재 검증 버전은 지원하지 않음" }
                val random = ByteArray(4).also { SecureRandom().nextBytes(it) }
                state("앱 인증 진행 중…", busy = true)
                writePairing(24, listOf(16 to random, 17 to authentication(random)))
            }
            25 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                val random = checkNotNull(fields[16]) { "인증 응답에 챌린지 값이 없음" }
                check(random.size == 4) { "인증 챌린지 값 길이 오류" }
                check(fields[17]?.contentEquals(authentication(random)) == true) { "앱 인증이 거부됨" }
                if (!authenticationHandled) {
                    authenticationHandled = true
                    log("BLE  앱 인증 통과")
                    if (bleOnly) {
                        main.removeCallbacks(timeout)
                        ready = true
                        state("BLE  인증됨, 알림 시도 가능 (완전한 페어링은 미검증)", ready = true)
                    } else openRfcomm()
                }
            }
            22, 23 -> log("안경 상태=${frame.payload.getOrNull(3)?.toInt()?.and(255)}")
        }
    }

    private fun writePairing(command: Int, fields: List<Pair<Int, ByteArray>>) {
        val bytes = ProtocolCodec.pairing(pairingSequence++, command, fields)
        enqueueBleFrame(bytes, "BLE 명령 전송 $command (${bytes.size}바이트)")
    }

    private fun enqueueBleFrame(bytes: ByteArray, completion: String?) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + blePayloadSize, bytes.size)
            bleWrites.addLast(BleWrite(bytes.copyOfRange(offset, end), if (end == bytes.size) completion else null))
            offset = end
        }
        writeNextBleChunk()
    }

    private fun writeNextBleChunk() {
        if (bleWriting || bleWrites.isEmpty()) return
        val connection = checkNotNull(gatt)
        bleWriting = true
        check(connection.writeCharacteristic(checkNotNull(send), bleWrites.first().bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS) { "BLE  전송이 받아들여지지 않음" }
    }

    private fun authentication(random: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(random + identity + classicAddress + AUTH_CONSTANT)

    private fun androidIdentity(): ByteArray {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        check(!id.isNullOrEmpty() && id.length >= 12) { "이 앱의 Android ID를 사용할 수 없음" }
        return id.takeLast(12).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun openRfcomm() {
        val target = adapter.getRemoteDevice(checkNotNull(expectedAddress))
        ensureBond(target) { connectRfcomm(target) }
    }

    private fun connectRfcomm(target: BluetoothDevice) {
        state("BLE  인증됨, 연결 중: SPP…", busy = true)
        val session = generation
        log("클래식 블루투스 페어링 상태=${target.bondState}; RFCOMM 서비스 UUID=$SPP")
        val pending = target.createRfcommSocketToServiceRecord(SPP)
        socket = pending
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        io.execute {
            try {
                adapter.cancelDiscovery()
                pending.connect()
                main.post {
                    if (session != generation || socket !== pending) return@post
                    main.removeCallbacks(timeout)
                    ready = true
                    state("연결됨: BLE 인증과 RFCOMM 모두 정상", ready = true)
                    startReader(pending, session)
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("SPP  연결 실패: ${error.message}") }
            }
        }
    }

    private fun startReader(connection: BluetoothSocket, session: Int) {
        Thread({
            val decoder = ProtocolCodec.StreamDecoder()
            val buffer = ByteArray(4096)
            try {
                while (socket === connection) {
                    val count = connection.inputStream.read(buffer)
                    check(count >= 0) { "안경이 데이터 스트림을 닫음" }
                    val frames = decoder.append(buffer.copyOf(count))
                    main.post {
                        if (session == generation) frames.forEach {
                            if (!it.acknowledgement) listener.onFrame(it, "SPP")
                        }
                    }
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("데이터 연결 종료: ${error.message}") }
            }
        }, "rayneo-reader").start()
    }

    /**
     * Diagnostic: three notifications 2 s apart — English only, the original Chinese text, and Korean —
     * to tell whether the glasses firmware drops messages containing glyphs its font lacks.
     */
    fun sendTestNotification() {
        sendNotification("Test 1 (EN)", "Hello RayNeo glasses. English only.")
        main.postDelayed({ if (ready) sendNotification("테스트 2 (한국어)", "안드로이드 직결 확인: 안녕하세요, RayNeo 안경.") }, 2_000)
        main.postDelayed({ if (ready) sendNotification("Test 3 (KO)", "한국어 테스트: 안녕하세요, RayNeo 안경.") }, 4_000)
    }

    fun sendNotification(title: String, body: String) {
        check(ready) { "먼저 안경을 연결하세요" }
        val timestamp = System.currentTimeMillis().toString()
        val payload = JSONObject().apply {
            put("notificationUID", timestamp)
            put("appId", "com.openrayneo.bridge")
            put("appName", "OpenRayneo Android")
            put("title", title)
            put("subtitle", JSONObject.NULL)
            put("content", body)
            put("timestamp", timestamp)
            put("category", 0)
            put("reply", false)
            put("type", 1)
        }
        val frame = ProtocolCodec.notification(notificationSequence++, payload.toString())
        if (bleOnly) {
            enqueueBleFrame(frame, "BLE 알림을 전송했습니다. 안경에서 표시를 확인하세요")
            return
        }
        sendFrame(frame) { result ->
            result.onSuccess { log("알림을 전송했습니다. 안경에서 표시를 확인하세요") }.onFailure { log("알림 전송 실패: ${it.message}") }
        }
    }

    fun sendFrame(frame: ByteArray, completion: (Result<Unit>) -> Unit) {
        check(ready && !bleOnly) { "이 기능은 완전한 RFCOMM 연결이 필요합니다. BLE-only 모드를 끄세요" }
        val session = generation
        val connection = checkNotNull(socket)
        io.execute {
            try {
                check(socket === connection) { "연결이 변경됨" }
                connection.outputStream.write(frame)
                connection.outputStream.flush()
                main.post { completion(if (session == generation) Result.success(Unit) else Result.failure(IllegalStateException("연결이 변경됨"))) }
            } catch (error: Exception) {
                main.post {
                    completion(Result.failure(error))
                    if (session == generation) fail("데이터 전송 실패: ${error.message}")
                }
            }
        }
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanTask)
        if (scanning) adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    fun disconnect() {
        generation++
        main.removeCallbacks(timeout)
        stopObservingBond()
        stopScan()
        ready = false
        authenticationHandled = false
        bleWrites.clear()
        bleWriting = false
        blePayloadSize = 20
        val oldGatt = gatt
        gatt = null
        oldGatt?.disconnect()
        oldGatt?.close()
        val oldSocket = socket
        socket = null
        runCatching { oldSocket?.close() }
        send = null
        state("연결 끊김")
    }

    fun close() { disconnect(); io.shutdownNow() }

    private fun fail(message: String) { disconnect(); state(message) }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) { fail(error.message ?: error.javaClass.simpleName) }
    }
    private fun state(text: String, ready: Boolean = false, busy: Boolean = false) {
        if (busy) connectionStage = text
        listener.onState(text, ready, busy)
        log(text)
    }
    private fun log(text: String) {
        Log.i("OpenRayneo", text)
        listener.onLog(text)
    }

    companion object {
        private val SERVICE = UUID.fromString("0000B81D-0000-1000-8000-00805F9B34FB")
        private val SEND = UUID.fromString("EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9")
        private val RECEIVE = UUID.fromString("7DB3E235-3608-41F3-A03C-955FCBD2EA4B")
        private val PAIR_INFO = UUID.fromString("EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        // RayNeo business service (APK V3/p); standard SPP is used by RNLink log capture.
        private val SPP = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val AUTH_CONSTANT = intArrayOf(7, 18, 200, 173, 39, 116, 65, 149, 222, 95, 161, 234, 109, 2, 95, 184)
            .map { it.toByte() }.toByteArray()
        private fun addressString(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
    }
}
