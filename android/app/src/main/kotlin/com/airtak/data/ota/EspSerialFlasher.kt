// AirTAK Android — data/ota/EspSerialFlasher.kt
// Planning: §3.3 | Roadmap: 6.1 (revised)
//
// Implements the esptool SLIP protocol over USB CDC-ACM for flashing
// ESP32 devices (T-Deck, T3 S3) via USB-C cable. Pure Kotlin — no JNI.

package com.airtak.data.ota

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress

class EspSerialFlasher(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    companion object {
        private const val ESP_SYNC = 0x08
        private const val ESP_FLASH_BEGIN = 0x02
        private const val ESP_FLASH_DATA = 0x03
        private const val ESP_FLASH_END = 0x04
        private const val ESP_CHANGE_BAUDRATE = 0x0F
        private const val ESP_SPI_FLASH_PARAMS = 0x0B
        private const val BLOCK_SIZE = 4096
        private const val DEFAULT_BAUD = 115200
        private const val TIMEOUT_MS = 3000
    }

    private var outEndpoint = 0
    private var inEndpoint = 0
    private var sequence = 0

    override suspend fun initialize(): Result<DeviceInfo> {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                    outEndpoint = ep.endpointNumber
                } else {
                    inEndpoint = ep.endpointNumber
                }
            }
        }
        if (outEndpoint == 0 || inEndpoint == 0) {
            return Result.failure(Exception("CDC-ACM endpoints not found"))
        }

        connection.claimInterface(intf, true)

        // Sync sequence: 36 × 0x55
        val syncData = ByteArray(36) { 0x55.toByte() }
        val syncFrame = SlipFramer.encode(
            byteArrayOf(0x00, ESP_SYNC.toByte()) +
            intToBytes(36, 2) + intToBytes(0x0D534552, 4) + syncData
        )
        val syncResult = writeRead(syncFrame)
        if (syncResult == null || syncResult.size < 4 || syncResult[0] != 0x01.toByte()) {
            return Result.failure(Exception("ESP32 sync failed. Hold BOOT, press RESET."))
        }

        // Read SPI flash params
        val flashParamsCmd = SlipFramer.encode(
            byteArrayOf(0x00, ESP_SPI_FLASH_PARAMS.toByte()) + intToBytes(0, 2) + intToBytes(checksum(ByteArray(0)), 4)
        )
        val flashParamsResp = writeRead(flashParamsCmd)

        return Result.success(DeviceInfo(
            boardId = "t_deck",
            platform = "ESP32-S3",
            firmwareVersion = "bootloader"
        ))
    }

    override suspend fun transfer(
        firmware: ByteArray,
        onProgress: (UpdateProgress) -> Unit
    ): Result<Unit> {
        val reporter = ProgressReporter(
            totalBytes = firmware.size.toLong(),
            onProgress = onProgress
        )
        reporter.start()

        // FLASH_BEGIN: erase region
        val flashBeginPayload = intToBytes(0x10000, 4) +  // offset 0x10000
            intToBytes(firmware.size, 4) +  // size
            intToBytes(BLOCK_SIZE, 4)  // block size
        sendEspCommand(ESP_FLASH_BEGIN, flashBeginPayload)
        readEspResponse()

        // FLASH_DATA: send blocks
        var offset = 0
        while (offset < firmware.size) {
            val remaining = firmware.size - offset
            val chunkSize = BLOCK_SIZE.coerceAtMost(remaining)
            val chunk = firmware.copyOfRange(offset, offset + chunkSize)
            val paddedChunk = if (chunkSize < BLOCK_SIZE) {
                chunk + ByteArray(BLOCK_SIZE - chunkSize) { 0xFF.toByte() }
            } else {
                chunk
            }

            val dataPayload = intToBytes(paddedChunk.size, 4) +
                intToBytes(sequence, 4) +
                byteArrayOf(0x00, 0x00, 0x00, 0x00) +
                paddedChunk

            sendEspCommand(ESP_FLASH_DATA, dataPayload)
            readEspResponse()
            reporter.update(chunkSize)
            offset += chunkSize
            sequence++
        }

        // FLASH_END without reboot for now
        sendEspCommand(ESP_FLASH_END, intToBytes(0, 4))
        readEspResponse()

        return Result.success(Unit)
    }

    override suspend fun finalize(): Result<Unit> {
        // Reboot
        sendEspCommand(ESP_FLASH_END, intToBytes(1, 4))
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        return Result.success("unknown")
    }

    private fun sendEspCommand(opcode: Int, payload: ByteArray) {
        val cmdPayload = byteArrayOf(0x00, opcode.toByte()) +
            intToBytes(payload.size, 2) +
            intToBytes(checksum(payload), 4) +
            payload
        val frame = SlipFramer.encode(cmdPayload)
        writeRead(frame)
    }

    private fun readEspResponse(): ByteArray? {
        return readResponse()
    }

    private fun writeRead(data: ByteArray): ByteArray? {
        val result = connection.bulkTransfer(
            android.hardware.usb.UsbEndpoint(null, outEndpoint),
            data, data.size, TIMEOUT_MS
        )
        if (result < 0) return null
        return readResponse()
    }

    private fun readResponse(): ByteArray? {
        val buffer = ByteArray(1024)
        val result = connection.bulkTransfer(
            android.hardware.usb.UsbEndpoint(null, inEndpoint),
            buffer, buffer.size, TIMEOUT_MS
        )
        if (result < 0) return null
        return SlipFramer.decode(buffer.copyOf(result)).firstOrNull()
    }

    private fun intToBytes(value: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            out[i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun checksum(data: ByteArray): Int {
        var checksum = 0xEF.toInt()
        for (b in data) {
            checksum = checksum xor (b.toInt() and 0xFF)
        }
        return checksum
    }
}
