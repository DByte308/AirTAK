// MASH Android — data/ble/BleFraming.kt
// Planning: §10.2 | Roadmap: 3.6
//
// Android-side mirror of firmware Framing.h/.cpp.
// 6-byte frame header (little-endian): total_length (UInt16) || chunk_index (UInt16) || chunk_count (UInt16)
// Chunk payload size = MTU - ATT overhead (3 bytes) - header (6 bytes) = 517 - 3 - 6 = 508 bytes.
//
// NOTE: Matches the firmware BLE_MTU (517) minus the ATT Handle-Value Notification overhead (3 bytes).

package com.mash.data.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object BleFraming {

    private const val HEADER_SIZE   = 6
    private const val CHUNK_PAYLOAD = 508   // 517 MTU − 3 ATT overhead − 6 header

    data class FrameHeader(
        val totalLength: UShort,
        val chunkIndex: UShort,
        val chunkCount: UShort,
    )

    /** Encode [data] into a sequence of chunks ready to write to the characteristic. */
    fun encode(data: ByteArray): List<ByteArray> {
        val chunkCount = ((data.size + CHUNK_PAYLOAD - 1) / CHUNK_PAYLOAD).coerceAtLeast(1)
        return (0 until chunkCount).map { i ->
            val offset = i * CHUNK_PAYLOAD
            val slice  = data.copyOfRange(offset, minOf(offset + CHUNK_PAYLOAD, data.size))
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
            totalLength = buf.short.toUShort(),
            chunkIndex  = buf.short.toUShort(),
            chunkCount  = buf.short.toUShort(),
        )
        val payload = chunk.copyOfRange(HEADER_SIZE, chunk.size)
        return hdr to payload
    }

    /** Reassemble multiple chunks into the original [ByteArray]. Returns null while incomplete. */
    class Reassembler {
        private var buffer: ByteArray? = null
        private var received = 0

        fun consume(chunk: ByteArray): ByteArray? {
            val (hdr, payload) = decodeHeader(chunk) ?: return null
            if (buffer == null) buffer = ByteArray(hdr.totalLength.toInt())
            val offset = hdr.chunkIndex.toInt() * CHUNK_PAYLOAD
            payload.copyInto(buffer!!, offset)
            received++
            return if (received >= hdr.chunkCount.toInt()) buffer else null
        }

        fun reset() { buffer = null; received = 0 }
    }
}
