// AirTAK Android — data/ota/FirmwareUpdateManager.kt
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Single entry point for firmware updates. Auto-detects transport (USB-C or BLE)
// and device type (nRF52 or ESP32) and selects the correct protocol engine.

package com.airtak.data.ota

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.net.Uri
import com.airtak.data.ble.BleConstants
import com.airtak.data.ble.BleRepository
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
            val vendorId: Int
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
        object Done : UpdateProgress()
    }

    interface FirmwareUpdateEngine {
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
        ): Result<String> {
            onProgress(UpdateProgress.Initializing("Detecting device..."))
            val initResult = engine.initialize()
            if (initResult.isFailure) {
                onProgress(UpdateProgress.Error(
                    "Failed to initialize: ${initResult.exceptionOrNull()?.message}",
                    false
                ))
                return Result.failure(initResult.exceptionOrNull()!!)
            }

            onProgress(UpdateProgress.Initializing("Transferring firmware..."))

            val transferResult = engine.transfer(firmware, onProgress)
            if (transferResult.isFailure) {
                onProgress(UpdateProgress.Error(
                    "Transfer failed: ${transferResult.exceptionOrNull()?.message}",
                    true
                ))
                return Result.failure(transferResult.exceptionOrNull()!!)
            }

            onProgress(UpdateProgress.Initializing("Finalizing..."))
            val finalizeResult = engine.finalize()
            if (finalizeResult.isFailure) {
                onProgress(UpdateProgress.Error(
                    "Finalize failed: ${finalizeResult.exceptionOrNull()?.message}",
                    false
                ))
                return Result.failure(finalizeResult.exceptionOrNull()!!)
            }

            onProgress(UpdateProgress.Rebooting)
            val versionResult = engine.waitForReconnect(30000L)
            if (versionResult.isFailure) {
                onProgress(UpdateProgress.Error(
                    "Device did not reconnect in time.",
                    false
                ))
                return Result.failure(versionResult.exceptionOrNull()!!)
            }

            val newVersion = versionResult.getOrThrow()
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
                    transport.vendorId == 0x239A -> NrfSerialDfuEngine(context, transport.connection, transport.intf)
                    transport.vendorId == 0x303A ||
                        transport.vendorId == 0x10C4 ||
                        transport.vendorId == 0x1A86 -> EspSerialFlasher(transport.connection, transport.intf)
                    else -> return Result.failure(IllegalArgumentException("Unknown USB vendor: ${transport.vendorId}"))
                }
            }
            is Transport.BleGatt -> {
                when {
                    deviceInfo?.platform?.contains("nRF", ignoreCase = true) == true ||
                        deviceInfo?.platform?.contains("NRF", ignoreCase = true) == true ->
                        NrfBleDfuEngine(context, transport.bluetoothGatt)
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
        fun isNordicDfuVendor(vendorId: Int): Boolean = vendorId == 0x239A
        fun isEsp32Vendor(vendorId: Int): Boolean =
            vendorId == 0x303A || vendorId == 0x10C4 || vendorId == 0x1A86
    }
}
