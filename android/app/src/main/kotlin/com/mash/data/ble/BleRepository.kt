// MASH Android — data/ble/BleRepository.kt
// Planning: §11.3 | Roadmap: 3.1, 3.2
//
// Manages BLE scan → pair → connect → reconnect lifecycle.
// Reconnect strategy: exponential backoff (1 s, 2 s, 4 s, … capped at 60 s).
// All BLE callbacks are bridged to Kotlin Flows for consumption by ViewModels.
//
// SECURITY: MASH firmware uses LE Secure Connections + Numeric Comparison (§10.6).
// Android handles LESC pairing automatically when the remote requests it; the user
// confirms the 6-digit passkey on both the phone and E-Ink display.
// No raw credentials are logged or stored outside the Android bond store.

package com.mash.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// MASH GATT UUIDs (must match firmware/include/config.h).
private val MASH_SERVICE_UUID   = UUID.fromString("4d415348-0000-1000-8000-00805f9b34fb")
private val CHAR_CONFIG_RW_UUID = UUID.fromString("4d415348-0001-1000-8000-00805f9b34fb")
private val CHAR_MESH_TX_UUID   = UUID.fromString("4d415348-0002-1000-8000-00805f9b34fb")
private val CHAR_MESH_RX_UUID   = UUID.fromString("4d415348-0003-1000-8000-00805f9b34fb")
private val CHAR_STATUS_UUID    = UUID.fromString("4d415348-0004-1000-8000-00805f9b34fb")
private val CHAR_ADMIN_UUID     = UUID.fromString("4d415348-0005-1000-8000-00805f9b34fb")

// Android BLE descriptor for enabling notifications / indications.
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class ConnectionState { SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

@Singleton
@SuppressLint("MissingPermission") // BLE permissions are declared in AndroidManifest.xml
class BleRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _meshRxFlow      = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _deviceStatus    = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)

    private var gatt: BluetoothGatt? = null
    private var mtu: Int = 23 // default BLE ATT MTU; upgraded to 517 after connection

    // Reconnect backoff state.
    private var reconnectDelay = 1_000L // ms

    // ── Public Flows ──────────────────────────────────────────────────────────

    fun connectionStateFlow(): StateFlow<ConnectionState> = _connectionState.asStateFlow()
    fun meshRxFlow(): SharedFlow<ByteArray>    = _meshRxFlow.asSharedFlow()
    fun deviceStatusFlow(): SharedFlow<ByteArray> = _deviceStatus.asSharedFlow()

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BleRepository.gatt = gatt
                _connectionState.value  = ConnectionState.CONNECTING
                gatt.requestMtu(517)  // MASH firmware requires MTU 517 (§10.1)
            } else {
                this@BleRepository.gatt?.close()
                this@BleRepository.gatt = null
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleRepository.mtu = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect(); return
            }
            // Enable notifications on MESH_RX and DEVICE_STATUS.
            enableNotify(gatt, CHAR_MESH_RX_UUID)
            enableNotify(gatt, CHAR_STATUS_UUID)
            _connectionState.value = ConnectionState.CONNECTED
            reconnectDelay = 1_000L // reset backoff on successful connect
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                CHAR_MESH_RX_UUID -> scope.launch { _meshRxFlow.emit(value.clone()) }
                CHAR_STATUS_UUID  -> scope.launch { _deviceStatus.emit(value.clone()) }
            }
        }

        // Required for API < 33 compatibility.
        @Deprecated("Use onCharacteristicChanged(gatt, char, value) on API 33+")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Pending write completions are handled via semaphores in the write helpers below.
            pendingWriteResult?.invoke(status == BluetoothGatt.GATT_SUCCESS)
            pendingWriteResult = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // No-op: descriptor writes for CCCD are fire-and-forget.
        }
    }

    // Callback slot for sequencing characteristic writes.
    @Volatile private var pendingWriteResult: ((Boolean) -> Unit)? = null

    // ── BLE setup helpers ─────────────────────────────────────────────────────

    private fun enableNotify(gatt: BluetoothGatt, charUuid: UUID) {
        val char = gatt.getService(MASH_SERVICE_UUID)?.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CCCD_UUID) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        // API 33+: writeDescriptor(descriptor, value); older: descriptor.value = value; writeDescriptor
        try {
            gatt.writeDescriptor(cccd, value) // API 33
        } catch (_: NoSuchMethodError) {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /** Begin scanning for MASH devices and connect to the first one found. */
    fun connect() {
        _connectionState.value = ConnectionState.SCANNING
        val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.DISCONNECTED; return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MASH_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var scanCallback: ScanCallback? = null
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                scanCallback = null
                _connectionState.value = ConnectionState.CONNECTING
                result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
        scanner.startScan(listOf(filter), settings, scanCallback!!)
    }

    /** Disconnect from the current device cleanly. */
    fun disconnect() {
        gatt?.disconnect()
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 60_000L) // cap at 60 s
            connect()
        }
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Write a decoded meshtastic.Data protobuf to the MESH_TX characteristic (§10.3).
     * Uses the common 6-byte framing header and WRITE_WITHOUT_RESPONSE.
     * Returns true if the write was submitted successfully.
     */
    suspend fun writeMeshTx(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = g.getService(MASH_SERVICE_UUID)?.getCharacteristic(CHAR_MESH_TX_UUID) ?: return false
        val chunks = BleFraming.encode(data, mtu - 3 - 6)
        for (chunk in chunks) {
            if (!writeCharacteristicNoResponse(g, char, chunk)) return false
        }
        return true
    }

    /**
     * Write nanopb-encoded ConfigPayload bytes to CONFIG_RW (§10.3).
     * Uses BleFraming.encode() to split into MTU-sized chunks. Each chunk is written
     * with WRITE (requires response) to guarantee delivery ordering.
     */
    suspend fun writeConfig(pbData: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = g.getService(MASH_SERVICE_UUID)?.getCharacteristic(CHAR_CONFIG_RW_UUID) ?: return false
        val chunks = BleFraming.encode(pbData, mtu - 3 - 6) // ATT overhead + frame header
        for (chunk in chunks) {
            if (!writeCharacteristicWithResponse(g, char, chunk)) return false
        }
        return true
    }

    /**
     * Write nanopb-encoded AdminRequest bytes to ADMIN characteristic (§10.5).
     * Single write, no framing required (AdminRequest ≤ 128 bytes).
     */
    suspend fun writeAdmin(pbData: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = g.getService(MASH_SERVICE_UUID)?.getCharacteristic(CHAR_ADMIN_UUID) ?: return false
        return writeCharacteristicWithResponse(g, char, pbData)
    }

    // ── Internal write primitives ─────────────────────────────────────────────

    private suspend fun writeCharacteristicNoResponse(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return try {
            gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            true // fire-and-forget; no ACK from peripheral
        } catch (_: NoSuchMethodError) {
            // API < 33 fallback
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private suspend fun writeCharacteristicWithResponse(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        var result = false
        val resultChannel = kotlinx.coroutines.channels.Channel<Boolean>(1)
        pendingWriteResult = { success -> scope.launch { resultChannel.trySend(success) } }
        try {
            val submitted = try {
                gatt.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                true
            } catch (_: NoSuchMethodError) {
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            if (submitted == true) {
                result = resultChannel.receiveCatching().getOrDefault(false)
            }
        } finally {
            pendingWriteResult = null
        }
        return result
    }
}

