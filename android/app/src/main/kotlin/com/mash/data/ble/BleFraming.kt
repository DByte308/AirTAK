// AirTAK Android — data/ble/BleFraming.kt
// Planning: §10.2 | Roadmap: 3.6
//
// Android-side mirror of firmware Framing.h/.cpp.
// 6-byte frame header (little-endian): total_length (UInt16) || chunk_index (UInt16) || chunk_count (UInt16)
// Chunk payload size = MTU - ATT overhead (3 bytes) - header (6 bytes) = 517 - 3 - 6 = 508 bytes.
//
// NOTE: Matches the firmware BLE_MTU (517) minus the ATT Handle-Value Notification overhead (3 bytes).

package com.airtak.data.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BleFraming {

    private const val HEADER_SIZE   = 6
    private const val DEFAULT_CHUNK_PAYLOAD = 508   // 517 MTU - 3 ATT overhead - 6 header

    data class FrameHeader(
        val totalLength: Int,
        val chunkIndex: Int,
        val chunkCount: Int,
    )

    /** Encode [data] into a sequence of chunks ready to write to the characteristic. */
    fun encode(data: ByteArray, chunkPayloadSize: Int = DEFAULT_CHUNK_PAYLOAD): List<ByteArray> {
        require(chunkPayloadSize > 0) { "chunkPayloadSize must be positive" }
        val chunkCount = ((data.size + chunkPayloadSize - 1) / chunkPayloadSize).coerceAtLeast(1)
        return (0 until chunkCount).map { i ->
            val offset = i * chunkPayloadSize
            val slice  = data.copyOfRange(offset, minOf(offset + chunkPayloadSize, data.size))
            ByteBuffer.allocate(HEADER_SIZE + slice.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(data.size.toShort())
                .putShort(i.toShort())
                .putShort(chunkCount.toShort())
                .put(slice)
                .array()
        }
    }

    /** Parse a received notification into header + payload slice. */
    fun decodeHeader(chunk: ByteArray): Pair<FrameHeader, ByteArray>? {
        if (chunk.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val hdr = FrameHeader(
            totalLength = buf.short.toInt() and 0xFFFF,
            chunkIndex  = buf.short.toInt() and 0xFFFF,
            chunkCount  = buf.short.toInt() and 0xFFFF,
        )
        val payload = chunk.copyOfRange(HEADER_SIZE, chunk.size)
        return hdr to payload
    }

    /** Reassemble multiple chunks into the original [ByteArray]. Returns null while incomplete. */
    class Reassembler {
        private var buffer: ByteArray? = null
        private var seen: BooleanArray = BooleanArray(0)
        private var received = 0
        private var expectedTotalLength = -1
        private var expectedChunkCount = -1
        private var chunkPayloadSize = DEFAULT_CHUNK_PAYLOAD

        fun consume(chunk: ByteArray): ByteArray? {
            val (hdr, payload) = decodeHeader(chunk) ?: return null
            val totalLength = hdr.totalLength
            val chunkIndex = hdr.chunkIndex
            val chunkCount = hdr.chunkCount
            if (chunkCount <= 0 || chunkIndex >= chunkCount || totalLength < 0) return null

            if (chunkIndex == 0 || buffer == null || totalLength != expectedTotalLength || chunkCount != expectedChunkCount) {
                buffer = ByteArray(totalLength)
                seen = BooleanArray(chunkCount)
                received = 0
                expectedTotalLength = totalLength
                expectedChunkCount = chunkCount
                chunkPayloadSize = payload.size.coerceAtLeast(1)
            }

            val offset = chunkIndex * chunkPayloadSize
            if (offset > totalLength) return null
            val bytesToCopy = minOf(payload.size, totalLength - offset)
            if (bytesToCopy < 0) return null
            payload.copyInto(buffer!!, offset, 0, bytesToCopy)
            if (!seen[chunkIndex]) {
                seen[chunkIndex] = true
                received++
            }
            if (received < expectedChunkCount) return null

            val complete = buffer
            reset()
            return complete
        }

        fun reset() {
            buffer = null
            seen = BooleanArray(0)
            received = 0
            expectedTotalLength = -1
            expectedChunkCount = -1
            chunkPayloadSize = DEFAULT_CHUNK_PAYLOAD
        }
    }
}
