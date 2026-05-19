// AirTAK Android — data/ble/BleRepository.kt
// Planning: §10 (BLE service), §11.10 (lifecycle)
// Roadmap: 3.2, 3.7
//
// Coroutine-based BLE transport. Single-device model: at most one BLE link at
// a time. The repository owns the connection state machine, the GATT instance,
// MTU negotiation, per-characteristic chunk-framing reassembly, and the
// exponential reconnect backoff. UI / domain layers consume:
//
//   linkState : StateFlow<LinkState>      — connection status for chrome
//   inbound   : SharedFlow<InboundFrame>  — reassembled payloads
//   discovered: StateFlow<List<...>>      — only while scanning
//
// LE Secure Connections / Numeric Comparison pairing is initiated implicitly
// by the OS when the app writes to a security-requiring characteristic
// (CONFIG_RW / MESH_TX / ADMIN). The system dialog presents the 6-digit code;
// the user compares to the code shown on the device e-ink and accepts.

package com.airtak.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.airtak.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("MissingPermission")
@Singleton
class BleRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: AppPreferences,
) {

    // ── External state ────────────────────────────────────────────────────────
    private val _linkState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val inbound: SharedFlow<InboundFrame> = _inbound.asSharedFlow()

    /** Surfaces a fatal write failure (all retries exhausted) so callers like
     *  the OTA engine can abort instead of silently losing bytes. Reset to
     *  null whenever a new operation begins succeeding. */
    private val _txFailure = MutableStateFlow<TxFailure?>(null)
    val txFailure: StateFlow<TxFailure?> = _txFailure.asStateFlow()

    data class TxFailure(val charUuid: java.util.UUID, val status: Int, val attempts: Int)

    /** Clear any latched write failure. The OTA engine calls this before
     *  starting a session so a stale error from earlier doesn't trip it. */
    fun clearTxFailure() { _txFailure.value = null }

    // ── Internal ──────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var scanJob: Job? = null
    private var currentMtu: Int = 23
    private var chunkPayloadSize: Int = 23 - BleFraming.HEADER_BYTES - BleFraming.ATT_OVERHEAD

    /** Per-characteristic reassemblers — chunked notifications must not interleave.
     *  OTA_COMMAND is included so device-side OtaStatus replies are reassembled
     *  during a firmware update session. */
    private val reassemblers = mapOf(
        BleConstants.CHR_CONFIG_RW     to BleFraming.Reassembler(),
        BleConstants.CHR_MESH_RX       to BleFraming.Reassembler(),
        BleConstants.CHR_DEVICE_STATUS to BleFraming.Reassembler(),
        BleConstants.CHR_ADMIN         to BleFraming.Reassembler(),
        BleConstants.CHR_OTA_COMMAND   to BleFraming.Reassembler(),
    )

    /** Outbound queue serialized to prevent GATT op overlap. */
    private val txQueue = ConcurrentLinkedDeque<TxOp>()
    @Volatile private var txInFlight = false
    @Volatile private var currentTxOp: TxOp? = null
    /** Guards the txInFlight check/set and the writeCharacteristic call so two
     *  threads can't both pass the `if (txInFlight) return` check and then
     *  issue overlapping writes (Android refuses with rc=201 BUSY). */
    private val pumpLock = Any()

    private data class TxOp(
        val charUuid: java.util.UUID,
        val chunk: ByteArray,
        val writeNoResponse: Boolean,
        var retriesLeft: Int = 3,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Whether the runtime BLE / location permissions required for connection are granted. */
    fun hasRequiredPermissions(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        // FINE_LOCATION is no longer required on Android 12+ if SCAN is requested with
        // neverForLocation, but some OEMs still gate scanning behind it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun bondedAddress(): String? = prefs.bondedAddress()

    /** Begin scanning for AirTAK advertisements (initial pair flow). */
    fun startScan() {
        if (!hasRequiredPermissions() || adapter?.isEnabled != true) return
        if (_linkState.value is LinkState.Scanning) return
        // Do not clobber an active connection — scanning while GATT is open can disrupt
        // the link on some chipsets, and the UI state machine would lose its Connected state.
        if (_linkState.value is LinkState.Connected) return
        _linkState.value = LinkState.Scanning
        _discovered.value = emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        scanJob = scope.launch {
            delay(30_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_linkState.value is LinkState.Scanning) _linkState.value = LinkState.Disconnected
    }

    /** Connect to a previously discovered or bonded device by MAC address. */
    fun connect(address: String, rememberAsBond: Boolean = true) {
        stopScan()
        reconnectJob?.cancel()
        if (rememberAsBond) prefs.setBondedAddress(address)
        val device = adapter?.getRemoteDevice(address) ?: run {
            _linkState.value = LinkState.Failed("Bluetooth unavailable")
            return
        }
        openGatt(device, attempt = 0)
    }

    /** Reconnect to the bonded device, if any. */
    fun reconnectBonded() {
        val addr = prefs.bondedAddress() ?: return
        connect(addr, rememberAsBond = false)
    }

    /** Disconnect (user-initiated; foreground service is dismissed). */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _linkState.value = LinkState.Disconnected
    }

    /** Forget the bonded device: disconnect + clear the stored address. */
    fun forget() {
        prefs.setBondedAddress(null)
        disconnect()
    }

    /** Current GATT instance — exposed for OTA firmware update transport. */
    fun getGatt(): BluetoothGatt? = gatt

    /** Send a payload on a writable characteristic. Returns immediately; transfer is async.
     *
     *  - [OutboundChannel.OtaData] is special: firmware OTA chunks are written raw
     *    (no 6-byte AirTAK chunk header, no further splitting) because the ESP32
     *    `OtaDataCallbacks::onWrite` handler feeds each BLE write straight into
     *    `esp_ota_write`. The caller is responsible for sizing each chunk to fit
     *    the negotiated MTU (typically `mtu - 3`).
     *  - All other channels go through the chunk-framed envelope. */
    fun send(channel: OutboundChannel, payload: ByteArray) {
        if (_linkState.value !is LinkState.Connected) return
        val uuid = when (channel) {
            OutboundChannel.ConfigRw   -> BleConstants.CHR_CONFIG_RW
            OutboundChannel.MeshTx     -> BleConstants.CHR_MESH_TX
            OutboundChannel.Admin      -> BleConstants.CHR_ADMIN
            OutboundChannel.OtaCommand -> BleConstants.CHR_OTA_COMMAND
            OutboundChannel.OtaData    -> BleConstants.CHR_OTA_DATA
        }
        // OTA_DATA uses write-WITH-response so the BLE stack paces chunks to
        // the firmware's `esp_ota_write` throughput (flash erase+write is ~10–30
        // ms per chunk on ESP32-S3). Write-without-response would let Android
        // queue ~100 writes and silently drop the rest once the controller's
        // link-layer buffer fills, leaving the device with a half-written image.
        val noResponse = channel == OutboundChannel.MeshTx
        if (channel == OutboundChannel.OtaData) {
            // Raw, unframed write — the firmware treats each ATT payload as a
            // complete OTA chunk. Serialize through txQueue for flow control.
            txQueue.add(TxOp(uuid, payload, noResponse))
        } else {
            val chunks = BleFraming.encode(payload, chunkPayloadSize)
            chunks.forEach { txQueue.add(TxOp(uuid, it, noResponse)) }
        }
        pumpTx()
    }

    /** Effective ATT payload size for OTA_DATA chunks at the negotiated MTU.
     *  Capped at 512 bytes because that is the Android-side maximum for a
     *  `BluetoothGattCharacteristic` value, regardless of MTU. Writes larger
     *  than 512 throw "value should not be longer than max length of an
     *  attribute value" before they ever reach the air. */
    fun otaDataChunkSize(): Int =
        (currentMtu - BleFraming.ATT_OVERHEAD).coerceIn(1, 512)

    /** Suspend until the outbound queue has drained (all writes acked) or the
     *  timeout elapses. Returns true if drained, false on timeout. Used by the
     *  OTA engine to flush pending chunks before requesting a status snapshot
     *  or finalizing the update. */
    suspend fun awaitTxIdle(timeoutMs: Long = 30_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (txQueue.isNotEmpty() || txInFlight) {
            if (System.currentTimeMillis() > deadline) return false
            if (_linkState.value !is LinkState.Connected) return false
            kotlinx.coroutines.delay(20)
        }
        return true
    }

    /** Depth of the pending outbound write queue (excluding the in-flight op).
     *  The OTA engine uses this for cooperative backpressure so it doesn't
     *  queue thousands of OTA_DATA chunks ahead of an OTA_COMMAND status
     *  request that needs to make round-trip progress. */
    fun txQueueDepth(): Int = txQueue.size

    enum class OutboundChannel { ConfigRw, MeshTx, Admin, OtaCommand, OtaData }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private fun openGatt(device: BluetoothDevice, attempt: Int) {
        _linkState.value = LinkState.Connecting(device.address)
        // autoConnect=false provides faster initial connect; reconnect uses our own backoff.
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Android caches the remote GATT database per-bond. When the
                    // firmware adds new characteristics (e.g. OTA_COMMAND /
                    // OTA_DATA) the bonded phone keeps using the stale cache and
                    // writes to those handles silently no-op. Force a refresh
                    // BEFORE service discovery so we always see the live schema.
                    refreshGattCache(g)
                    // Request the largest supported MTU; the device may grant less.
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txQueue.clear()
                    txInFlight = false
                    currentTxOp = null
                    reassemblers.values.forEach { it.reset() }
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    onLinkLost(g.device.address, status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            chunkPayloadSize = (currentMtu - BleFraming.HEADER_BYTES - BleFraming.ATT_OVERHEAD)
                .coerceAtLeast(1)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _linkState.value = LinkState.Failed("Service discovery failed (status=$status)")
                g.disconnect()
                return
            }
            val service = g.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                _linkState.value = LinkState.Failed("AirTAK GATT service not present")
                g.disconnect()
                return
            }
            // Warn loudly if expected characteristics are missing. This used
            // to silently fail when the bonded phone held a stale GATT cache
            // from before OTA_COMMAND / OTA_DATA were added.
            val missing = BleConstants.ALL_CHARS.filter { service.getCharacteristic(it) == null }
            if (missing.isNotEmpty()) {
                android.util.Log.w(
                    "BleRepository",
                    "GATT schema missing ${missing.size} characteristic(s): " +
                        missing.joinToString { it.toString().take(8) } +
                        " — phone is likely using a stale cache. Unpair the device and reconnect."
                )
            }
            // Enable notifications on every notify-capable characteristic.
            scope.launch {
                BleConstants.NOTIFY_CHARS.forEach { uuid ->
                    service.getCharacteristic(uuid)?.let { enableSubscription(g, it) }
                    delay(40)
                }
                _linkState.value = LinkState.Connected(g.device.address, currentMtu)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleInbound(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleInbound(ch.uuid, value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            val op = currentTxOp
            currentTxOp = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.w(
                    "BleRepository",
                    "Write to ${ch.uuid} failed: status=$status " +
                        "(5=INSUFFICIENT_AUTHENTICATION, 8=INSUFFICIENT_ENCRYPTION, " +
                        "15=INSUFFICIENT_AUTHORIZATION, 133=GATT_ERROR, 137=GATT_AUTH_FAIL)",
                )
                // Re-queue the failed op at the FRONT so the data stream stays
                // in order. Without this, transient errors (controller busy /
                // peer flow-control) silently drop bytes — fatal for OTA where
                // even one missing chunk corrupts the image.
                if (op != null && op.retriesLeft > 0) {
                    op.retriesLeft -= 1
                    txQueue.addFirst(op)
                } else if (op != null) {
                    // Retries exhausted — surface the failure to callers and
                    // drain the queue. Continuing would lose bytes silently
                    // and produce a truncated firmware image at finalize.
                    android.util.Log.e(
                        "BleRepository",
                        "Giving up on ${op.charUuid} after retries (last status=$status). " +
                            "Dropping ${txQueue.size} queued op(s).",
                    )
                    _txFailure.value = TxFailure(op.charUuid, status, attempts = 4 - op.retriesLeft)
                    txQueue.clear()
                }
            }
            txInFlight = false
            pumpTx()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.w(
                    "BleRepository",
                    "CCCD write on ${descriptor.characteristic.uuid} failed: status=$status",
                )
            }
        }
    }

    private fun enableSubscription(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BleConstants.CCC_DESCRIPTOR) ?: return
        // CONFIG_RW and ADMIN are INDICATE-only on the firmware (NimBLE NIMBLE_PROPERTY::INDICATE).
        // NimBLE checks the CCCD indication bit (0x0002) before sending an indication; if only the
        // notification bit (0x0001) is set the indication is silently dropped and no read-response
        // or ConfigAck ever reaches the Android side.  Use ENABLE_INDICATION_VALUE for any
        // characteristic that advertises the INDICATE property, ENABLE_NOTIFICATION_VALUE otherwise.
        val cccValue = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, cccValue)
        } else {
            cccd.value = cccValue
            g.writeDescriptor(cccd)
        }
    }

    private fun handleInbound(uuid: java.util.UUID, frame: ByteArray) {
        val reassembler = reassemblers[uuid] ?: return
        val payload = reassembler.feed(frame) ?: return
        val channel = when (uuid) {
            BleConstants.CHR_CONFIG_RW     -> InboundChannel.ConfigRw
            BleConstants.CHR_MESH_RX       -> InboundChannel.MeshRx
            BleConstants.CHR_DEVICE_STATUS -> InboundChannel.DeviceStatus
            BleConstants.CHR_ADMIN         -> InboundChannel.Admin
            BleConstants.CHR_OTA_COMMAND   -> InboundChannel.OtaCommand
            else -> return
        }
        _inbound.tryEmit(InboundFrame(channel, payload))
    }

    private fun pumpTx() {
        // Serialize the check-set of txInFlight together with the actual
        // writeCharacteristic call. Without this lock, multiple coroutines
        // calling enqueueTx → pumpTx concurrently all see txInFlight=false,
        // all set it true, and all hit Android's GATT layer simultaneously —
        // which returns rc=201 (ERROR_GATT_WRITE_REQUEST_BUSY) for all but
        // one and stalls the queue (the survivor's callback fires, but the
        // others left phantom state behind).
        val op: TxOp
        val g: BluetoothGatt
        val ch: BluetoothGattCharacteristic
        val writeType: Int
        synchronized(pumpLock) {
            if (txInFlight) return
            g = gatt ?: return
            op = txQueue.poll() ?: return
            val service = g.getService(BleConstants.SERVICE_UUID) ?: return
            ch = service.getCharacteristic(op.charUuid) ?: run {
                android.util.Log.e(
                    "BleRepository",
                    "Cannot write ${op.charUuid}: characteristic not present in remote GATT db " +
                        "(stale cache?). Forgetting bond and reconnecting may resolve this.",
                )
                return
            }
            writeType = if (op.writeNoResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            txInFlight = true
            currentTxOp = op
        }
        val writeResult = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(ch, op.chunk, writeType)
                if (rc == BluetoothGatt.GATT_SUCCESS) rc
                else throw IllegalStateException("writeCharacteristic returned rc=$rc")
            } else {
                ch.writeType = writeType
                ch.value = op.chunk
                @Suppress("DEPRECATION")
                if (g.writeCharacteristic(ch)) BluetoothGatt.GATT_SUCCESS
                else throw IllegalStateException("writeCharacteristic returned false")
            }
        }
        writeResult.onFailure { e ->
            // e.g. IllegalArgumentException("value should not be longer than
            // max length of an attribute value") when a caller asks to write
            // more than 512 bytes. The framework throws before delivering an
            // onCharacteristicWrite callback, so we must un-block the queue
            // ourselves or every subsequent write deadlocks.
            android.util.Log.e(
                "BleRepository",
                "writeCharacteristic(${op.charUuid}, ${op.chunk.size} bytes) threw: ${e.message}",
            )
            currentTxOp = null
            if (op.retriesLeft > 0) {
                op.retriesLeft -= 1
                txQueue.addFirst(op)
                scope.launch {
                    delay(20)
                    synchronized(pumpLock) { txInFlight = false }
                    pumpTx()
                }
            } else {
                android.util.Log.e(
                    "BleRepository",
                    "Giving up on ${op.charUuid} after retries (sync failure: ${e.message}). " +
                        "Dropping ${txQueue.size} queued op(s).",
                )
                _txFailure.value = TxFailure(op.charUuid, status = -1, attempts = 4 - op.retriesLeft)
                txQueue.clear()
                synchronized(pumpLock) { txInFlight = false }
                pumpTx()
            }
        }
    }

    /** Invoke the hidden `BluetoothGatt.refresh()` via reflection. This wipes
     *  Android's per-bond GATT attribute cache so the next `discoverServices()`
     *  re-reads the live schema from the device. Required after the firmware
     *  changes its set of characteristics (e.g. adding OTA_COMMAND / OTA_DATA)
     *  without bumping the GAP Service-Changed indication. */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching {
            val m = g.javaClass.getMethod("refresh")
            val ok = m.invoke(g) as? Boolean ?: false
            android.util.Log.d("BleRepository", "BluetoothGatt.refresh() = $ok")
        }.onFailure {
            android.util.Log.w("BleRepository", "BluetoothGatt.refresh() not available: ${it.message}")
        }
    }

    // ── Reconnect backoff (1s, 2s, 4s … 60s) ──────────────────────────────────
    private var backoffAttempt = 0
    private fun onLinkLost(address: String, @Suppress("UNUSED_PARAMETER") status: Int) {
        if (prefs.bondedAddress() != address) {
            _linkState.value = LinkState.Disconnected
            return
        }
        val delayMs = (1_000L shl backoffAttempt.coerceAtMost(6)).coerceAtMost(60_000L)
        _linkState.value = LinkState.Reconnecting(address, delayMs)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            backoffAttempt++
            adapter?.getRemoteDevice(address)?.let { openGatt(it, backoffAttempt) }
        }
    }

    init {
        // Reset backoff each time a link is successfully established (§11.10).
        scope.launch {
            linkState.collect { state ->
                if (state is LinkState.Connected) backoffAttempt = 0
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val entry = DiscoveredDevice(
                address = device.address,
                name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull(),
                rssi = result.rssi,
            )
            val current = _discovered.value
            val updated = current.filterNot { it.address == entry.address } + entry
            _discovered.value = updated.sortedByDescending { it.rssi }
        }
    }
}
