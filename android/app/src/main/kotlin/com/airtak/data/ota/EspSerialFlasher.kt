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
//   6. FLASH_DATA — stream ESP_BLOCK_SIZE-byte chunks (last chunk 0xFF-padded)
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
import android.content.Context
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.airtak.config.constants.CDC_SET_CONTROL_LINE_STATE
import com.airtak.config.constants.CDC_SET_LINE_CODING
import com.airtak.config.constants.CH340_BAUD_DIV_READ_HIGH
import com.airtak.config.constants.CH340_BAUD_DIV_READ_LOW
import com.airtak.config.constants.CH340_BAUD_DIV_WRITE_HIGH
import com.airtak.config.constants.CH340_BAUD_DIV_WRITE_LOW
import com.airtak.config.constants.CH340_LINE_CTRL_HIGH
import com.airtak.config.constants.CH340_LINE_CTRL_LOW
import com.airtak.config.constants.CH340_MODEM_DEFAULT
import com.airtak.config.constants.CH340_MODEM_DTR_NEG
import com.airtak.config.constants.CH340_MODEM_RTS_NEG
import com.airtak.config.constants.CH340_REQ_MODEM_CTRL
import com.airtak.config.constants.CH340_REQ_SERIAL_INIT
import com.airtak.config.constants.CH340_REQ_WRITE_REG
import com.airtak.config.constants.CP210X_ENABLE_VALUE
import com.airtak.config.constants.CP210X_IFC_ENABLE
import com.airtak.config.constants.CP210X_MHS_DTR
import com.airtak.config.constants.CP210X_MHS_MASK
import com.airtak.config.constants.CP210X_SET_BAUDRATE
import com.airtak.config.constants.CP210X_SET_MHS
import com.airtak.config.constants.ESP_APP_MAGIC
import com.airtak.config.constants.ESP_APP1_OFFSET
import com.airtak.config.constants.ESP_BLOCK_PACING_MS
import com.airtak.config.constants.ESP_BLOCK_SIZE
import com.airtak.config.constants.ESP_BULK_TIMEOUT_MS
import com.airtak.config.constants.ESP_DEFAULT_BAUD
import com.airtak.config.constants.ESP_DRAIN_TIMEOUT_MS
import com.airtak.config.constants.ESP_FLASH_BEGIN
import com.airtak.config.constants.ESP_FLASH_BEGIN_LARGE_TIMEOUT_MS
import com.airtak.config.constants.ESP_FLASH_BEGIN_TIMEOUT_MS
import com.airtak.config.constants.ESP_FLASH_DATA
import com.airtak.config.constants.ESP_FLASH_DATA_TIMEOUT_MS
import com.airtak.config.constants.ESP_FLASH_END
import com.airtak.config.constants.ESP_FLASH_END_DELAY_MS
import com.airtak.config.constants.ESP_FLASH_OFFSET
import com.airtak.config.constants.ESP_FLASH_PADDING_BYTE
import com.airtak.config.constants.ESP_FLASH_SECTOR_SIZE
import com.airtak.config.constants.ESP_GPIO0_RELEASE_MS
import com.airtak.config.constants.ESP_IFCLAIM_DRAIN_MS
import com.airtak.config.constants.ESP_IN_EP_HALT_THRESHOLD_MS
import com.airtak.config.constants.ESP_MEM_BEGIN
import com.airtak.config.constants.ESP_MEM_BEGIN_TIMEOUT_MS
import com.airtak.config.constants.ESP_MEM_DATA
import com.airtak.config.constants.ESP_MEM_END
import com.airtak.config.constants.ESP_OTA_DATA_OFFSET
import com.airtak.config.constants.ESP_OTA_DATA_SIZE
import com.airtak.config.constants.ESP_OTA_MAX_SIZE
import com.airtak.config.constants.ESP_OHAI_WAIT_MS
import com.airtak.config.constants.ESP_PROGRESS_LOG_EVERY_N
import com.airtak.config.constants.ESP_RAM_BLOCK_SIZE
import com.airtak.config.constants.ESP_REBOOT_APP
import com.airtak.config.constants.ESP_RESET_PULSE_MS
import com.airtak.config.constants.ESP_RESET_RELEASE_MS
import com.airtak.config.constants.ESP_ROM_REPLY_OK
import com.airtak.config.constants.ESP_ROM_REPLY_STATUS_OK_BYTE2
import com.airtak.config.constants.ESP_ROM_REPLY_STATUS_OK_BYTE4
import com.airtak.config.constants.ESP_ROM_RETRY_REPLY_MAGIC
import com.airtak.config.constants.ESP_SHORT_READ_TIMEOUT_MS
import com.airtak.config.constants.ESP_SPI_ATTACH
import com.airtak.config.constants.ESP_SPI_ATTACH_PAYLOAD_SIZE
import com.airtak.config.constants.ESP_SYNC
import com.airtak.config.constants.ESP_SYNC_FRAME_BYTE
import com.airtak.config.constants.ESP_SYNC_FRAME_DATA_LEN
import com.airtak.config.constants.ESP_SYNC_FRAME_PREFIX
import com.airtak.config.constants.ESP_SYNC_RETRIES
import com.airtak.config.constants.ESP_SYNC_TIMEOUT_MS
import com.airtak.config.constants.ESP_XOR_CHECKSUM_SEED
import com.airtak.config.constants.USB_BMREQ_SET_FEATURE
import com.airtak.config.constants.SLIP_END
import com.airtak.config.constants.USB_CDC_DATA_BITS_8
import com.airtak.config.constants.USB_CDC_DTR_RTS_ASSERTED
import com.airtak.config.constants.USB_CDC_PARITY_NONE
import com.airtak.config.constants.USB_CDC_STOP_BITS_1
import com.airtak.config.constants.USB_ENDPOINT_HALT
import com.airtak.config.constants.USB_REQ_CLEAR_FEATURE
import com.airtak.config.constants.USB_REQ_TYPE_CLASS_OUT
import com.airtak.config.constants.USB_REQ_TYPE_VENDOR_IN
import com.airtak.config.constants.USB_REQ_TYPE_VENDOR_OUT
import com.airtak.config.constants.VID_ESP32_NATIVE
import com.airtak.config.constants.VID_QINHENG_CH340
import com.airtak.config.constants.VID_SILABS_CP210X

class EspSerialFlasher(
    private val context: Context,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val vendorId: Int,
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    override var onDebug: ((String) -> Unit)? = null

    private fun dbg(msg: String) { android.util.Log.d("EspFlash", msg); onDebug?.invoke(msg) }
    private fun dbgErr(msg: String) { android.util.Log.e("EspFlash", msg); onDebug?.invoke("ERROR: $msg") }

    companion object {
        // All constants are canonical in config/constants/OtaConstants.kt
    }

    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null
    private var sequence = 0

    override suspend fun initialize(): Result<DeviceInfo> {
        dbg("initialize: locating endpoints...")
        // ---- Locate the bulk IN/OUT endpoints on the CDC data interface --
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) outEndpoint = ep
            else inEndpoint = ep
        }
        if (outEndpoint == null || inEndpoint == null) {
            dbgErr("CDC bulk endpoints not found")
            return Result.failure(Exception("CDC bulk endpoints not found on interface"))
        }
        dbg("endpoints OK: out=${outEndpoint?.address?.toString(16)} in=${inEndpoint?.address?.toString(16)}")
        if (!connection.claimInterface(intf, true)) {
            dbgErr("claimInterface failed")
            return Result.failure(Exception("Could not claim USB interface (force=true)"))
        }
        dbg("interface claimed")

        // ---- Configure baud/line + assert handshaking lines --------------
        dbg("configureSerial(baud=${ESP_DEFAULT_BAUD})...")
        val cfgOk = configureSerial(ESP_DEFAULT_BAUD)
        if (!cfgOk) {
            dbgErr("configureSerial failed")
            return Result.failure(Exception("Failed to configure USB-serial bridge for vendor 0x${vendorId.toString(16)}"))
        }
        dbg("serial configured OK")

        // ---- Reset chip into ROM download mode --------------------------
        if (vendorId != VID_ESP32_NATIVE) {
            dbg("resetIntoBootloader()...")
            resetIntoBootloader()
            dbg("reset done")
            // After a hardware reset the bridge may have stale bytes from
            // the previously-running app. Drain them so they don't pollute
            // the first SYNC read.
            drainPendingReplies()
            ringBuf.clear()
        } else {
            dbg("ESP32 native USB — skipping DTR/RTS reset (user must hold BOOT+RESET)")
        }

        // ---- SYNC handshake ---------------------------------------------
        dbg("SYNC handshake (retries=$ESP_SYNC_RETRIES)...")
        val syncedAttempt = (0 until ESP_SYNC_RETRIES).firstOrNull { trySync() }
        if (syncedAttempt == null) {
            dbgErr("SYNC failed after $ESP_SYNC_RETRIES attempts")
            return Result.failure(Exception(
                if (vendorId == VID_ESP32_NATIVE)
                    "ESP32 did not respond to SYNC. Hold the BOOT button and tap RESET on the T-Deck, " +
                        "then try again — the chip must already be in the download-mode bootloader for " +
                        "USB-Serial/JTAG flashing."
                else
                    "ESP32 did not respond to SYNC. Check cable, hold BOOT and tap RESET, then retry."
            ))
        }
        dbg("SYNC OK on attempt ${syncedAttempt}")

        drainPendingReplies()
        dbg("pending replies drained")

        // ---- SPI_ATTACH --------
        dbg("SPI_ATTACH...")
        if (!sendCommand(ESP_SPI_ATTACH, ByteArray(ESP_SPI_ATTACH_PAYLOAD_SIZE))) {
            dbgErr("SPI_ATTACH rejected")
            return Result.failure(Exception("SPI_ATTACH rejected by ROM bootloader"))
        }
        dbg("SPI_ATTACH OK")

        // ---- Upload stub loader --------
        val stubResult = uploadStubLoader()
        if (stubResult.isFailure) {
            return Result.failure(stubResult.exceptionOrNull() ?: Exception("stub upload failed"))
        }
        dbg("initialize: DONE")
        return Result.success(DeviceInfo(
            boardId = if (vendorId == VID_ESP32_NATIVE) "t_deck" else "t3s3",
            platform = "ESP32-S3",
            firmwareVersion = "stub"
        ))
    }

    /** Upload esptool's RAM stub loader so flash writes use the correct IDF
     *  driver instead of the buggy ROM (ESP32-S3 rev 0.2 ACKs writes but
     *  does not execute them).  The stub binary is stored in the app assets. */
    private suspend fun uploadStubLoader(): Result<Unit> {
        val ramBlockSize = ESP_RAM_BLOCK_SIZE
        dbg("stub: loading assets...")

        // Load stub
        val textBytes: ByteArray; val dataBytes: ByteArray
        val textStart: Int; val dataStart: Int; val entry: Int
        try {
            val meta = JSONObject(context.assets.open("esp32s3_stub_meta.json").bufferedReader().readText())
            textStart = meta.getInt("text_start"); dataStart = meta.getInt("data_start"); entry = meta.getInt("entry")
            textBytes = context.assets.open("esp32s3_stub_text.bin").readBytes()
            dataBytes = context.assets.open("esp32s3_stub_data.bin").readBytes()
            dbg("stub: text=${textBytes.size}@0x${textStart.toString(16)} data=${dataBytes.size}@0x${dataStart.toString(16)} entry=0x${entry.toString(16)}")
        } catch (e: Exception) {
            dbgErr("stub: asset load failed: ${e.message}")
            return Result.failure(Exception("stub_asset: ${e.message}"))
        }

        // MEM_BEGIN text
        val textBlocks = (textBytes.size + ramBlockSize - 1) / ramBlockSize
        val textBegin = leInt(textBytes.size) + leInt(textBlocks) + leInt(ramBlockSize) + leInt(textStart)
        dbg("stub: MEM_BEGIN text size=${textBytes.size} blocks=$textBlocks off=0x${textStart.toString(16)}")
        if (!sendCommand(ESP_MEM_BEGIN, textBegin, timeoutMs = ESP_MEM_BEGIN_TIMEOUT_MS))
            return Result.failure(Exception("MEM_BEGIN_text rejected"))
        dbg("stub: MEM_BEGIN text OK")

        // MEM_DATA text
        for (seq in 0 until textBlocks) {
            val from = seq * ramBlockSize
            val to = (from + ramBlockSize).coerceAtMost(textBytes.size)
            val chunk = textBytes.copyOfRange(from, to)
            val p = leInt(chunk.size) + leInt(seq) + leInt(0) + leInt(0) + chunk
            if (!sendCommand(ESP_MEM_DATA, p, checksumOver = chunk))
                return Result.failure(Exception("MEM_DATA_text seq=$seq failed"))
        }
        dbg("stub: MEM_DATA text OK (${textBlocks} blocks)")

        // MEM_BEGIN data
        val dataBlocks = (dataBytes.size + ramBlockSize - 1) / ramBlockSize
        val dataBegin = leInt(dataBytes.size) + leInt(dataBlocks) + leInt(ramBlockSize) + leInt(dataStart)
        dbg("stub: MEM_BEGIN data size=${dataBytes.size} blocks=$dataBlocks off=0x${dataStart.toString(16)}")
        if (!sendCommand(ESP_MEM_BEGIN, dataBegin, timeoutMs = ESP_MEM_BEGIN_TIMEOUT_MS))
            return Result.failure(Exception("MEM_BEGIN_data rejected"))
        dbg("stub: MEM_BEGIN data OK")

        // MEM_DATA data
        for (seq in 0 until dataBlocks) {
            val from = seq * ramBlockSize
            val to = (from + ramBlockSize).coerceAtMost(dataBytes.size)
            val chunk = dataBytes.copyOfRange(from, to)
            val p = leInt(chunk.size) + leInt(seq) + leInt(0) + leInt(0) + chunk
            if (!sendCommand(ESP_MEM_DATA, p, checksumOver = chunk))
                return Result.failure(Exception("MEM_DATA_data seq=$seq failed"))
        }
        dbg("stub: MEM_DATA data OK (${dataBlocks} blocks)")

        // MEM_END
        val memEndPayload = leInt(0) + leInt(entry)
        val memEndFrame = SlipFramer.encode(
            byteArrayOf(0x00, ESP_MEM_END.toByte()) + leShort(memEndPayload.size) + leInt(0) + memEndPayload
        )
        val out = outEndpoint ?: return Result.failure(Exception("MEM_END: no endpoint"))
        if (connection.bulkTransfer(out, memEndFrame, memEndFrame.size, ESP_DRAIN_TIMEOUT_MS.toInt()) < 0) {
            dbgErr("MEM_END: bulkTransfer failed")
            return Result.failure(Exception("MEM_END: send failed"))
        }
        dbg("stub: MEM_END sent (${memEndFrame.size}B), draining ROM reply...")

        // Drain the ROM's MEM_END reply (op=0x06).  Must use SLIP decode here.
        val romReply = readSlipPacket(ESP_BULK_TIMEOUT_MS)
        dbg("stub: ROM reply: ${romReply?.size ?: 0}B ${if (romReply != null && romReply.size >= 2) "op=0x%02x".format(romReply[1].toInt() and 0xFF) else "none"}")

        // Read OHAI — no artificial delay; the stub starts while we read the ROM reply.
        // Use a long timeout since the stub must initialise SPI flash before sending OHAI.
        dbg("stub: waiting for OHAI (10s timeout)...")
        val hello = readSlipPacket(ESP_OHAI_WAIT_MS)
        val helloStr = hello?.let { String(it) }
        val rawHex = hello?.joinToString("") { "%02x".format(it) } ?: "null"
        dbg("stub: hello response: $helloStr (raw=$rawHex)")
        if (helloStr != "OHAI") 
            return Result.failure(Exception("stub_hello: ${helloStr ?: "timeout"}"))
        dbg("stub: OHAI received — stub running!")
        return Result.success(Unit)
    }

    override suspend fun transfer(
        firmware: ByteArray,
        onProgress: (UpdateProgress) -> Unit
    ): Result<Unit> {
        if (firmware.isEmpty()) {
            return Result.failure(Exception("Firmware file is empty"))
        }

        // Detect image type: OTA app (starts with 0xE9, typically < 2 MB)
        // or a combined / factory image (flashed at offset 0).
        val isOta = firmware[0] == ESP_APP_MAGIC && firmware.size < ESP_OTA_MAX_SIZE
        val flashOffset = if (isOta) ESP_FLASH_OFFSET else 0
        dbg("Image type: ${if (isOta) "OTA app (0x10000)" else "factory (0x0)"} size=${firmware.size}")

        // 1. For OTA: erase otadata so the bootloader falls back to app0.
        if (isOta) {
            val otaBlockCount = ESP_OTA_DATA_SIZE / ESP_BLOCK_SIZE
            val otaBegin = leInt(ESP_OTA_DATA_SIZE) + leInt(otaBlockCount) + leInt(ESP_BLOCK_SIZE) + leInt(ESP_OTA_DATA_OFFSET)
            if (!sendCommand(ESP_FLASH_BEGIN, otaBegin, timeoutMs = ESP_FLASH_BEGIN_TIMEOUT_MS)) {
                dbg("otadata FLASH_BEGIN failed — continuing")
            } else {
                val blank = ByteArray(ESP_BLOCK_SIZE) { ESP_FLASH_PADDING_BYTE }
                for (otaSeq in 0 until otaBlockCount) {
                    val p = leInt(blank.size) + leInt(otaSeq) + leInt(0) + leInt(0) + blank
                    if (!sendCommand(ESP_FLASH_DATA, p, checksumOver = blank)) break
                }
            }
        }

        // 2. Write firmware to a single slot (app0 for OTA, 0x0 for factory).
        val numBlocks = (firmware.size + ESP_BLOCK_SIZE - 1) / ESP_BLOCK_SIZE
        val eraseSize = ((firmware.size + ESP_FLASH_SECTOR_SIZE - 1) / ESP_FLASH_SECTOR_SIZE) * ESP_FLASH_SECTOR_SIZE

        val beginPayload = leInt(eraseSize) + leInt(numBlocks) + leInt(ESP_BLOCK_SIZE) + leInt(flashOffset)
        dbg("FLASH_BEGIN off=0x${flashOffset.toString(16)} erase=0x${eraseSize.toString(16)} blocks=$numBlocks")
        if (!sendCommand(ESP_FLASH_BEGIN, beginPayload, timeoutMs = ESP_FLASH_BEGIN_LARGE_TIMEOUT_MS)) {
            return Result.failure(Exception("FLASH_BEGIN rejected at offset 0x${flashOffset.toString(16)} (image size=${firmware.size})"))
        }

        val reporter = ProgressReporter(
            totalBytes = firmware.size.toLong(),
            onProgress = onProgress,
        )
        reporter.start()

        var offset = 0
        sequence = 0
        while (offset < firmware.size) {
            val remaining = firmware.size - offset
            val take = ESP_BLOCK_SIZE.coerceAtMost(remaining)
            val chunk = firmware.copyOfRange(offset, offset + take)
            val padded = if (take < ESP_BLOCK_SIZE) chunk + ByteArray(ESP_BLOCK_SIZE - take) { ESP_FLASH_PADDING_BYTE } else chunk

            val payload = leInt(padded.size) + leInt(sequence) + leInt(0) + leInt(0) + padded
            var blockOk = false
            for (attempt in 0 until 3) {
                if (sendCommand(ESP_FLASH_DATA, payload, checksumOver = padded, timeoutMs = ESP_FLASH_DATA_TIMEOUT_MS)) {
                    blockOk = true
                    break
                }
                dbg("FLASH_DATA block $sequence attempt $attempt failed — retrying")
                drainPendingReplies()
                ringBuf.clear()
                delay(ESP_BLOCK_PACING_MS * (attempt + 1))
            }
            if (!blockOk) {
                return Result.failure(Exception("FLASH_DATA failed at block $sequence (offset=$offset) after 3 retries"))
            }
            reporter.update(take)
            offset += take
            sequence++
            delay(ESP_BLOCK_PACING_MS)
            if (sequence % ESP_PROGRESS_LOG_EVERY_N == 0) dbg("...block $sequence/${numBlocks} OK")
        }

        return Result.success(Unit)
    }

    override suspend fun finalize(): Result<Unit> {
        val committed = sendCommand(ESP_FLASH_END, leInt(ESP_REBOOT_APP),
            expectReply = true, timeoutMs = ESP_FLASH_BEGIN_TIMEOUT_MS)
        delay(ESP_FLASH_END_DELAY_MS)
        runCatching { connection.releaseInterface(intf) }
        if (!committed) {
            dbg("FLASH_END ACK not received — expected after reboot (chip resets before replying)")
        }
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
            USB_CDC_STOP_BITS_1.toByte(), // 1 stop bit
            USB_CDC_PARITY_NONE.toByte(), // no parity
            USB_CDC_DATA_BITS_8.toByte(), // 8 data bits
        )
        val r1 = connection.controlTransfer(
            USB_REQ_TYPE_CLASS_OUT, CDC_SET_LINE_CODING, 0, intf.id, data, data.size, ESP_BULK_TIMEOUT_MS
        )
        // Assert DTR+RTS so the bridge starts clocking data through. The
        // bootloader reset pulse below will re-drive these lines.
        val r2 = connection.controlTransfer(
            USB_REQ_TYPE_CLASS_OUT, CDC_SET_CONTROL_LINE_STATE, USB_CDC_DTR_RTS_ASSERTED, intf.id, null, 0, ESP_BULK_TIMEOUT_MS
        )
        return r1 >= 0 && r2 >= 0
    }

    /** Silicon Labs CP210x: enable interface, set baud, leave modem lines
     *  for [resetIntoBootloader] to drive. */
    private fun configureCp210x(baud: Int): Boolean {
        val enable = connection.controlTransfer(
            USB_REQ_TYPE_VENDOR_OUT, CP210X_IFC_ENABLE, CP210X_ENABLE_VALUE, 0, null, 0, ESP_BULK_TIMEOUT_MS
        )
        val baudBytes = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
        )
        val setBaud = connection.controlTransfer(
            USB_REQ_TYPE_VENDOR_OUT, CP210X_SET_BAUDRATE, 0, 0, baudBytes, baudBytes.size, ESP_BULK_TIMEOUT_MS
        )
        return enable >= 0 && setBaud >= 0
    }

    /** WCH CH340: init sequence is poorly documented — these values are
     *  cargo-culted from the Linux ch341 driver and known-good for 115200. */
    private fun configureCh340(@Suppress("UNUSED_PARAMETER") baud: Int): Boolean {
        // Magic init reads (return 0x27/0x00); ignored, just kick the chip.
        connection.controlTransfer(USB_REQ_TYPE_VENDOR_IN, CH340_REQ_SERIAL_INIT, 0, 0, ByteArray(8), 8, ESP_BULK_TIMEOUT_MS)
        // Set baud-rate divisors for 115200 from ch341 driver tables.
        connection.controlTransfer(USB_REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, CH340_BAUD_DIV_WRITE_LOW, CH340_BAUD_DIV_WRITE_HIGH, null, 0, ESP_BULK_TIMEOUT_MS)
        connection.controlTransfer(USB_REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, CH340_BAUD_DIV_READ_LOW, CH340_BAUD_DIV_READ_HIGH, null, 0, ESP_BULK_TIMEOUT_MS)
        // 8N1, no parity, no flow control.
        connection.controlTransfer(USB_REQ_TYPE_VENDOR_OUT, CH340_REQ_WRITE_REG, CH340_LINE_CTRL_LOW, CH340_LINE_CTRL_HIGH, null, 0, ESP_BULK_TIMEOUT_MS)
        return true
    }

    /** Drive DTR/RTS in the classic esptool sequence to land the chip in
     *  ROM download mode.  On most ESP32 dev boards (including Heltec V3)
     *  the auto-reset circuit maps DTR→IO0 and RTS→EN.  A simultaneous
     *  DTR+RTS transition can cause EN to rise before IO0 falls, so we
     *  insert an intermediate state that holds the boot pin low before
     *  releasing reset. */
    private suspend fun resetIntoBootloader() {
        // 1. Assert EN (RTS high), IO0 floating (DTR low)
        setDtrRts(dtr = false, rts = true);  delay(ESP_RESET_PULSE_MS)
        // 2. Pull IO0 low (DTR high) while EN stays asserted, then release EN
        setDtrRts(dtr = true,  rts = true);  delay(5)
        setDtrRts(dtr = true,  rts = false); delay(ESP_RESET_RELEASE_MS)
        // 3. Release IO0 — chip stays in download mode
        setDtrRts(dtr = false, rts = false); delay(ESP_GPIO0_RELEASE_MS)
    }

    private fun setDtrRts(dtr: Boolean, rts: Boolean) {
        when (vendorId) {
            VID_SILABS_CP210X -> {
                // Bit 0=DTR, bit 1=RTS in low byte; mask in high byte.
                val state = (if (dtr) CP210X_MHS_DTR else 0x00) or (if (rts) 0x02 else 0x00)
                val mask = CP210X_MHS_MASK shl 8
                connection.controlTransfer(
                    USB_REQ_TYPE_VENDOR_OUT, CP210X_SET_MHS, state or mask, 0, null, 0, ESP_BULK_TIMEOUT_MS
                )
            }
            VID_QINHENG_CH340 -> {
                // CH340 MODEM_CTRL: bit 5=~DTR, bit 6=~RTS (active-low).
                var v = CH340_MODEM_DEFAULT
                if (dtr) v = v and CH340_MODEM_DTR_NEG.inv()
                if (rts) v = v and CH340_MODEM_RTS_NEG.inv()
                connection.controlTransfer(
                    USB_REQ_TYPE_VENDOR_OUT, CH340_REQ_MODEM_CTRL, v, 0, null, 0, ESP_BULK_TIMEOUT_MS
                )
            }
            else -> {
                // Standard CDC-ACM: bit 0=DTR, bit 1=RTS.
                val value = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
                connection.controlTransfer(
                    USB_REQ_TYPE_CLASS_OUT, CDC_SET_CONTROL_LINE_STATE, value, intf.id, null, 0, ESP_BULK_TIMEOUT_MS
                )
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  SLIP transport
    // ────────────────────────────────────────────────────────────────────

    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        // Standard USB CLEAR_FEATURE(ENDPOINT_HALT) — the PC driver does this
        // automatically after a stall; Android's USB host does not.
        connection.controlTransfer(
            USB_BMREQ_SET_FEATURE,  // bmReqType: host→device, standard, endpoint
            USB_REQ_CLEAR_FEATURE,  // CLEAR_FEATURE
            USB_ENDPOINT_HALT,  // ENDPOINT_HALT
            endpoint.address,
            null, 0, ESP_BULK_TIMEOUT_MS
        )
    }

    /** Send a single command and wait for the ROM's reply.
     *  @param checksumOver bytes to include in the SLIP header checksum
     *         (only [ESP_FLASH_DATA] uses a non-empty checksum).
     *  @return true if the ROM ACKed with status=0. */
    private fun sendCommand(
        op: Int,
        payload: ByteArray,
        checksumOver: ByteArray = ByteArray(0),
        expectReply: Boolean = true,
        timeoutMs: Int = ESP_BULK_TIMEOUT_MS,
    ): Boolean {
        val cs = if (checksumOver.isEmpty()) 0 else checksum(checksumOver)
        val header = byteArrayOf(0x00, op.toByte()) +
            leShort(payload.size) +
            leInt(cs)
        val frame = SlipFramer.encode(header + payload)
        val out = outEndpoint ?: return false
        val writeStart = System.currentTimeMillis()
        if (connection.bulkTransfer(out, frame, frame.size, timeoutMs) < 0) {
            dbgErr("sendCmd op=0x%02x: bulkTransfer OUT failed (frame=${frame.size}B)".format(op))
            return false
        }
        if (!expectReply) return true
        // The ROM may have buffered SYNC echoes or stale replies in the
        // bulk-in pipe. Read until we either match our opcode or the
        // timeout elapses — drop any other frames silently.
        val deadline = System.currentTimeMillis() + timeoutMs
        var discarded = 0
        var lastDiscardedOp = -1
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(ESP_IFCLAIM_DRAIN_MS.toInt())
            val reply = readSlipPacket(remainingMs)
            if (reply == null) {
                val elapsed = System.currentTimeMillis() - writeStart
                // After 3s of silence, clear the IN endpoint halt (Android
                // does not auto-clear) and give it one more chance.
                if (elapsed > ESP_IN_EP_HALT_THRESHOLD_MS) {
                    inEndpoint?.let { clearEndpointHalt(it) }
                    dbg("sendCmd op=0x%02x: cleared IN endpoint halt, retrying...".format(op))
                    val retryReply = readSlipPacket(3_000)
                    if (retryReply != null && retryReply.size >= 2 && 
                        retryReply[0] == ESP_ROM_RETRY_REPLY_MAGIC && retryReply[1] == op.toByte()) {
                        return parseReplyOk(op, retryReply)
                    }
                }
                if (discarded > 0) {
                    dbgErr("sendCmd op=0x%02x: TIMEOUT after ${elapsed}ms (discarded $discarded frames, last op=0x%02x)".format(op, lastDiscardedOp))
                } else {
                    dbgErr("sendCmd op=0x%02x: TIMEOUT after ${elapsed}ms (no data received)".format(op))
                }
                return false
            }
            if (reply.size >= 2 && reply[0] == ESP_ROM_REPLY_OK && reply[1] == op.toByte()) {
                return parseReplyOk(op, reply)
            }
            // Mismatched frame — discard and keep reading.
            discarded++
            if (reply.size >= 2) {
                lastDiscardedOp = reply[1].toInt() and 0xFF
                val hex = reply.joinToString("") { "%02x".format(it) }
                dbg("  discarded #$discarded: ${reply.size}B op=0x%02x raw=$hex".format(lastDiscardedOp))
            }
        }
        dbgErr("sendCmd op=0x%02x: DEADLINE (discarded $discarded)".format(op))
        return false
    }

    /** Issue one 36×0x55 SYNC frame and return true if the ROM replies OK. */
    private fun trySync(): Boolean {
        val syncData = ByteArray(ESP_SYNC_FRAME_DATA_LEN) { ESP_SYNC_FRAME_BYTE }
        val payload = ESP_SYNC_FRAME_PREFIX + syncData
        val header = byteArrayOf(0x00, ESP_SYNC.toByte()) + leShort(payload.size) + leInt(0)
        val frame = SlipFramer.encode(header + payload)
        val out = outEndpoint ?: return false
        if (connection.bulkTransfer(out, frame, frame.size, ESP_SYNC_TIMEOUT_MS) < 0) return false
        // The ROM emits multiple frames in response (one per SYNC echo); the
        // first valid OK reply is enough for our purposes.
        val reply = readSlipPacket(ESP_SYNC_TIMEOUT_MS) ?: return false
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
        val deadline = System.currentTimeMillis() + ESP_DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val n = connection.bulkTransfer(ep, buf, buf.size, ESP_SHORT_READ_TIMEOUT_MS)
            if (n <= 0) break
        }
    }

    // Simple ring-buffer SLIP decoder (no persistent state machines).
    // Bytes accumulate here, SlipFramer.decode() extracts complete frames,
    // consumed bytes are trimmed.  This avoids the ghost-frame bugs of
    // byte-by-byte state machines and slipPending queues.
    private val ringBuf = ArrayList<Byte>()

    /** Pull bytes from the bulk-in endpoint until a complete SLIP frame
     *  is decoded, or [timeoutMs] elapses. */
    private fun readSlipPacket(timeoutMs: Int): ByteArray? {
        val ep = inEndpoint ?: return null
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Try to decode from buffered bytes first
            val packets = SlipFramer.decode(ringBuf.toByteArray())
            if (packets.isNotEmpty()) {
                val pkt = packets[0]
                // Find the end of this packet in ringBuf (after the second 0xC0/END)
                var endIdx = -1
                var started = false
                var consumed = 0
                for (i in ringBuf.indices) {
                    val b = ringBuf[i]
                    if (!started && b == SLIP_END.toByte()) { started = true; continue }
                    if (started) {
                        if (b == SLIP_END.toByte()) { endIdx = i; break }
                    }
                }
                if (endIdx > 0) {
                    repeat(endIdx + 1) { ringBuf.removeAt(0) }
                }
                // Discard empty frames (consecutive 0xC0 bytes)
                if (pkt.isNotEmpty()) return pkt
                continue
            }
            // Read more bytes from USB
            val n = connection.bulkTransfer(ep, buf, buf.size, ESP_IFCLAIM_DRAIN_MS.toInt())
            if (n > 0) {
                for (i in 0 until n) ringBuf.add(buf[i])
            }
        }
        return null
    }

    // Remove the now-unused persistent state fields and functions
    // (slipPartial, slipEscaped, slipPending, slipDecodeByte)

    /** ROM reply format: `<0x01><opcode><size:2><value:4><data...>`.
     *  ESP32-S2/S3/C3 ROMs use a 2-byte status trailer: `<status:1><error:1>`.
     *  ESP32 (classic) ROMs use a 4-byte status trailer: `<status:1><error:1><reserved:2>`.
     *  We check the 2-byte position first since AirTAK targets ESP32-S3.
     *  status == 0 means success. */
    private fun parseReplyOk(expectedOp: Int, reply: ByteArray): Boolean {
        if (reply.size < 10) return false  // 8-byte header + at least 2 status bytes
        if (reply[0] != ESP_ROM_REPLY_OK) return false
        if (reply[1] != expectedOp.toByte()) return false
        // Check 2-byte status trailer first (ESP32-S3 used by AirTAK), then
        // fall back to 4-byte (ESP32 classic / stub).
        val statusByte2 = reply.getOrNull(reply.size - 2)
        if (statusByte2 != null && statusByte2 == ESP_ROM_REPLY_STATUS_OK_BYTE2) return true
        val statusByte4 = reply.getOrNull(reply.size - 4)
        return statusByte4 != null && statusByte4 == ESP_ROM_REPLY_STATUS_OK_BYTE4
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
        var c = ESP_XOR_CHECKSUM_SEED
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c
    }
}
