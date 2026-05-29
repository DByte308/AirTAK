// AirTAK Android — ui/firmware/FirmwareUpdateViewModel.kt
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Holds firmware update progress state. Takes a firmware URI, reads bytes,
// executes via FirmwareUpdateManager over the active BLE link, and reports
// progress via StateFlow<UpdateProgress>.

package com.airtak.ui.firmware

import android.app.PendingIntent
import android.bluetooth.BluetoothGatt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.ota.FirmwareUpdateManager
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import com.airtak.data.status.DeviceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.airtak.config.constants.VID_NRF52840
import com.airtak.config.constants.VID_ESP32_NATIVE
import com.airtak.config.constants.VID_SILABS_CP210X
import com.airtak.config.constants.VID_QINHENG_CH340
import com.airtak.config.constants.FW_MIME_OCTET_STREAM
import com.airtak.config.constants.FW_MIME_X_BINARY
import com.airtak.config.constants.FW_MIME_ZIP
import com.airtak.config.constants.FW_MIME_WILDCARD
import kotlin.coroutines.resume

@HiltViewModel
class FirmwareUpdateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ble: BleRepository,
    private val deviceStatusRepo: DeviceStatusRepository,
) : ViewModel() {

    private val manager = FirmwareUpdateManager(appContext)

    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Initializing("Not started"))
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()

    private val _state = MutableStateFlow<FwUpdateState>(FwUpdateState.Idle)
    val state: StateFlow<FwUpdateState> = _state.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    private fun addDebug(msg: String) {
        _debugLog.value = _debugLog.value + msg
    }

    fun startUpdate(firmwareUri: Uri) {
        if (_state.value !is FwUpdateState.Idle) return
        _state.value = FwUpdateState.InProgress

        viewModelScope.launch {
            try {
                val firmwareBytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read firmware file")
                }

                val linkState = ble.linkState.value
                val gatt = when (linkState) {
                    is LinkState.Connected -> ble.getGatt()
                    else -> throw Exception("Device not connected over BLE")
                } ?: throw Exception("BLE GATT not available")

                val status = deviceStatusRepo.status.value
                val platform = status?.platform ?: "unknown"
                val resolvedFirmware = if (platform.contains("ESP", ignoreCase = true))
                    FirmwareUpdateManager.extractEsp32FirmwareBin(firmwareBytes)
                else firmwareBytes
                if (resolvedFirmware.size != firmwareBytes.size) {
                    addDebug("Unwrapped CI ZIP: ${firmwareBytes.size}B → firmware.bin ${resolvedFirmware.size}B")
                }
                val transport = FirmwareUpdateManager.Transport.BleGatt(gatt, ble)
                val deviceInfo = FirmwareUpdateManager.DeviceInfo(
                    boardId = status?.boardId ?: "unknown",
                    platform = status?.platform ?: "unknown",
                    firmwareVersion = status?.firmwareVersion ?: "unknown",
                )

                val session = manager.prepareUpdate(firmwareUri, transport, deviceInfo).getOrThrow()
                val result = withContext(Dispatchers.IO) {
                    session.execute(resolvedFirmware, { _progress.value = it }, ::addDebug)
                }
                result.fold(
                    onSuccess = { _state.value = FwUpdateState.Done },
                    onFailure = { e ->
                        // execute() has already emitted UpdateProgress.Error
                        // via the progress callback; surface it on the state
                        // flow so the screen actually shows the failure.
                        _state.value = FwUpdateState.Error(e.message ?: "Update failed")
                    },
                )
            } catch (e: Exception) {
                _progress.value = UpdateProgress.Error(e.message ?: "Unknown error", canRetry = false)
                _state.value = FwUpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Flash firmware over a USB-C cable. Enumerates connected USB devices,
     *  picks the first one matching a known VID (ESP32 native USB, CP210x,
     *  CH340, or Adafruit nRF52 bootloader), requests USB permission, opens
     *  the CDC data interface, and hands off to FirmwareUpdateManager.
     *
     *  USB and BLE paths are mutually exclusive — once a USB device is found
     *  we don't use the BLE link at all (the engine takes the radio anyway).
     */
    fun startUpdateOverUsb(firmwareUri: Uri) {
        if (_state.value !is FwUpdateState.Idle) return
        _state.value = FwUpdateState.InProgress

        viewModelScope.launch {
            try {
                val firmwareBytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read firmware file")
                }

                val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
                val device = pickSupportedDevice(usbManager)
                    ?: throw Exception(
                        "No supported USB device found. Connect your AirTAK device via USB-C cable " +
                            "and grant permission when prompted."
                    )

                // Request USB permission (no-op if already granted).
                if (!usbManager.hasPermission(device)) {
                    val granted = requestUsbPermission(usbManager, device)
                    if (!granted) throw Exception("USB permission denied")
                }

                val dataIntf = findCdcDataInterface(device)
                    ?: throw Exception("Device exposes no USB CDC data interface")

                val connection = usbManager.openDevice(device)
                    ?: throw Exception("Could not open USB connection")

                val transport = FirmwareUpdateManager.Transport.UsbCdc(
                    connection = connection,
                    intf = dataIntf,
                    vendorId = device.vendorId,
                    usbDevice = device,
                )

                // The engine will read DeviceInfo from the bootloader; we
                // still pass our best guess so the dispatcher can pick the
                // right engine (USB path is fully VID-driven, but the
                // DeviceInfo here is informational).
                val deviceInfo = FirmwareUpdateManager.DeviceInfo(
                    boardId = "usb-${device.vendorId.toString(16)}",
                    platform = if (device.vendorId == VID_NRF52840) "nRF52" else "ESP32",
                    firmwareVersion = "unknown",
                )

                val resolvedFirmware = if (device.vendorId != VID_NRF52840)
                    FirmwareUpdateManager.extractEsp32FirmwareBin(firmwareBytes)
                else firmwareBytes
                if (resolvedFirmware.size != firmwareBytes.size) {
                    addDebug("Unwrapped CI ZIP: ${firmwareBytes.size}B → firmware.bin ${resolvedFirmware.size}B")
                }

                val session = manager.prepareUpdate(firmwareUri, transport, deviceInfo).getOrThrow()
                // USB engines (EspSerialFlasher / NrfSerialDfuEngine) use
                // blocking UsbDeviceConnection.bulkTransfer / controlTransfer
                // calls; running them on the Main dispatcher (the default
                // for viewModelScope) ANRs the app for the duration of the
                // flash. _progress is a StateFlow which is safe to set
                // from any thread.
                val result = withContext(Dispatchers.IO) {
                    session.execute(resolvedFirmware, { _progress.value = it }, ::addDebug)
                }
                result.fold(
                    onSuccess = { _state.value = FwUpdateState.Done },
                    onFailure = { e -> _state.value = FwUpdateState.Error(e.message ?: "Update failed") },
                )
            } catch (e: Exception) {
                _progress.value = UpdateProgress.Error(e.message ?: "Unknown error", canRetry = false)
                _state.value = FwUpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Scan attached USB devices for one that matches a known firmware-flash
     *  VID. Returns null when nothing matches so the caller can show a
     *  hint instead of opening a device picker. */
    private fun pickSupportedDevice(mgr: UsbManager): UsbDevice? {
        val supported = setOf(VID_NRF52840, VID_ESP32_NATIVE, VID_SILABS_CP210X, VID_QINHENG_CH340)
        return mgr.deviceList.values.firstOrNull { it.vendorId in supported }
    }

    /** Locate the CDC data interface (class 0x0A) on the device. Falls
     *  back to interface 1 if no class match — which is the canonical
     *  data-interface index for CDC-ACM composite devices. */
    private fun findCdcDataInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return intf
        }
        // Vendor bridges (CP210x/CH340) don't advertise CDC class — they
        // expose a single vendor-class interface with bulk endpoints.
        return if (device.interfaceCount > 0) device.getInterface(device.interfaceCount - 1) else null
    }

    /** Suspend until the user grants or denies USB permission. */
    private suspend fun requestUsbPermission(mgr: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "com.airtak.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != action) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            val pi = PendingIntent.getBroadcast(
                appContext, 0, Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mgr.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
        }

    fun reset() {
        _progress.value = UpdateProgress.Initializing("Not started")
        _state.value = FwUpdateState.Idle
        _debugLog.value = emptyList()
    }

    sealed class FwUpdateState {
        data object Idle : FwUpdateState()
        data object InProgress : FwUpdateState()
        data object Done : FwUpdateState()
        data class Error(val message: String) : FwUpdateState()
    }

    companion object {
        val SUPPORTED_EXTENSIONS = arrayOf(FW_MIME_OCTET_STREAM, FW_MIME_X_BINARY, FW_MIME_ZIP, FW_MIME_WILDCARD)
    }
}
