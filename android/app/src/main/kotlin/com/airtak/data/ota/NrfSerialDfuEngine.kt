// AirTAK Android — data/ota/NrfSerialDfuEngine.kt
// Planning: §3.2 / §6.1 | Roadmap: 6.1 (revised)
//
// Nordic Secure DFU over USB CDC-ACM (the protocol spoken by Adafruit's
// `adafruit-nrfutil dfu serial`). The input firmware is the same signed
// `.zip` distribution we feed to the BLE path; the engine parses the zip,
// extracts the manifest, the init packet (`*.dat`), and the firmware image
// (`*.bin`), then runs the Secure DFU state machine.
//
// Wire format (per `nordicsemi.dfu.dfu_transport_serial`):
//
//   • 115200 8N1, no flow control, on a standard CDC-ACM interface
//     (Adafruit's bootloader presents itself with VID 0x239A).
//   • Every control message is SLIP-framed (END=0xC0). Control opcodes are
//     the first byte of the payload.
//   • Every data write (for either the init packet or the firmware image)
//     is also SLIP-framed but consists only of raw bytes — the bootloader
//     attributes the bytes to the most recently CREATEd object.
//
// State machine for one object (command or data):
//   1. SELECT  → bootloader replies with (max_size, offset, crc) for any
//                in-flight partial transfer of that object type.
//   2. CREATE  → declare a new object of size N within max_size.
//   3. write   → push raw bytes (one or more SLIP frames totalling N bytes).
//   4. CALC_CRC → bootloader returns (offset, crc) over received bytes;
//                we verify against our own running CRC32.
//   5. EXECUTE → commit the object (writes flash for data, validates
//                signature for command).
//
// The firmware image is split into max_size-byte objects; the init packet
// is always a single object.

package com.airtak.data.ota

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import kotlinx.coroutines.delay
import com.airtak.config.constants.CDC_SET_CONTROL_LINE_STATE
import com.airtak.config.constants.CDC_SET_LINE_CODING
import com.airtak.config.constants.NRF_CRC_RESP_MIN_SIZE
import com.airtak.config.constants.NRF_OBJ_TYPE_COMMAND
import com.airtak.config.constants.NRF_OBJ_TYPE_DATA
import com.airtak.config.constants.NRF_OP_CALC_CRC
import com.airtak.config.constants.NRF_OP_CREATE
import com.airtak.config.constants.NRF_OP_EXECUTE
import com.airtak.config.constants.NRF_OP_MTU_GET
import com.airtak.config.constants.NRF_OP_PING
import com.airtak.config.constants.NRF_OP_RESPONSE
import com.airtak.config.constants.NRF_OP_SELECT
import com.airtak.config.constants.NRF_OP_SET_PRN
import com.airtak.config.constants.NRF_PING_ID
import com.airtak.config.constants.NRF_PRN_VALUE
import com.airtak.config.constants.NRF_RESPONSE_HEADER_BYTES
import com.airtak.config.constants.NRF_RESULT_SUCCESS
import com.airtak.config.constants.NRF_SELECT_RESP_MIN_SIZE
import com.airtak.config.constants.NRF_SERIAL_BULK_TIMEOUT_MS
import com.airtak.config.constants.NRF_SERIAL_DEFAULT_BAUD
import com.airtak.config.constants.NRF_SERIAL_SHORT_READ_TIMEOUT_MS
import com.airtak.config.constants.NRF_SLIP_CHUNK_SIZE
import com.airtak.config.constants.NRF_SLIP_MAX_OVERHEAD
import com.airtak.config.constants.SLIP_END
import com.airtak.config.constants.SLIP_ESC
import com.airtak.config.constants.SLIP_ESC_END
import com.airtak.config.constants.SLIP_ESC_ESC
import com.airtak.config.constants.USB_CDC_CLASS_CONTROL
import com.airtak.config.constants.USB_CDC_CLASS_DATA
import com.airtak.config.constants.USB_CDC_DATA_BITS_8
import com.airtak.config.constants.USB_CDC_DTR_RTS_ASSERTED
import com.airtak.config.constants.USB_CDC_PARITY_NONE
import com.airtak.config.constants.USB_CDC_STOP_BITS_1
import com.airtak.config.constants.USB_REQ_TYPE_CLASS_OUT
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

class NrfSerialDfuEngine(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val usbDevice: android.hardware.usb.UsbDevice,
) : FirmwareUpdateManager.FirmwareUpdateEngine {
    override var onDebug: ((String) -> Unit)? = null
    private fun dbg(msg: String) { android.util.Log.d("NrfSerial", msg); onDebug?.invoke(msg) }
    private fun dbgErr(msg: String) { android.util.Log.e("NrfSerial", msg); onDebug?.invoke("ERROR: $msg") }

    companion object {
        // All constants are canonical in config/constants/OtaConstants.kt
    }

    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    override suspend fun initialize(): Result<DeviceInfo> {
        dbg("init: locating endpoints...")
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) outEndpoint = ep
            else inEndpoint = ep
        }
        if (outEndpoint == null || inEndpoint == null) {
            dbgErr("CDC bulk endpoints not found")
            return Result.failure(Exception("CDC bulk endpoints not found"))
        }
        dbg("endpoints OK")
       if (!connection.claimInterface(intf, true)) {
            dbgErr("claimInterface data failed")
            return Result.failure(Exception("Could not claim CDC data interface"))
       }
        dbg("data interface claimed")

        // The Adafruit nRF52 bootloader is a composite device: interface 0
        // is CDC Control (class 0x02, receives SET_LINE_CODING + DTR/RTS),
        // interface 1 is CDC Data (class 0x0A, has BULK endpoints).
        // We already claimed the data interface; now claim the control one
        // so the controlTransfer calls in configureCdcAcm can reach it.
        val ctrlIntf = findControlInterface()
        if (ctrlIntf != null && ctrlIntf.id != intf.id) {
            if (!connection.claimInterface(ctrlIntf, true)) {
                dbgErr("claimInterface control failed")
                return Result.failure(Exception("Could not claim CDC control interface"))
            }
            dbg("control interface claimed")
        }

        // Configure the Adafruit bootloader CDC endpoint for 115200 8N1 +
        // DTR/RTS asserted (the bootloader gates serial RX on DTR).
        dbg("configuring CDC ACM 115200...")
        if (!configureCdcAcm(NRF_SERIAL_DEFAULT_BAUD)) {
            dbgErr("configureCdcAcm failed")
            return Result.failure(Exception("Failed to configure USB serial for nRF52 bootloader"))
        }
        dbg("CDC ACM configured")

        // PING is a cheap probe; if the bootloader responds we know the
        // serial pipe is alive and we're talking to the right firmware.
        val pingId = NRF_PING_ID
        dbg("sending PING...")
        sendControl(byteArrayOf(NRF_OP_PING.toByte(), pingId))
        val pingResp = readResponse(NRF_OP_PING.toByte())
        if (pingResp == null) {
            dbgErr("PING no response")
            return Result.failure(Exception(
                "nRF52 bootloader did not respond to PING. Make sure the device " +
                    "is in DFU mode (double-tap RESET to enter the bootloader)."
            ))
        }
        dbg("PING response OK")
        if (pingResp.size < 1 || pingResp[0] != pingId) {
            dbgErr("PING id mismatch: got ${pingResp[0].toInt() and 0xFF}, expected ${pingId.toInt() and 0xFF}")
            return Result.failure(Exception("PING id mismatch — wrong device on this serial port"))
        }

        // Disable packet receipt notifications: with PRN=0 the bootloader
        // ACKs only the CALC_CRC at the end of each object, simplifying our
        // state machine without a measurable throughput hit on USB.
        dbg("SET_PRN 0...")
        sendControl(byteArrayOf(NRF_OP_SET_PRN.toByte(), NRF_PRN_VALUE.toByte(), NRF_PRN_VALUE.toByte()))
        if (readResponse(NRF_OP_SET_PRN.toByte()) == null) {
            dbgErr("SET_PRN rejected")
            return Result.failure(Exception("SET_PRN rejected"))
        }
        dbg("init OK")

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
        // ---- 1. Parse the .zip distribution ------------------------------
        onProgress(UpdateProgress.Initializing("Parsing DFU package..."))
        val pkg = runCatching { parseDfuZip(firmware) }
            .getOrElse {
                return Result.failure(Exception(
                    "Firmware must be a .zip produced by adafruit-nrfutil " +
                        "(missing manifest.json or .dat/.bin): ${it.message}"
                ))
            }

        // ---- 2. Upload the init / command object -------------------------
        onProgress(UpdateProgress.Initializing("Uploading init packet..."))
        val initSel = selectObject(NRF_OBJ_TYPE_COMMAND.toByte())
            ?: return Result.failure(Exception("SELECT(command) failed"))
        val initMax = initSel.maxSize
        if (pkg.initPacket.size > initMax) {
            return Result.failure(Exception(
                "Init packet (${pkg.initPacket.size} B) exceeds bootloader limit ($initMax B)"
            ))
        }
        if (!streamObject(NRF_OBJ_TYPE_COMMAND.toByte(), pkg.initPacket, initMax)) {
            return Result.failure(Exception("Init packet upload failed (signature mismatch?)"))
        }

        // ---- 3. Upload the firmware image in max_size chunks -------------
        val dataSel = selectObject(NRF_OBJ_TYPE_DATA.toByte())
            ?: return Result.failure(Exception("SELECT(data) failed"))
        val objSize = dataSel.maxSize

        val reporter = ProgressReporter(
            totalBytes = pkg.firmwareImage.size.toLong(),
            onProgress = onProgress,
        )
        reporter.start()

        var offset = 0
        while (offset < pkg.firmwareImage.size) {
            val take = objSize.coerceAtMost(pkg.firmwareImage.size - offset)
            val chunk = pkg.firmwareImage.copyOfRange(offset, offset + take)
            if (!streamObject(NRF_OBJ_TYPE_DATA.toByte(), chunk, objSize)) {
                return Result.failure(Exception("Data object failed at offset $offset"))
            }
            offset += take
            reporter.update(take)
        }

        return Result.success(Unit)
    }

    override suspend fun finalize(): Result<Unit> {
        // The bootloader resets itself once the last data object's EXECUTE
        // returns successfully — no additional reset command needed. Just
        // release the USB interface so the new application enumerates.
        runCatching { connection.releaseInterface(intf) }
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        // Over USB we have no path back; the user will see the device
        // re-enumerate as the application USB-CDC interface (or it'll
        // come back over BLE if AirTAK BLE is the default boot mode).
        return Result.success("unknown")
    }

    // ────────────────────────────────────────────────────────────────────
    //  Secure DFU object state machine
    // ────────────────────────────────────────────────────────────────────

    private data class SelectResult(val maxSize: Int, val offset: Int, val crc: Int)

    /** SELECT a Secure DFU object type and decode the (max_size, offset, crc)
     *  triple from the response. */
    private fun selectObject(type: Byte): SelectResult? {
        sendControl(byteArrayOf(NRF_OP_SELECT.toByte(), type))
        val resp = readResponse(NRF_OP_SELECT.toByte()) ?: return null
        if (resp.size < NRF_SELECT_RESP_MIN_SIZE) return null
        val maxSize = leInt(resp, 0)
        val off = leInt(resp, 4)
        val crc = leInt(resp, 8)
        return SelectResult(maxSize, off, crc)
    }

    /** Upload one logical Secure DFU object: CREATE → write bytes → CALC_CRC
     *  → EXECUTE. Splits into chunks of [maxObjSize] for the data path.
     *  Returns true only when every step gets a SUCCESS response and the
     *  bootloader-reported CRC matches our local CRC32.  */
    private fun streamObject(type: Byte, payload: ByteArray, maxObjSize: Int): Boolean {
        require(payload.size <= maxObjSize) { "object too large for bootloader" }

        // CREATE object: <opcode><type><size:4 LE>
        sendControl(byteArrayOf(NRF_OP_CREATE.toByte(), type) + leInt(payload.size))
        readResponse(NRF_OP_CREATE.toByte()) ?: return false

        // Write the raw bytes — Adafruit's bootloader (and nrfutil) frame
        // each chunk of up to ~64 bytes as its own SLIP frame to fit the
        // CDC packet boundary on the device side. Using a single big frame
        // also works on most boards but ~64-byte chunks are the safe
        // interop choice.
        val chunkSize = NRF_SLIP_CHUNK_SIZE
        var sent = 0
        while (sent < payload.size) {
            val take = chunkSize.coerceAtMost(payload.size - sent)
            if (!sendRawSlip(payload, sent, take)) return false
            sent += take
        }

        // CALC_CRC: bootloader returns (offset:4, crc:4) over what it has.
        sendControl(byteArrayOf(NRF_OP_CALC_CRC.toByte()))
        val crcResp = readResponse(NRF_OP_CALC_CRC.toByte()) ?: return false
        if (crcResp.size < NRF_CRC_RESP_MIN_SIZE) return false
        val offset = leInt(crcResp, 0)
        val deviceCrc = leInt(crcResp, 4)
        if (offset != payload.size) return false
        val localCrc = CRC32().apply { update(payload) }.value.toInt()
        if (deviceCrc != localCrc) return false

        // EXECUTE: commit (write flash for data, validate signature for cmd).
        sendControl(byteArrayOf(NRF_OP_EXECUTE.toByte()))
        readResponse(NRF_OP_EXECUTE.toByte()) ?: return false
        return true
    }

    // ────────────────────────────────────────────────────────────────────
    //  USB CDC line config
    // ────────────────────────────────────────────────────────────────────

    // CDC ACM class codes
    private val CDC_CONTROL_CLASS = USB_CDC_CLASS_CONTROL
    private val CDC_DATA_CLASS = USB_CDC_CLASS_DATA

    /** Find the CDC Communications Interface (class 0x02).  nRF52 DFU
     *  exposes two interfaces: control (iface 0) and data (iface 1).
     *  SET_LINE_CODING / SET_CONTROL_LINE_STATE must target the control
     *  interface; sending to the data interface is rejected by the
     *  Adafruit bootloader. */
    private fun findControlInterface(): UsbInterface? {
        for (i in 0 until usbDevice.interfaceCount) {
            val candidate = usbDevice.getInterface(i)
            if (candidate.interfaceClass == CDC_CONTROL_CLASS) return candidate
        }
        return null
    }

    private fun configureCdcAcm(baud: Int): Boolean {
        val ctrlIntf = findControlInterface() ?: intf
        dbg("configureCdcAcm: using interface ${ctrlIntf.id} (class=0x${ctrlIntf.interfaceClass.toString(16)})")
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
            USB_REQ_TYPE_CLASS_OUT, CDC_SET_LINE_CODING, 0, ctrlIntf.id, data, data.size, NRF_SERIAL_BULK_TIMEOUT_MS
        )
        // Assert DTR + RTS — the Adafruit bootloader gates serial RX on DTR.
        val r2 = connection.controlTransfer(
            USB_REQ_TYPE_CLASS_OUT, CDC_SET_CONTROL_LINE_STATE, USB_CDC_DTR_RTS_ASSERTED, ctrlIntf.id, null, 0, NRF_SERIAL_BULK_TIMEOUT_MS
        )
        return r1 >= 0 && r2 >= 0
    }

    // ────────────────────────────────────────────────────────────────────
    //  SLIP transport
    // ────────────────────────────────────────────────────────────────────

    private fun sendControl(payload: ByteArray): Boolean = sendRawSlip(payload, 0, payload.size)

    private fun sendRawSlip(buf: ByteArray, off: Int, len: Int): Boolean {
        val out = outEndpoint ?: return false
        val frame = slipEncode(buf, off, len)
        return connection.bulkTransfer(out, frame, frame.size, NRF_SERIAL_BULK_TIMEOUT_MS) >= 0
    }

    /** Read a SLIP-framed response, verify it's an OP_RESPONSE for the
     *  expected request opcode with a SUCCESS result, and return the
     *  payload that trails the result byte. Returns null on any error. */
    private fun readResponse(expectedOp: Byte): ByteArray? {
        val packet = readSlipPacket() ?: return null
        if (packet.size < 3) return null
        if (packet[0] != NRF_OP_RESPONSE.toByte()) return null
        if (packet[1] != expectedOp) return null
        if (packet[2] != NRF_RESULT_SUCCESS.toByte()) return null
        return packet.copyOfRange(NRF_RESPONSE_HEADER_BYTES, packet.size)
    }

    // Persistent SLIP decoder state — survives across readSlipPacket calls
    // so partial frames spanning multiple USB transfers are reassembled.
    private var slipPartial: ArrayList<Byte>? = null
    private var slipEscaped = false
    private var slipPend = java.util.ArrayDeque<Byte>()

    private fun readSlipPacket(): ByteArray? {
        val ep = inEndpoint ?: return null
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + NRF_SERIAL_BULK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Drain pending bytes first
            while (slipPend.isNotEmpty()) {
                val pkt = slipDecode(slipPend.removeFirst())
                if (pkt != null) return pkt
            }
            val n = connection.bulkTransfer(ep, buf, buf.size, NRF_SERIAL_SHORT_READ_TIMEOUT_MS)
            if (n > 0) {
                for (i in 0 until n) {
                    val pkt = slipDecode(buf[i])
                    if (pkt != null) {
                        for (j in i + 1 until n) slipPend.addLast(buf[j])
                        return pkt
                    }
                }
            }
        }
        return null
    }

    private fun slipDecode(b: Byte): ByteArray? {
        if (slipPartial == null) {
            if (b == SLIP_END.toByte()) slipPartial = ArrayList()
        } else if (slipEscaped) {
            slipEscaped = false
            when (b) {
                SLIP_ESC_END.toByte() -> slipPartial!!.add(SLIP_END.toByte())
                SLIP_ESC_ESC.toByte() -> slipPartial!!.add(SLIP_ESC.toByte())
                else -> slipPartial!!.add(b)
            }
        } else when (b) {
            SLIP_ESC.toByte() -> slipEscaped = true
            SLIP_END.toByte() -> {
                val pkt = slipPartial!!.toByteArray()
                slipPartial = null
                if (pkt.isNotEmpty()) return pkt
                slipPartial = ArrayList()
            }
            else -> slipPartial!!.add(b)
        }
        return null
    }

    /** SLIP-encode [len] bytes from [buf] starting at [off] — bracketed by
     *  END (0xC0) on both sides; END/ESC bytes inside the payload are
     *  escaped. */
    private fun slipEncode(buf: ByteArray, off: Int, len: Int): ByteArray {
        val out = ArrayList<Byte>(len + NRF_SLIP_MAX_OVERHEAD)
        out.add(SLIP_END.toByte())
        for (i in off until off + len) {
            when (val b = buf[i]) {
                SLIP_END.toByte() -> { out.add(SLIP_ESC.toByte()); out.add(SLIP_ESC_END.toByte()) }
                SLIP_ESC.toByte() -> { out.add(SLIP_ESC.toByte()); out.add(SLIP_ESC_ESC.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(SLIP_END.toByte())
        return out.toByteArray()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Zip parsing
    // ────────────────────────────────────────────────────────────────────

    private data class DfuPackage(val initPacket: ByteArray, val firmwareImage: ByteArray)

    /** Pull the (init packet, firmware image) pair from a Nordic DFU
     *  distribution zip. The zip contains a `manifest.json` that names the
     *  `.dat` (init) and `.bin` (image) files under whichever section is
     *  populated (`application`, `softdevice`, `bootloader`, or combined).
     *  We accept any of those sections — the firmware nodes always pack
     *  an `application` for AirTAK builds. */
    private fun parseDfuZip(zipBytes: ByteArray): DfuPackage {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"]
            ?: error("manifest.json not found in zip")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONObject("manifest")
        // Try the populated sections in priority order.
        val sectionNames = listOf("application", "bootloader", "softdevice", "softdevice_bootloader")
        for (name in sectionNames) {
            if (!manifest.has(name)) continue
            val sec = manifest.getJSONObject(name)
            val datName = sec.optString("dat_file")
            val binName = sec.optString("bin_file")
            if (datName.isEmpty() || binName.isEmpty()) continue
            val dat = entries[datName] ?: error("$datName missing from zip")
            val bin = entries[binName] ?: error("$binName missing from zip")
            return DfuPackage(dat, bin)
        }
        error("manifest does not declare a usable section")
    }

    // ────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun leInt(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun leInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        ((buf[off + 2].toInt() and 0xFF) shl 16) or
        ((buf[off + 3].toInt() and 0xFF) shl 24)
}
