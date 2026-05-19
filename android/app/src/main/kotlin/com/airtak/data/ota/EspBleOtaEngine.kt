// AirTAK Android — data/ota/EspBleOtaEngine.kt
// Planning: §4.3 | Roadmap: 6.1 (revised)
//
// Custom BLE OTA engine for ESP32 devices. Sends firmware chunks via
// the OTA_COMMAND and OTA_DATA BLE characteristics using the AirTAK
// custom OTA protocol with CRC32 end-to-end validation.

package com.airtak.data.ota

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.airtak.data.ble.BleConstants
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class EspBleOtaEngine(
    private val bluetoothGatt: BluetoothGatt
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    companion object {
        private const val CHUNK_SIZE = 514 // MTU(517) - 3
        private const val STATUS_EVERY_N_CHUNKS = 50
    }

    private var deviceInfo: DeviceInfo = DeviceInfo("unknown", "ESP32-S3", "unknown")

    override suspend fun initialize(): Result<DeviceInfo> {
        // Read DEVICE_STATUS via BLE
        val statusChar = bluetoothGatt.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHR_DEVICE_STATUS)
        if (statusChar != null) {
            bluetoothGatt.readCharacteristic(statusChar)
        }
        return Result.success(deviceInfo)
    }

    override suspend fun transfer(
        firmware: ByteArray,
        onProgress: (UpdateProgress) -> Unit
    ): Result<Unit> {
        val otaService = bluetoothGatt.getService(BleConstants.SERVICE_UUID)
            ?: return Result.failure(Exception("AirTAK service not found"))

        val otaCmdChar = otaService.getCharacteristic(BleConstants.CHR_OTA_COMMAND)
            ?: return Result.failure(Exception("OTA_COMMAND characteristic not found"))

        val otaDataChar = otaService.getCharacteristic(BleConstants.CHR_OTA_DATA)
            ?: return Result.failure(Exception("OTA_DATA characteristic not found"))

        val crc = CRC32().apply { update(firmware) }.value

        val reporter = ProgressReporter(
            totalBytes = firmware.size.toLong(),
            onProgress = onProgress,
            statusEveryNChunks = STATUS_EVERY_N_CHUNKS
        )
        reporter.start()

        // Step 1: Send OtaStart
        onProgress(UpdateProgress.Initializing("Starting OTA..."))
        val startPayload = buildOtaCommand(
            tag = 1, // start
            imageSize = firmware.size.toLong(),
            crc32 = crc
        )
        otaCmdChar.value = startPayload
        otaCmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!bluetoothGatt.writeCharacteristic(otaCmdChar)) {
            return Result.failure(Exception("Failed to send OtaStart"))
        }

        // Step 2: Send firmware chunks via OTA_DATA
        var offset = 0
        var chunkCount = 0
        while (offset < firmware.size) {
            val chunkSize = CHUNK_SIZE.coerceAtMost(firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + chunkSize)

            otaDataChar.value = chunk
            otaDataChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!bluetoothGatt.writeCharacteristic(otaDataChar)) {
                return Result.failure(Exception("Failed to write chunk at offset $offset"))
            }

            reporter.update(chunkSize)
            offset += chunkSize
            chunkCount++

            if (chunkCount % STATUS_EVERY_N_CHUNKS == 0) {
                // Request status
                val statusReq = buildOtaCommand(tag = 3) // status request
                otaCmdChar.value = statusReq
                otaCmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                bluetoothGatt.writeCharacteristic(otaCmdChar)
            }
        }

        // Step 3: Send OtaFinish
        onProgress(UpdateProgress.Initializing("Finalizing OTA..."))
        val finishPayload = buildOtaCommand(
            tag = 2, // finish
            crc32 = crc
        )
        otaCmdChar.value = finishPayload
        otaCmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!bluetoothGatt.writeCharacteristic(otaCmdChar)) {
            return Result.failure(Exception("Failed to send OtaFinish"))
        }

        return Result.success(Unit)
    }

    override suspend fun finalize(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        return Result.success("unknown")
    }

    private fun buildOtaCommand(
        tag: Int,
        imageSize: Long = 0,
        crc32: Long = 0
    ): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        when (tag) {
            1 -> {
                // OtaStart { image_size, crc32 }
                // Field 1 (start): wire type 2 (length-delimited), tag 1
                buf.put(0x0A.toByte()) // field 1, type 2
                buf.put(0x0C.toByte()) // length 12
                // OtaStart.image_size: field 1, varint
                buf.put(0x08.toByte())
                putVarint(buf, imageSize)
                // OtaStart.crc32: field 2, varint
                buf.put(0x10.toByte())
                putVarint(buf, crc32)
            }
            2 -> {
                // OtaFinish { crc32 }
                buf.put(0x12.toByte()) // field 2, type 2
                buf.put(0x05.toByte()) // length 5
                // OtaFinish.crc32: field 1, varint
                buf.put(0x08.toByte())
                putVarint(buf, crc32)
            }
            3 -> {
                // OtaStatusRequest
                buf.put(0x1A.toByte()) // field 3, type 2
                buf.put(0x00.toByte()) // length 0
            }
            4 -> {
                // OtaCancel
                buf.put(0x22.toByte()) // field 4, type 2
                buf.put(0x00.toByte()) // length 0
            }
        }
        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    private fun putVarint(buf: ByteBuffer, value: Long) {
        var v = value
        while (v >= 0x80) {
            buf.put(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        buf.put(v.toByte())
    }
}
