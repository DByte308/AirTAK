// AirTAK Android — data/ble/BleFraming.kt
// Planning: §10.2 (BLE framing protocol)
//
// Frame header (6 bytes, little-endian):
//   offset 0  uint16  total_length  — total bytes of reassembled payload
//   offset 2  uint16  chunk_index   — 0-based
//   offset 4  uint16  chunk_count   — total chunks for this payload
//
// Payloads larger than (MTU − 6 − 3) bytes are split. Single-chunk payloads use
// chunk_index=0, chunk_count=1.
//
// The receiver maintains one reassembly state per characteristic — chunked
// payloads from distinct characteristics must not interleave at the chunk
// level (each characteristic notifies independently and serializes its own
// frames on the device side).

package com.airtak.data.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BleFraming {
    const val HEADER_BYTES = 6
    const val ATT_OVERHEAD = 3                // ATT op + handle
    const val MAX_PAYLOAD_BYTES = 65_535       // total_length is uint16

    /**
     * Split [payload] into chunks of [chunkPayloadSize] bytes each, prefixed with the
     * 6-byte header. chunkPayloadSize = MTU − 6 − 3 = effective ATT payload.
     */
    fun encode(payload: ByteArray, chunkPayloadSize: Int): List<ByteArray> {
        require(chunkPayloadSize in 1..(517 - HEADER_BYTES - ATT_OVERHEAD)) {
            "chunkPayloadSize $chunkPayloadSize out of range"
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "payload too large (${payload.size} bytes)" }
        if (payload.isEmpty()) {
            // Empty payload still encodes a single-chunk frame to signal e.g. a config read request.
            return listOf(header(0, 0, 1))
        }
        val count = ((payload.size + chunkPayloadSize - 1) / chunkPayloadSize).coerceAtLeast(1)
        return (0 until count).map { i ->
            val start = i * chunkPayloadSize
            val end = minOf(start + chunkPayloadSize, payload.size)
            val chunk = payload.copyOfRange(start, end)
            val h = header(payload.size, i, count)
            h + chunk
        }
    }

    private fun header(total: Int, idx: Int, count: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(total.toShort()); putShort(idx.toShort()); putShort(count.toShort())
        }.array()

    /**
     * Per-characteristic reassembly state. Single-threaded; callers must externally serialize.
     * Chunks are appended in declared index order; the protocol guarantees in-order delivery
     * over a single GATT characteristic so offsets are tracked as a running write cursor.
     */
    class Reassembler {
        private var buffer: ByteArray? = null
        private var expectedCount: Int = 0
        private var nextIndex: Int = 0
        private var cursor: Int = 0

        fun feed(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER_BYTES) { reset(); return null }
            val bb = ByteBuffer.wrap(frame, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val total = bb.short.toInt() and 0xFFFF
            val idx   = bb.short.toInt() and 0xFFFF
            val count = bb.short.toInt() and 0xFFFF
            if (count == 0 || idx >= count) { reset(); return null }
            val data = frame.copyOfRange(HEADER_BYTES, frame.size)
            if (idx == 0) {
                buffer = ByteArray(total)
                expectedCount = count
                nextIndex = 0
                cursor = 0
            }
            val buf = buffer ?: run { reset(); return null }
            if (count != expectedCount || idx != nextIndex) { reset(); return null }
            if (cursor + data.size > buf.size) { reset(); return null }
            System.arraycopy(data, 0, buf, cursor, data.size)
            cursor += data.size
            nextIndex++
            return if (nextIndex == expectedCount) {
                if (cursor != buf.size) { reset(); return null }
                val out = buf
                reset()
                out
            } else null
        }

        fun reset() {
            buffer = null
            expectedCount = 0
            nextIndex = 0
            cursor = 0
        }
    }
}
