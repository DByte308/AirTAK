// AirTAK Android — data/ota/EspSerialFlasher.kt
// Planning: §3.3 / §6.1 | Roadmap: 6.1 (revised)
//
// ESP32 firmware flashing over USB CDC using the ROM bootloader's SLIP
// protocol (the same wire format esptool.py speaks). We target the ESP32-S3
// chips used in AirTAK builds (T-Deck, T3-S3) but the code keeps the protocol
// generic enough to work against any ESP32 family ROM loader.
//
// Wire flow:
//
//   1. Configure the USB-CDC bridge for 115200 8N1 with no flow control.
//      For ESP32-S3 native USB (VID 0x303A) the device exposes a standard
//      CDC-ACM interface where SET_LINE_CODING is enough. For CP210x
//      (0x10C4) and CH340 (0x1A86) we use vendor-specific control transfers
//      to set baud rate and assert MCR lines.
//
//   2. Pulse DTR/RTS to reset the chip and latch GPIO0 low at the right
//      moment so the ROM boots into "download mode". The classic
//      esptool sequence is:
//        DTR=0, RTS=1   (assert reset, GPIO0 floating)
//        sleep 100 ms
//        DTR=1, RTS=0   (release reset, hold GPIO0 low)
//        sleep 50 ms
//        DTR=0, RTS=0   (release GPIO0)
//      For native S3 USB the same sequence is interpreted by the on-chip
//      USB Serial/JTAG controller and works identically.
//
//   3. Sync handshake — up to 7 attempts of 36 × 0x55 framed as a SYNC
//      command. The ROM is in an unknown state after reset and discards
//      the first few frames; we retry until it ACKs.
//
//   4. SPI_ATTACH — required on S3 to enable flash access before any
//      FLASH_BEGIN.
//
//   5. FLASH_BEGIN — declare erase size, block size, offset; ROM erases
//      the requested region in the background.
//
//   6. FLASH_DATA — stream BLOCK_SIZE-byte chunks (last chunk 0xFF-padded)
//      with monotonic sequence numbers and per-block XOR checksum.
//
//   7. FLASH_END — request reboot into the freshly flashed app.
//
// Notes / limitations:
//   * We use the ROM loader, not the RAM stub. ROM is slower (cannot raise
//     baud) but reliable across all chip revisions, and avoids the
//     ~400-line stub-loader blob.
//   * No MD5 verification yet — esptool issues SPI_FLASH_MD5 (op 0x13);
//     we rely on the ROM's per-block checksum for now.

package com.airtak.data.ota

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import kotlinx.coroutines.delay

class EspSerialFlasher(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val vendorId: Int,
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    companion object {
        // SLIP command opcodes — esptool `command.py`.
        private const val ESP_SYNC          = 0x08
        private const val ESP_FLASH_BEGIN   = 0x02
        private const val ESP_FLASH_DATA    = 0x03
        private const val ESP_FLASH_END     = 0x04
        private const val ESP_SPI_ATTACH    = 0x0D
        private const val ESP_READ_REG      = 0x0A

        // Block size that matches the ROM loader's internal SPI write buffer.
        private const val BLOCK_SIZE        = 0x400  // 1 KiB — safe across all chips
        private const val FLASH_OFFSET      = 0x10000

        // Transfer parameters.
        private const val DEFAULT_BAUD      = 115200
        private const val BULK_TIMEOUT_MS   = 3_000
        private const val SYNC_TIMEOUT_MS   = 100
        private const val SYNC_RETRIES      = 7

        // USB CDC class request codes.
        private const val CDC_SET_LINE_CODING        = 0x20
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22

        // USB control transfer types.
        private const val REQ_TYPE_CLASS_OUT  = 0x21  // host→device, class, interface
        private const val REQ_TYPE_VENDOR_OUT = 0x40  // host→device, vendor, device
        private const val REQ_TYPE_VENDOR_IN  = 0xC0  // device→host, vendor, device

        // CP210x vendor-specific requests (Silicon Labs AN571).
        private const val CP210X_IFC_ENABLE  = 0x00
        private const val CP210X_SET_BAUDRATE = 0x1E
        private const val CP210X_SET_MHS     = 0x07  // modem handshaking

        // CH340 vendor-specific requests.
        private const val CH340_REQ_WRITE_REG = 0x9A
        private const val CH340_REQ_SERIAL_INIT = 0xA1
        private const val CH340_REQ_MODEM_CTRL  = 0xA4

        // VIDs we explicitly handle.
        private const val VID_ESP32_NATIVE   = 0x303A
        private const val VID_SILABS_CP210X  = 0x10C4
        private const val VID_QINHENG_CH340  = 0x1A86
    }

    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null
    private var sequence = 0

    override suspend fun initialize(): Result<DeviceInfo> {
        // ---- Locate the bulk IN/OUT endpoints on the CDC data interface --
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) outEndpoint = ep
            else inEndpoint = ep
        }
        if (outEndpoint == null || inEndpoint == null) {
            return Result.failure(Exception("CDC bulk endpoints not found on interface"))
        }
        if (!connection.claimInterface(intf, true)) {
            return Result.failure(Exception("Could not claim USB interface (force=true)"))
        }

        // ---- Configure baud/line + assert handshaking lines --------------
        val cfgOk = configureSerial(DEFAULT_BAUD)
        if (!cfgOk) {
            return Result.failure(Exception("Failed to configure USB-serial bridge for vendor 0x${vendorId.toString(16)}"))
        }

        // ---- Reset chip into ROM download mode --------------------------
        // On ESP32-S3 native USB-Serial/JTAG (VID 0x303A) the DTR/RTS lines
        // are NOT wired to EN/IO0 on most modules (T-Deck included) — they
        // map to the USB-J-S controller, which can soft-reset the chip but
        // will land it in the *application* unless the GPIO0 strap is
        // already held low. The user must hold BOOT and tap RESET. For
        // vendor UART bridges (CP210x, CH340) the classic DTR/RTS pulse
        // does work and we drive it.
        if (vendorId != VID_ESP32_NATIVE) {
            resetIntoBootloader()
        }

        // ---- SYNC handshake ---------------------------------------------
        val syncedAttempt = (0 until SYNC_RETRIES).firstOrNull { trySync() }
        if (syncedAttempt == null) {
            return Result.failure(Exception(
                if (vendorId == VID_ESP32_NATIVE)
                    "ESP32 did not respond to SYNC. Hold the BOOT button and tap RESET on the T-Deck, " +
                        "then try again — the chip must already be in the download-mode bootloader for " +
                        "USB-Serial/JTAG flashing."
                else
                    "ESP32 did not respond to SYNC. Check cable, hold BOOT and tap RESET, then retry."
            ))
        }

        // The ROM emits one reply per SYNC frame (8 echoes total). Drain
        // any extras so the next sendCommand() doesn't grab a stale SYNC
        // reply and mis-identify it as the SPI_ATTACH response.
        drainPendingReplies()

        // ---- SPI_ATTACH (required on S3 before FLASH_BEGIN) -------------
        // payload: HSPI_ARG(4)=0 + ZERO(4)=0
        val attachOk = sendCommand(ESP_SPI_ATTACH, ByteArray(8))
        if (!attachOk) {
            return Result.failure(Exception("SPI_ATTACH rejected by ROM bootloader"))
        }

        return Result.success(DeviceInfo(
            boardId = if (vendorId == VID_ESP32_NATIVE) "t_deck" else "t3s3",
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
            onProgress = onProgress,
        )
        reporter.start()

        // FLASH_BEGIN — erase enough flash for the image.
        val numBlocks = (firmware.size + BLOCK_SIZE - 1) / BLOCK_SIZE
        val beginPayload = leInt(firmware.size) + leInt(numBlocks) + leInt(BLOCK_SIZE) + leInt(FLASH_OFFSET)
        if (!sendCommand(ESP_FLASH_BEGIN, beginPayload, timeoutMs = 30_000)) {
            return Result.failure(Exception("FLASH_BEGIN rejected (image size=${firmware.size})"))
        }

        // FLASH_DATA — stream blocks.
        var offset = 0
        sequence = 0
        while (offset < firmware.size) {
            val remaining = firmware.size - offset
            val take = BLOCK_SIZE.coerceAtMost(remaining)
            val chunk = firmware.copyOfRange(offset, offset + take)
            val padded = if (take < BLOCK_SIZE) chunk + ByteArray(BLOCK_SIZE - take) { 0xFF.toByte() } else chunk

            // FLASH_DATA payload: <data_len:4><seq:4><0:4><0:4><data>
            val payload = leInt(padded.size) + leInt(sequence) + leInt(0) + leInt(0) + padded
            // FLASH_DATA's checksum field (in the SLIP header) is computed
            // over the *data only*, not the leading 16-byte header.
            val ok = sendCommand(ESP_FLASH_DATA, payload, checksumOver = padded)
            if (!ok) {
                return Result.failure(Exception("FLASH_DATA failed at block $sequence (offset=$offset)"))
            }
            reporter.update(take)
            offset += take
            sequence++
        }

        // ---- Invalidate otadata so the bootloader picks our new app ------
        // The T-Deck / T3-S3 partition tables (default_16MB.csv) are
        // dual-OTA: two app slots (app0 at 0x10000, app1 at 0x650000) plus
        // an otadata sector at 0xE000 that records which slot is active.
        // If the device was previously updated over BLE OTA, otadata may
        // point at app1 — in which case writing app0 alone leaves the old
        // app1 firmware running. Erasing otadata to all-0xFF causes the
        // ROM bootloader to fall back to the *first* valid app slot, which
        // is the one we just wrote to.
        if (FLASH_OFFSET == 0x10000) {  // only meaningful for app-slot writes
            val eraseOk = eraseOtaDataSector()
            if (!eraseOk) {
                // Non-fatal: log via exception text but continue. Worst case
                // the user's device still boots the previous slot and they
                // can retry; we don't want to lose a successful app write.
                android.util.Log.w("EspSerialFlasher",
                    "Could not invalidate otadata; device may still boot the previous OTA slot.")
            }
        }

        return Result.success(Unit)
    }

    /** Write 0xFF across the otadata sector (offset 0xE000, size 0x2000) so
     *  the bootloader falls back to the first OTA app slot. */
    private suspend fun eraseOtaDataSector(): Boolean {
        val otaDataOffset = 0xE000
        val otaDataSize = 0x2000
        val blockCount = otaDataSize / BLOCK_SIZE
        val beginPayload = leInt(otaDataSize) + leInt(blockCount) + leInt(BLOCK_SIZE) + leInt(otaDataOffset)
        if (!sendCommand(ESP_FLASH_BEGIN, beginPayload, timeoutMs = 10_000)) return false
        val blank = ByteArray(BLOCK_SIZE) { 0xFF.toByte() }
        sequence = 0
        repeat(blockCount) { seq ->
            val payload = leInt(blank.size) + leInt(seq) + leInt(0) + leInt(0) + blank
            if (!sendCommand(ESP_FLASH_DATA, payload, checksumOver = blank)) return false
        }
        return true
    }

    override suspend fun finalize(): Result<Unit> {
        // FLASH_END payload: 0 = reboot into app, 1 = stay in bootloader.
        sendCommand(ESP_FLASH_END, leInt(0), expectReply = false)
        runCatching { connection.releaseInterface(intf) }
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        // Over USB CDC we have no path to read the running app's version —
        // the user can verify via the BLE link once it comes back.
        return Result.success("unknown")
    }

    // ────────────────────────────────────────────────────────────────────
    //  Bridge configuration
    // ────────────────────────────────────────────────────────────────────

    /** Configure the USB-serial bridge for [baud] 8N1, flow control off. */
    private fun configureSerial(baud: Int): Boolean = when (vendorId) {
        VID_ESP32_NATIVE -> configureCdcAcm(baud)
        VID_SILABS_CP210X -> configureCp210x(baud)
        VID_QINHENG_CH340 -> configureCh340(baud)
        else -> configureCdcAcm(baud) // best-effort fallback
    }

    /** Standard USB CDC-ACM SET_LINE_CODING (7-byte descriptor):
     *  baud (u32 LE), stop bits, parity, data bits. */
    private fun configureCdcAcm(baud: Int): Boolean {
        val data = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
            0x00, // 1 stop bit
            0x00, // no parity
            0x08, // 8 data bits
        )
        val r1 = connection.controlTransfer(
            REQ_TYPE_CLASS_OUT, CDC_SET_LINE_CODING, 0, intf.id, data, data.size, BULK_TIMEOUT_MS
        )
        // Assert DTR+RTS so the bridge starts clocking data through. The
        // bootloader reset pulse below will re-drive these lines.
        val r2 = connection.controlTransfer(
            REQ_TYPE_CLASS_OUT, CDC_SET_CONTROL_LINE_STATE, 0x03, intf.id, null, 0, BULK_TIMEOUT_MS
        )
        return r1 >= 0 && r2 >= 0
    }

    /** Silicon Labs CP210x: enable interface, set baud, leave modem lines
     *  for [resetIntoBootloader] to drive. */
    private fun configureCp210x(baud: Int): Boolean {
        val enable = connection.controlTransfer(
            REQ_TYPE_VENDOR_OUT, CP210X_IFC_ENABLE, 0x0001, 0, null, 0, BULK_TIMEOUT_MS
        )
        val baudBytes = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
        )
        val setBaud = connection.controlTransfer(
            REQ_TYPE_VENDOR_OUT, CP210X_SET_BAUDRATE, 0, 0, baudBytes, baudBytes.size, BULK_TIMEOUT_MS
        )
        return enable >= 0 && setBaud >= 0
    }

    /** WCH CH340: init sequence is poorly documented — these values are
     *  cargo-culted from the Linux ch341 driver and known-good for 115200. */
    private fun configureCh340(@Suppress("UNUSED_PARAMETER") baud: Int): Boolean {
        // Magic init reads (return 0x27/0x00); ignored, just kick the chip.
        connection.controlTransfer(REQ_TYPE_VENDOR_IN, CH340_REQ_SERIAL_INIT, 0, 0, ByteArray(8), 8, BULK_TIMEOUT_MS)
        // Set baud-rate divisors for 115200 from ch341 driver tables.
        connection.controlTransfer(REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, 0x1312, 0xCC83, null, 0, BULK_TIMEOUT_MS)
        connection.controlTransfer(REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, 0x0F2C, 0x0007, null, 0, BULK_TIMEOUT_MS)
        // 8N1, no parity, no flow control.
        connection.controlTransfer(REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, 0x2518, 0x00C3, null, 0, BULK_TIMEOUT_MS)
        return true
    }

    /** Drive DTR/RTS in the classic esptool sequence to land the chip in
     *  ROM download mode. Lines map to chip pins as:
     *    DTR  →  EN  (reset, active low)
     *    RTS  →  IO0 (strap, low = bootloader)
     */
    private suspend fun resetIntoBootloader() {
        setDtrRts(dtr = false, rts = true);  delay(100)
        setDtrRts(dtr = true,  rts = false); delay(50)
        setDtrRts(dtr = false, rts = false); delay(50)
    }

    private fun setDtrRts(dtr: Boolean, rts: Boolean) {
        when (vendorId) {
            VID_SILABS_CP210X -> {
                // Bit 0=DTR, bit 1=RTS in low byte; mask in high byte.
                val state = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
                val mask = 0x03 shl 8
                connection.controlTransfer(
                    REQ_TYPE_VENDOR_OUT, CP210X_SET_MHS, state or mask, 0, null, 0, BULK_TIMEOUT_MS
                )
            }
            VID_QINHENG_CH340 -> {
                // CH340 MODEM_CTRL: bit 5=~DTR, bit 6=~RTS (active-low).
                var v = 0xFF
                if (dtr) v = v and 0x20.inv()
                if (rts) v = v and 0x40.inv()
                connection.controlTransfer(
                    REQ_TYPE_VENDOR_OUT, CH340_REQ_MODEM_CTRL, v, 0, null, 0, BULK_TIMEOUT_MS
                )
            }
            else -> {
                // Standard CDC-ACM: bit 0=DTR, bit 1=RTS.
                val value = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
                connection.controlTransfer(
                    REQ_TYPE_CLASS_OUT, CDC_SET_CONTROL_LINE_STATE, value, intf.id, null, 0, BULK_TIMEOUT_MS
                )
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  SLIP transport
    // ────────────────────────────────────────────────────────────────────

    /** Send a single command and wait for the ROM's reply.
     *  @param checksumOver bytes to include in the SLIP header checksum
     *         (only [ESP_FLASH_DATA] uses a non-empty checksum).
     *  @return true if the ROM ACKed with status=0. */
    private fun sendCommand(
        op: Int,
        payload: ByteArray,
        checksumOver: ByteArray = ByteArray(0),
        expectReply: Boolean = true,
        timeoutMs: Int = BULK_TIMEOUT_MS,
    ): Boolean {
        val header = byteArrayOf(0x00, op.toByte()) +
            leShort(payload.size) +
            leInt(checksum(checksumOver))
        val frame = SlipFramer.encode(header + payload)
        val out = outEndpoint ?: return false
        if (connection.bulkTransfer(out, frame, frame.size, timeoutMs) < 0) return false
        if (!expectReply) return true
        // The ROM may have buffered SYNC echoes or stale replies in the
        // bulk-in pipe. Read until we either match our opcode or the
        // timeout elapses — drop any other frames silently.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val reply = readSlipPacket(
                (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50)
            ) ?: return false
            if (reply.size >= 2 && reply[0] == 0x01.toByte() && reply[1] == op.toByte()) {
                return parseReplyOk(op, reply)
            }
            // Mismatched frame (stale SYNC echo etc.) — discard and keep reading.
        }
        return false
    }

    /** Issue one 36×0x55 SYNC frame and return true if the ROM replies OK. */
    private fun trySync(): Boolean {
        val syncData = ByteArray(36) { 0x55.toByte() }
        val payload = byteArrayOf(0x07.toByte(), 0x07.toByte(), 0x12.toByte(), 0x20.toByte()) + syncData
        val header = byteArrayOf(0x00, ESP_SYNC.toByte()) + leShort(payload.size) + leInt(0)
        val frame = SlipFramer.encode(header + payload)
        val out = outEndpoint ?: return false
        if (connection.bulkTransfer(out, frame, frame.size, SYNC_TIMEOUT_MS) < 0) return false
        // The ROM emits multiple frames in response (one per SYNC echo); the
        // first valid OK reply is enough for our purposes.
        val reply = readSlipPacket(SYNC_TIMEOUT_MS) ?: return false
        return parseReplyOk(ESP_SYNC, reply)
    }

    /** Drain any pending frames from the bulk-in endpoint. Called after
     *  SYNC succeeds because the ROM emits 7 additional echo frames that
     *  would otherwise be consumed as replies to the next real command. */
    private fun drainPendingReplies() {
        val ep = inEndpoint ?: return
        val buf = ByteArray(256)
        // Brief read loop with short timeout — once the pipe is idle for
        // one tick we know all queued echoes have been consumed.
        val deadline = System.currentTimeMillis() + 200
        while (System.currentTimeMillis() < deadline) {
            val n = connection.bulkTransfer(ep, buf, buf.size, 30)
            if (n <= 0) break
        }
    }

    /** Pull bytes from the bulk-in endpoint until a complete SLIP frame
     *  is decoded, or [timeoutMs] elapses. */
    private fun readSlipPacket(timeoutMs: Int): ByteArray? {
        val ep = inEndpoint ?: return null
        val accum = ArrayList<Byte>()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = connection.bulkTransfer(ep, buf, buf.size, 50)
            if (n <= 0) continue
            for (i in 0 until n) accum.add(buf[i])
            val packets = SlipFramer.decode(accum.toByteArray())
            if (packets.isNotEmpty()) return packets[0]
        }
        return null
    }

    /** ROM reply format: `<0x01><opcode><size:2><value:4><data...>`.
     *  For ESP32-family ROM the trailing `data` ends with a 4-byte status
     *  block: `<status:1><error:1><reserved:2>` (esptool's
     *  `STATUS_BYTES_LENGTH = 4`). status == 0 means success. */
    private fun parseReplyOk(expectedOp: Int, reply: ByteArray): Boolean {
        if (reply.size < 10) return false  // 8-byte header + at least 2 status bytes
        if (reply[0] != 0x01.toByte()) return false
        if (reply[1] != expectedOp.toByte()) return false
        // Try 4-byte status trailer first (ROM ESP32-family), fall back to
        // 2-byte trailer (stub or ESP8266 ROM).
        val statusByte4 = reply.getOrNull(reply.size - 4)
        if (statusByte4 != null && statusByte4 == 0x00.toByte()) return true
        val statusByte2 = reply.getOrNull(reply.size - 2)
        return statusByte2 != null && statusByte2 == 0x00.toByte()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun leShort(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    /** esptool XOR checksum (seed 0xEF). */
    private fun checksum(data: ByteArray): Int {
        var c = 0xEF
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }
}
