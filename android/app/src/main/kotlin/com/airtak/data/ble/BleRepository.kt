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
import java.util.concurrent.ConcurrentLinkedQueue
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

    // ── Internal ──────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var scanJob: Job? = null
    private var currentMtu: Int = 23
    private var chunkPayloadSize: Int = 23 - BleFraming.HEADER_BYTES - BleFraming.ATT_OVERHEAD

    /** Per-characteristic reassemblers — chunked notifications must not interleave. */
    private val reassemblers = mapOf(
        BleConstants.CHR_CONFIG_RW     to BleFraming.Reassembler(),
        BleConstants.CHR_MESH_RX       to BleFraming.Reassembler(),
        BleConstants.CHR_DEVICE_STATUS to BleFraming.Reassembler(),
        BleConstants.CHR_ADMIN         to BleFraming.Reassembler(),
    )

    /** Outbound queue serialized to prevent GATT op overlap. */
    private val txQueue = ConcurrentLinkedQueue<TxOp>()
    @Volatile private var txInFlight = false

    private data class TxOp(val charUuid: java.util.UUID, val chunk: ByteArray, val writeNoResponse: Boolean)

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

    /** Send a payload on a writable characteristic. Returns immediately; transfer is async. */
    fun send(channel: OutboundChannel, payload: ByteArray) {
        if (_linkState.value !is LinkState.Connected) return
        val uuid = when (channel) {
            OutboundChannel.ConfigRw -> BleConstants.CHR_CONFIG_RW
            OutboundChannel.MeshTx   -> BleConstants.CHR_MESH_TX
            OutboundChannel.Admin    -> BleConstants.CHR_ADMIN
        }
        val noResponse = channel == OutboundChannel.MeshTx
        val chunks = BleFraming.encode(payload, chunkPayloadSize)
        chunks.forEach { txQueue.add(TxOp(uuid, it, noResponse)) }
        pumpTx()
    }

    enum class OutboundChannel { ConfigRw, MeshTx, Admin }

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
                    // Request the largest supported MTU; the device may grant less.
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txQueue.clear()
                    txInFlight = false
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
            txInFlight = false
            pumpTx()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) { /* notifications enable serially via delay() in onServicesDiscovered */ }
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
            else -> return
        }
        _inbound.tryEmit(InboundFrame(channel, payload))
    }

    private fun pumpTx() {
        if (txInFlight) return
        val g = gatt ?: return
        val op = txQueue.poll() ?: return
        val service = g.getService(BleConstants.SERVICE_UUID) ?: return
        val ch = service.getCharacteristic(op.charUuid) ?: return
        val writeType = if (op.writeNoResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        txInFlight = true
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, op.chunk, writeType)
        } else {
            ch.writeType = writeType
            ch.value = op.chunk
            g.writeCharacteristic(ch)
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
