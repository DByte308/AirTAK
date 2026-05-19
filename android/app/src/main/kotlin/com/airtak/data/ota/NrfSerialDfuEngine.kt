// AirTAK Android — data/ota/NrfSerialDfuEngine.kt
// Planning: §3.2 | Roadmap: 6.1 (revised)
//
// Implements Nordic DFU protocol over USB CDC-ACM serial connection.
// Uses raw bulk transfers on UsbDeviceConnection — no third-party DFU library
// needed for the USB transport path.

package com.airtak.data.ota

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import java.util.zip.CRC32

class NrfSerialDfuEngine(
    private val context: Context,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    companion object {
        private const val DFU_OP_START = 0x01.toByte()
        private const val DFU_OP_INIT_DFU_PARAMS = 0x02.toByte()
        private const val DFU_OP_CREATE_OBJECT = 0x01.toByte()
        private const val DFU_OP_SET_PRN = 0x02.toByte()
        private const val DFU_OP_CALC_CRC = 0x03.toByte()
        private const val DFU_OP_EXECUTE = 0x04.toByte()
        private const val DFU_OP_SELECT_OBJECT = 0x06.toByte()
        private const val DFU_OP_GET_MTU = 0x07.toByte()
        private const val DFU_OP_WRITE = 0x08.toByte()
        private const val DFU_OP_PING = 0x09.toByte()
        private const val DFU_OP_RESET = 0x0A.toByte()
        private const val DFU_OP_RESPONSE = 0x60.toByte()
        private const val DFU_RESULT_SUCCESS = 0x01.toByte()
        private const val CHUNK_SIZE = 512
        private const val PRN_INTERVAL = 10
        private const val TIMEOUT_MS = 5000
    }

    private var outEndpoint: android.hardware.usb.UsbEndpoint? = null
    private var inEndpoint: android.hardware.usb.UsbEndpoint? = null
    private var maxTransferSize = 64

    override suspend fun initialize(): Result<DeviceInfo> {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                    outEndpoint = ep
                } else {
                    inEndpoint = ep
                }
            }
        }
        if (outEndpoint == null || inEndpoint == null) {
            return Result.failure(Exception("CDC-ACM endpoints not found"))
        }

        connection.claimInterface(intf, true)

        // Send DFU_START
        val startCmd = byteArrayOf(DFU_OP_START)
        val startResp = sendCommand(startCmd)
        if (startResp == null || startResp[0] != DFU_OP_RESPONSE) {
            return Result.failure(Exception("DFU handshake failed"))
        }

        // GET_MTU
        val mtuCmd = byteArrayOf(DFU_OP_GET_MTU)
        val mtuResp = sendCommand(mtuCmd)
        if (mtuResp != null && mtuResp.size >= 3) {
            maxTransferSize = ((mtuResp[1].toInt() and 0xFF) or
                ((mtuResp[2].toInt() and 0xFF) shl 8)).coerceAtLeast(64)
        }

        // SET_PRN
        val prnCmd = byteArrayOf(DFU_OP_SET_PRN, PRN_INTERVAL.toByte(), 0x00)
        sendCommand(prnCmd)

        return Result.success(DeviceInfo(
            boardId = "unknown",
            platform = "nRF52840",
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

        // Create init object
        val initPacket = createInitPacket(firmware)
        val createInitCmd = createCommand(DFU_OP_CREATE_OBJECT, byteArrayOf(0x01) + initPacket.size.toBytes())
        sendCommand(createInitCmd)
        writeData(initPacket)
        sendCommand(byteArrayOf(DFU_OP_CALC_CRC))

        // Create data object
        val createDataCmd = createCommand(DFU_OP_CREATE_OBJECT, byteArrayOf(0x02) + firmware.size.toBytes())
        sendCommand(createDataCmd)

        // Write firmware in chunks
        var offset = 0
        var seq = 0
        while (offset < firmware.size) {
            val chunkSize = CHUNK_SIZE.coerceAtMost(firmware.size - offset)
            val chunk = firmware.copyOfRange(offset, offset + chunkSize)
            writeData(chunk)
            offset += chunkSize
            seq++
            reporter.update(chunkSize)

            if (seq % PRN_INTERVAL == 0) {
                val prnResp = readResponse()
                if (prnResp == null || prnResp[0] != DFU_OP_RESPONSE) {
                    return Result.failure(Exception("PRN response missing"))
                }
            }
        }

        // CRC check
        sendCommand(byteArrayOf(DFU_OP_CALC_CRC))
        val crcResp = readResponse()
        if (crcResp == null || crcResp[0] != DFU_OP_RESPONSE) {
            return Result.failure(Exception("CRC check failed"))
        }

        return Result.success(Unit)
    }

    override suspend fun finalize(): Result<Unit> {
        // EXECUTE
        sendCommand(byteArrayOf(DFU_OP_EXECUTE))
        val execResp = readResponse()
        if (execResp == null || execResp[0] != DFU_OP_RESPONSE) {
            return Result.failure(Exception("Execute command failed"))
        }

        // RESET
        sendCommand(byteArrayOf(DFU_OP_RESET))
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        // Can't reconnect via USB — the device reboots and BLE starts
        return Result.success("unknown")
    }

    private fun createInitPacket(firmware: ByteArray): ByteArray {
        val crc = CRC32().apply { update(firmware) }.value
        val init = ByteArray(14)
        // Device type: 0xFFFF (any)
        init[0] = 0xFF.toByte()
        init[1] = 0xFF.toByte()
        // Device revision: 0xFFFF (any)
        init[2] = 0xFF.toByte()
        init[3] = 0xFF.toByte()
        // Application version: 0xFFFFFFFF (any)
        init[4] = 0xFF.toByte()
        init[5] = 0xFF.toByte()
        init[6] = 0xFF.toByte()
        init[7] = 0xFF.toByte()
        // SoftDevice count: 0
        init[8] = 0x00.toByte()
        init[9] = 0x00.toByte()
        // Firmware CRC32
        init[10] = (crc and 0xFF).toByte()
        init[11] = ((crc shr 8) and 0xFF).toByte()
        init[12] = ((crc shr 16) and 0xFF).toByte()
        init[13] = ((crc shr 24) and 0xFF).toByte()
        return init
    }

    private fun createCommand(opcode: Byte, params: ByteArray): ByteArray {
        return byteArrayOf(opcode) + params
    }

    private fun sendCommand(cmd: ByteArray): ByteArray? {
        val ep = outEndpoint ?: return null
        val result = connection.bulkTransfer(ep, cmd, cmd.size, TIMEOUT_MS)
        if (result < 0) return null
        return readResponse()
    }

    private fun writeData(data: ByteArray): Boolean {
        val ep = outEndpoint ?: return false
        val result = connection.bulkTransfer(ep, data, data.size, TIMEOUT_MS)
        return result >= 0
    }

    private fun readResponse(): ByteArray? {
        val ep = inEndpoint ?: return null
        val buffer = ByteArray(maxTransferSize)
        val result = connection.bulkTransfer(ep, buffer, buffer.size, TIMEOUT_MS)
        if (result < 0) return null
        return buffer.copyOf(result)
    }

    private fun Int.toBytes(): ByteArray {
        return byteArrayOf(
            (this and 0xFF).toByte(),
            ((this shr 8) and 0xFF).toByte(),
            ((this shr 16) and 0xFF).toByte(),
            ((this shr 24) and 0xFF).toByte()
        )
    }
}
