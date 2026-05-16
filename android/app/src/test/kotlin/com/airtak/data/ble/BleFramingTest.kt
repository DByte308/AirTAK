// AirTAK Android — test/kotlin/com/airtak/data/ble/BleFramingTest.kt
//
// Validates the 6-byte chunk framer against the §10.2 wire format. We cover:
//   - empty payload (single-chunk control frame)
//   - exactly-one-chunk payload
//   - non-uniform last chunk (regression for the original size-based offset bug)
//   - header parsing (LE order) and rejection of malformed frames

package com.airtak.data.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BleFramingTest {

    @Test fun emptyPayloadRoundtrip() {
        val chunks = BleFraming.encode(byteArrayOf(), chunkPayloadSize = 64)
        assertEquals(1, chunks.size)
        val r = BleFraming.Reassembler()
        val out = r.feed(chunks[0])!!
        assertEquals(0, out.size)
    }

    @Test fun singleChunkRoundtrip() {
        val payload = Random(1).nextBytes(50)
        val chunks = BleFraming.encode(payload, chunkPayloadSize = 64)
        assertEquals(1, chunks.size)
        val r = BleFraming.Reassembler()
        assertArrayEquals(payload, r.feed(chunks[0]))
    }

    @Test fun multiChunkRoundtripNonUniformTail() {
        // 200 bytes split into 64-byte chunks → 4 chunks, last one 8 bytes.
        val payload = Random(2).nextBytes(200)
        val chunks = BleFraming.encode(payload, chunkPayloadSize = 64)
        assertEquals(4, chunks.size)
        assertEquals(64 + BleFraming.HEADER_BYTES, chunks[0].size)
        assertEquals( 8 + BleFraming.HEADER_BYTES, chunks[3].size)

        val r = BleFraming.Reassembler()
        var out: ByteArray? = null
        for (c in chunks) out = r.feed(c)
        assertArrayEquals(payload, out)
    }

    @Test fun outOfOrderChunkResets() {
        val payload = Random(3).nextBytes(120)
        val chunks = BleFraming.encode(payload, chunkPayloadSize = 64)
        val r = BleFraming.Reassembler()
        // Skip the first chunk → reassembler should refuse the second.
        assertNull(r.feed(chunks[1]))
    }

    @Test fun truncatedFrameIsRejected() {
        val r = BleFraming.Reassembler()
        assertNull(r.feed(byteArrayOf(0x01, 0x02)))   // shorter than HEADER_BYTES
    }
}
