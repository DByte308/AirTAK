// AirTAK Android — data/ota/FirmwareUpdateManager.kt
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Single entry point for firmware updates. Auto-detects transport (USB-C or BLE)
// and device type (nRF52 or ESP32) and selects the correct protocol engine.

package com.airtak.data.ota

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.net.Uri
import android.util.Log
import com.airtak.config.constants.OTA_RECONNECT_TIMEOUT_MS
import com.airtak.config.constants.VID_ESP32_NATIVE
import com.airtak.config.constants.VID_NRF52840
import com.airtak.config.constants.VID_SILABS_CP210X
import com.airtak.config.constants.VID_QINHENG_CH340
import com.airtak.data.ble.BleConstants
import com.airtak.data.ble.BleRepository
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow

class FirmwareUpdateManager(
    private val context: Context
) {
    data class DeviceInfo(
        val boardId: String,
        val platform: String,
        val firmwareVersion: String
    )

    sealed class Transport {
        data class UsbCdc(
            val connection: UsbDeviceConnection,
            val intf: UsbInterface,
            val vendorId: Int,
            val usbDevice: UsbDevice,
        ) : Transport()

        data class BleGatt(
            val bluetoothGatt: BluetoothGatt,
            /** Repository used by BLE OTA engines to send framed writes via the
             *  shared serialized tx queue and to observe link state during the
             *  post-flash reconnect window. */
            val repository: BleRepository,
        ) : Transport()
    }

    sealed class UpdateProgress {
        data class Initializing(val step: String) : UpdateProgress()
        data class Transferring(
            val bytesWritten: Long,
            val totalBytes: Long,
            val etaSeconds: Int
        ) : UpdateProgress()
        data class Verifying(val progress: Float) : UpdateProgress()
        object Rebooting : UpdateProgress()
        data class Reconnected(val newVersion: String) : UpdateProgress()
        data class Error(val message: String, val canRetry: Boolean) : UpdateProgress()
        data class Debug(val message: String) : UpdateProgress()
        object Done : UpdateProgress()
    }

    interface FirmwareUpdateEngine {
        var onDebug: ((String) -> Unit)?
        suspend fun initialize(): Result<DeviceInfo>
        suspend fun transfer(
            firmware: ByteArray,
            onProgress: (UpdateProgress) -> Unit
        ): Result<Unit>

        suspend fun finalize(): Result<Unit>
        suspend fun waitForReconnect(timeoutMs: Long): Result<String>
    }

    class UpdateSession(
        val deviceInfo: DeviceInfo,
        val transport: Transport,
        private val engine: FirmwareUpdateEngine
    ) {
        val canRetry: Boolean
            get() = transport is Transport.UsbCdc || transport is Transport.BleGatt

        val requiresUserAction: String?
            get() = when {
                deviceInfo.platform.contains("ESP32", ignoreCase = true) &&
                    transport is Transport.UsbCdc -> "Hold BOOT button and press RESET on your device."
                else -> null
            }

        suspend fun execute(
            firmware: ByteArray,
            onProgress: (UpdateProgress) -> Unit,
            onDebug: ((String) -> Unit)? = null,
        ): Result<String> {
            engine.onDebug = onDebug
            engine.onDebug?.invoke("=== Firmware update starting ===")
            onProgress(UpdateProgress.Initializing("Detecting device..."))
            engine.onDebug?.invoke("Step 1: initialize() — SYNC + SPI_ATTACH + stub upload")
            val initResult = engine.initialize()
            if (initResult.isFailure) {
                engine.onDebug?.invoke("FAILED: ${initResult.exceptionOrNull()?.message}")
                onProgress(UpdateProgress.Error(
                    "Failed to initialize: ${initResult.exceptionOrNull()?.message}",
                    false
                ))
                return Result.failure(initResult.exceptionOrNull()!!)
            }
            engine.onDebug?.invoke("initialize() OK: ${initResult.getOrNull()}")

            onProgress(UpdateProgress.Initializing("Transferring firmware..."))
            engine.onDebug?.invoke("Step 2: transfer() — erase otadata + write app0/app1")

            val transferResult = engine.transfer(firmware, onProgress)
            if (transferResult.isFailure) {
                engine.onDebug?.invoke("FAILED: ${transferResult.exceptionOrNull()?.message}")
                onProgress(UpdateProgress.Error(
                    "Transfer failed: ${transferResult.exceptionOrNull()?.message}",
                    true
                ))
                return Result.failure(transferResult.exceptionOrNull()!!)
            }
            engine.onDebug?.invoke("transfer() OK")

            onProgress(UpdateProgress.Initializing("Finalizing..."))
            engine.onDebug?.invoke("Step 3: finalize() — FLASH_END + reboot")
            val finalizeResult = engine.finalize()
            if (finalizeResult.isFailure) {
                engine.onDebug?.invoke("FAILED: ${finalizeResult.exceptionOrNull()?.message}")
                onProgress(UpdateProgress.Error(
                    "Finalize failed: ${finalizeResult.exceptionOrNull()?.message}",
                    false
                ))
                return Result.failure(finalizeResult.exceptionOrNull()!!)
            }
            engine.onDebug?.invoke("finalize() OK — device should reboot now")

            onProgress(UpdateProgress.Rebooting)
            val versionResult = engine.waitForReconnect(OTA_RECONNECT_TIMEOUT_MS)
            if (versionResult.isFailure) {
                engine.onDebug?.invoke("waitForReconnect: ${versionResult.exceptionOrNull()?.message}")
                onProgress(UpdateProgress.Error(
                    "Device did not reconnect in time.",
                    false
                ))
                return Result.failure(versionResult.exceptionOrNull()!!)
            }

            val newVersion = versionResult.getOrThrow()
            engine.onDebug?.invoke("=== SUCCESS: device reconnected, version=$newVersion ===")
            onProgress(UpdateProgress.Reconnected(newVersion))
            onProgress(UpdateProgress.Done)
            return Result.success(newVersion)
        }
    }

    suspend fun prepareUpdate(
        firmwareUri: Uri,
        transport: Transport? = null,
        deviceInfo: DeviceInfo? = null
    ): Result<UpdateSession> {
        val engine = when (transport) {
            is Transport.UsbCdc -> {
                when {
                    transport.vendorId == VID_NRF52840 -> NrfSerialDfuEngine(context, transport.connection, transport.intf, transport.usbDevice)
                    transport.vendorId == VID_ESP32_NATIVE ||
                        transport.vendorId == VID_SILABS_CP210X ||
                        transport.vendorId == VID_QINHENG_CH340 ->
                        EspSerialFlasher(context, transport.connection, transport.intf, transport.vendorId)
                    else -> return Result.failure(IllegalArgumentException("Unknown USB vendor: ${transport.vendorId}"))
                }
            }
            is Transport.BleGatt -> {
                when {
                    deviceInfo?.platform?.contains("nRF", ignoreCase = true) == true ||
                        deviceInfo?.platform?.contains("NRF", ignoreCase = true) == true ->
                        NrfBleDfuEngine(
                            context = context,
                            ble = transport.repository,
                            deviceAddress = transport.bluetoothGatt.device.address,
                            initialDeviceInfo = deviceInfo,
                        )
                    deviceInfo?.platform?.contains("ESP", ignoreCase = true) == true ->
                        EspBleOtaEngine(transport.repository)
                    else -> return Result.failure(IllegalArgumentException("Unknown platform: ${deviceInfo?.platform}"))
                }
            }
            null -> return Result.failure(IllegalArgumentException("Transport is required"))
        }

        val info = deviceInfo ?: engine.initialize().getOrElse {
            return Result.failure(it)
        }

        return Result.success(UpdateSession(info, transport!!, engine))
    }

    companion object {
        // ── Canonical definitions in config/constants/OtaConstants.kt ──────────
        fun isNordicDfuVendor(vendorId: Int): Boolean = vendorId == VID_NRF52840
        fun isEsp32Vendor(vendorId: Int): Boolean =
            vendorId == VID_ESP32_NATIVE || vendorId == VID_SILABS_CP210X || vendorId == VID_QINHENG_CH340

        /** CI artifacts are ZIP-wrapped (upload-artifact). For ESP32 the
         *  ZIP contains firmware.bin — extract just the .bin payload so we
         *  don't flash ZIP bytes to flash.  Returns the unmodified [bytes]
         *  if the input is not a ZIP or no .bin entry is found. */
        fun extractEsp32FirmwareBin(bytes: ByteArray): ByteArray {
            // ZIP local file header signature: PK\x03\x04
            if (bytes.size < 4 ||
                bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte() ||
                bytes[2] != 0x03.toByte() || bytes[3] != 0x04.toByte()
            ) return bytes
            try {
                Log.d("FirmwareManager", "Detected ZIP firmware — extracting .bin payload (${bytes.size} B zip)")
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        if (entry.name.endsWith(".bin", ignoreCase = true)) {
                            val extracted = zin.readBytes()
                            Log.d("FirmwareManager", "Extracted ${entry.name} (${extracted.size} B) from ZIP")
                            return extracted
                        }
                        zin.closeEntry()
                    }
                }
                Log.w("FirmwareManager", "ZIP contained no .bin entry — falling back to raw bytes")
            } catch (e: Exception) {
                Log.e("FirmwareManager", "ZIP extraction failed: ${e.message} — falling back to raw bytes")
            }
            return bytes
        }
    }
}
