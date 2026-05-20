// AirTAK Android — data/ota/EspBleOtaEngine.kt
// Planning: §4.3 | Roadmap: 6.1 (revised)
//
// Custom BLE OTA engine for ESP32 devices. Sends firmware chunks via the
// OTA_COMMAND and OTA_DATA BLE characteristics using the AirTAK custom OTA
// protocol with CRC32 end-to-end validation.
//
// All writes go through BleRepository so they share its serialized tx queue
// (one outstanding GATT op at a time, flow-controlled by onCharacteristicWrite
// callbacks). OTA_COMMAND payloads (protobuf) are wrapped in the AirTAK 6-byte
// chunk header on the way out and reassembled from the same envelope on the
// way back. OTA_DATA chunks are written raw because the firmware feeds each
// ATT payload directly into esp_ota_write.

package com.airtak.data.ota

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundChannel
import com.airtak.data.ble.LinkState
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class EspBleOtaEngine(
    private val ble: BleRepository,
) : FirmwareUpdateManager.FirmwareUpdateEngine {
    override var onDebug: ((String) -> Unit)? = null

    companion object {
        // Status request frequency during chunk streaming.
        private const val STATUS_EVERY_N_CHUNKS = 50
        // Maximum wait for device to confirm OTA_COMPLETE (covers ~6 MB of
        // verification work on slow ESP32-S3 builds).
        private const val FINISH_TIMEOUT_MS = 30_000L
        // Maximum number of chunks allowed to sit in the BleRepository tx
        // queue ahead of the streaming loop. Keeps a moderate-sized pipeline
        // (so the GATT layer never idles waiting on us) without queuing the
        // whole image up front and starving OTA_COMMAND status requests.
        private const val MAX_QUEUE_DEPTH = 8

        // First byte of an ESP32 OTA app image (esp_image_header_t.magic).
        private const val ESP_APP_MAGIC = 0xE9.toByte()

        // Wire-format constants for airtak.OtaState.
        private const val STATE_RECEIVING = 1
        private const val STATE_COMPLETE  = 4
        private const val STATE_ERROR     = 5
    }

    private var deviceInfo: DeviceInfo = DeviceInfo("unknown", "ESP32-S3", "unknown")

    override suspend fun initialize(): Result<DeviceInfo> {
        // The ViewModel already reads DEVICE_STATUS via the connected GATT
        // session; nothing to do here beyond reporting what we know.
        return Result.success(deviceInfo)
    }

    override suspend fun transfer(
        firmware: ByteArray,
        onProgress: (UpdateProgress) -> Unit,
    ): Result<Unit> {
        if (firmware.isEmpty()) {
            return Result.failure(IllegalArgumentException("Firmware payload is empty"))
        }
        if (ble.linkState.value !is LinkState.Connected) {
            return Result.failure(IllegalStateException("BLE link is not connected"))
        }

        // Validate: the binary must be an OTA app image, not a factory image.
        // Factory images start with a bootloader (byte != 0xE9) and cannot be
        // flashed to an OTA app partition — their internal offsets are wrong.
        if (firmware[0] != ESP_APP_MAGIC) {
            return Result.failure(Exception(
                "This firmware file does not appear to be an OTA/app binary " +
                "(first byte is 0x${firmware[0].toUByte().toString(16)}, not 0xE9). " +
                "If you are trying to flash a factory image, use esptool.py on a PC " +
                "or the USB serial flasher in chip-download mode instead."
            ))
        }

        val crc = CRC32().apply { update(firmware) }.value

        // Drop any latched tx-failure from a prior aborted session so we don't
        // false-positive on it during this run.
        ble.clearTxFailure()

        // Drain stale OtaStatus notifications that may be sitting in the
        // SharedFlow from a previous aborted session.
        // (We rely on filter+first below; nothing to drain explicitly because
        //  SharedFlow.first() consumes only what arrives after subscription.)

        // 1. OtaStart
        onProgress(UpdateProgress.Initializing("Starting OTA…"))
        val startCmd = buildOtaStart(firmware.size, crc)
        ble.send(BleRepository.OutboundChannel.OtaCommand, startCmd)

        // Wait for the firmware to acknowledge with state=RECEIVING. If the
        // firmware never replies the user gets a real error instead of a
        // bogus "Firmware Updated".
        val startAck = waitForOtaStatus(timeoutMs = 5_000L)
            ?: return Result.failure(Exception("Device did not acknowledge OTA start"))
        if (startAck.state == STATE_ERROR) {
            return Result.failure(Exception("Device rejected OtaStart (err=${startAck.lastError})"))
        }
        if (startAck.state != STATE_RECEIVING) {
            return Result.failure(Exception("Unexpected OTA state after start: ${startAck.state}"))
        }

        // 2. Stream OTA_DATA chunks sized to the negotiated MTU. Each BLE
        //    write is a complete OTA chunk on the firmware side.
        val chunkSize = ble.otaDataChunkSize()
        val reporter = ProgressReporter(
            totalBytes = firmware.size.toLong(),
            onProgress = onProgress,
            statusEveryNChunks = STATUS_EVERY_N_CHUNKS,
        )
        reporter.start()

        var offset = 0
        var chunkCount = 0
        // Snapshot of the most recent device-reported status. Updated by a
        // collector launched below so the streaming loop can fail fast if the
        // device reports STATE_ERROR (e.g. esp_ota_write returned an error).
        val latestStatus = java.util.concurrent.atomic.AtomicReference<OtaStatus?>(startAck)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statusJob = collectorScope.launch {
            ble.inbound
                .filter { it.channel == InboundChannel.OtaCommand }
                .collect { frame -> latestStatus.set(parseOtaStatus(frame.payload)) }
        }

        try {
        while (offset < firmware.size) {
            val end = (offset + chunkSize).coerceAtMost(firmware.size)
            val chunk = firmware.copyOfRange(offset, end)
            ble.send(BleRepository.OutboundChannel.OtaData, chunk)
            reporter.update(chunk.size)
            offset = end
            chunkCount++

            // Backpressure: cap how far ahead we get of the actual GATT writes.
            // OTA_DATA is write-with-response so each chunk takes one round-
            // trip (~20–40 ms at MTU 517). Without this cap we'd enqueue ~1500
            // chunks instantly, then a status request would sit behind them
            // for ~60 s and the engine's 3 s wait would time out.
            while (ble.txQueueDepth() > MAX_QUEUE_DEPTH) {
                if (ble.linkState.value !is LinkState.Connected) {
                    return Result.failure(Exception("Link dropped mid-transfer at $offset/${firmware.size} bytes"))
                }
                ble.txFailure.value?.let { f ->
                    return Result.failure(Exception(
                        "GATT write to ${f.charUuid} failed after ${f.attempts} attempts " +
                            "(status=${f.status}) at $offset/${firmware.size} bytes"
                    ))
                }
                delay(20)
            }

            ble.txFailure.value?.let { f ->
                return Result.failure(Exception(
                    "GATT write to ${f.charUuid} failed after ${f.attempts} attempts " +
                        "(status=${f.status}) at $offset/${firmware.size} bytes"
                ))
            }

            // Periodic status request — also lets us fail fast if the device
            // has gone into STATE_ERROR (e.g. esp_ota_write failed mid-stream).
            if (chunkCount % STATUS_EVERY_N_CHUNKS == 0) {
                ble.send(BleRepository.OutboundChannel.OtaCommand, buildOtaStatusRequest())
            }
            val s = latestStatus.get()
            if (s != null && s.state == STATE_ERROR) {
                return Result.failure(Exception(
                    "Device reported OTA_ERROR after ${s.bytesWritten} bytes " +
                        "(esp_err=0x${s.lastError.toString(16)}). Streaming aborted."
                ))
            }
        }
        } finally {
            statusJob.cancel()
            collectorScope.cancel()
        }

        // 3. OtaFinish — firmware verifies CRC, sends OtaStatus(COMPLETE),
        //    then reboots (~250 ms later).
        onProgress(UpdateProgress.Initializing("Finalizing OTA…"))

        // Drain the tx queue so the status request below isn't sitting behind
        // hundreds of pending OTA_DATA writes.
        if (!ble.awaitTxIdle(timeoutMs = 60_000L)) {
            return Result.failure(Exception("Tx queue did not drain before finalize"))
        }

        // Before we finalize, request a final status snapshot. If the device
        // never received any bytes (bytes_written == 0) something is very
        // wrong on the link — fail loudly instead of pretending it worked.
        ble.send(BleRepository.OutboundChannel.OtaCommand, buildOtaStatusRequest())
        val finalStatus = waitForOtaStatus(timeoutMs = 5_000L)
        if (finalStatus == null) {
            return Result.failure(Exception("Device stopped responding before finalize (no OtaStatus reply)"))
        }
        if (finalStatus.bytesWritten <= 0) {
            return Result.failure(Exception("Device received 0 bytes — check BLE pairing / OTA characteristic permissions"))
        }
        if (finalStatus.bytesWritten < firmware.size) {
            return Result.failure(Exception(
                "Device only received ${finalStatus.bytesWritten}/${firmware.size} bytes before finalize " +
                    "(state=${finalStatus.state}, esp_err=0x${finalStatus.lastError.toString(16)}) " +
                    "— check device serial monitor for [OTA_DATA] writeChunk FAILED message"
            ))
        }

        ble.send(BleRepository.OutboundChannel.OtaCommand, buildOtaFinish(crc))

        // 4. Wait for OtaStatus(COMPLETE). The device sends it BEFORE it
        //    reboots (firmware-side delay ≈ 250 ms after the notify is queued)
        //    so an unexplained disconnect with no preceding COMPLETE means
        //    the update did not actually succeed.
        val terminal = withTimeoutOrNull(FINISH_TIMEOUT_MS) {
            var sawComplete = false
            while (true) {
                val link = ble.linkState.value
                if (link !is LinkState.Connected) {
                    return@withTimeoutOrNull if (sawComplete)
                        TerminalEvent.Complete else TerminalEvent.DisconnectedWithoutComplete
                }
                val status = waitForOtaStatus(timeoutMs = 1_000L) ?: continue
                when (status.state) {
                    STATE_COMPLETE -> { sawComplete = true /* keep looping until link drops */ }
                    STATE_ERROR    -> return@withTimeoutOrNull TerminalEvent.Error(status.lastError)
                    else           -> { /* VERIFYING / WRITING — keep waiting */ }
                }
            }
            @Suppress("UNREACHABLE_CODE") TerminalEvent.DisconnectedWithoutComplete
        }

        return when (terminal) {
            TerminalEvent.Complete -> Result.success(Unit)
            TerminalEvent.DisconnectedWithoutComplete ->
                Result.failure(Exception("Device disconnected before confirming firmware update — check device logs"))
            is TerminalEvent.Error -> Result.failure(Exception("Device reported OTA error (code=${terminal.code})"))
            null -> Result.failure(Exception("Device did not confirm firmware update within ${FINISH_TIMEOUT_MS / 1000}s"))
        }
    }

    override suspend fun finalize(): Result<Unit> = Result.success(Unit)

    /** Wait for the device to drop the BLE link (reboot) and reconnect with the
     *  new firmware. Returns the newly-advertised version string if it can be
     *  inferred; otherwise "unknown". Times out with failure so the UI shows
     *  the real outcome instead of a false success. */
    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        return runCatching {
            withTimeout(timeoutMs) {
                // First, observe the disconnect/reconnecting transition.
                ble.linkState.filter { it !is LinkState.Connected }.first()
                // Then wait for the link to come back up.
                ble.linkState.filter { it is LinkState.Connected }.first()
                "unknown"
            }
        }.recoverCatching { e ->
            if (e is TimeoutCancellationException) {
                throw Exception("Device did not reboot/reconnect within ${timeoutMs / 1000}s")
            }
            throw e
        }
    }

    // ── Protobuf encoding ────────────────────────────────────────────────────
    //
    // Hand-rolled because the OtaCommand schema is tiny and adding a runtime
    // protobuf dependency for four messages is not worth it. Layout matches
    // proto/airtak/ble.proto §OtaCommand:
    //   oneof command {
    //     OtaStart         start  = 1;   // wire tag 0x0A (field 1, LEN)
    //     OtaFinish        finish = 2;   // wire tag 0x12 (field 2, LEN)
    //     OtaStatusRequest status = 3;   // wire tag 0x1A (field 3, LEN)
    //     OtaCancel        cancel = 4;   // wire tag 0x22 (field 4, LEN)
    //   }

    private fun buildOtaStart(imageSize: Int, crc32: Long): ByteArray {
        // OtaStart { uint32 image_size = 1; uint32 crc32 = 2; }
        val inner = ByteArrayBuilder().apply {
            put(0x08.toByte()); putVarint(imageSize.toLong() and 0xFFFFFFFFL)
            put(0x10.toByte()); putVarint(crc32 and 0xFFFFFFFFL)
        }.toByteArray()
        return ByteArrayBuilder().apply {
            put(0x0A.toByte()); putVarint(inner.size.toLong()); put(inner)
        }.toByteArray()
    }

    private fun buildOtaFinish(crc32: Long): ByteArray {
        val inner = ByteArrayBuilder().apply {
            put(0x08.toByte()); putVarint(crc32 and 0xFFFFFFFFL)
        }.toByteArray()
        return ByteArrayBuilder().apply {
            put(0x12.toByte()); putVarint(inner.size.toLong()); put(inner)
        }.toByteArray()
    }

    private fun buildOtaStatusRequest(): ByteArray =
        byteArrayOf(0x1A.toByte(), 0x00.toByte()) // empty submessage

    // ── Inbound OtaStatus parsing ────────────────────────────────────────────

    private data class OtaStatus(
        val state: Int,
        val bytesWritten: Long,
        val totalSize: Long,
        val lastError: Long,
    )

    private sealed class TerminalEvent {
        data object Complete : TerminalEvent()
        data object DisconnectedWithoutComplete : TerminalEvent()
        data class Error(val code: Long) : TerminalEvent()
    }

    private suspend fun waitForOtaStatus(timeoutMs: Long): OtaStatus? = withTimeoutOrNull(timeoutMs) {
        val frame = ble.inbound
            .filter { it.channel == InboundChannel.OtaCommand }
            .first()
        parseOtaStatus(frame.payload)
    }

    /** Parse the relevant fields of airtak.OtaStatus. Unknown fields are
     *  skipped per the protobuf wire-format spec. */
    private fun parseOtaStatus(buf: ByteArray): OtaStatus {
        var state = 0
        var bytesWritten = 0L
        var totalSize = 0L
        var lastError = 0L
        val r = VarintReader(buf)
        while (r.hasMore()) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()
            when (wire) {
                0 -> { // varint
                    val v = r.readVarint()
                    when (field) {
                        1 -> state = v.toInt()
                        2 -> bytesWritten = v
                        3 -> totalSize = v
                        4 -> lastError = v
                    }
                }
                2 -> { // length-delimited (e.g. error_text string) — skip
                    val len = r.readVarint().toInt()
                    r.skip(len)
                }
                1 -> r.skip(8)  // 64-bit
                5 -> r.skip(4)  // 32-bit
                else -> break    // unknown wire type — stop parsing
            }
        }
        return OtaStatus(state, bytesWritten, totalSize, lastError)
    }

    // ── Tiny varint / buffer helpers ─────────────────────────────────────────

    private class ByteArrayBuilder {
        private val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        fun put(b: Byte) { ensure(1); buf.put(b) }
        fun put(bytes: ByteArray) { ensure(bytes.size); buf.put(bytes) }
        fun putVarint(value: Long) {
            var v = value
            ensure(10)
            while (v and 0x7FL.inv() != 0L) {
                buf.put(((v and 0x7FL) or 0x80L).toByte())
                v = v ushr 7
            }
            buf.put(v.toByte())
        }
        fun toByteArray(): ByteArray = ByteArray(buf.position()).also {
            val p = buf.position(); buf.position(0); buf.get(it, 0, p); buf.position(p)
        }
        private fun ensure(@Suppress("UNUSED_PARAMETER") n: Int) {
            // Pre-allocated to 64 bytes; the largest OtaCommand we emit is
            // OtaStart with two uint32 varints (~16 bytes worst case). No
            // growth is needed.
        }
    }

    private class VarintReader(private val buf: ByteArray) {
        private var pos = 0
        fun hasMore(): Boolean = pos < buf.size
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) break
            }
            return result
        }
        fun skip(n: Int) { pos = (pos + n).coerceAtMost(buf.size) }
    }
}
