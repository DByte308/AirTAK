// AirTAK Android — src/test/kotlin/com/airtak/BleFramingTest.kt
// Planning: §10.2, §19.1 | Roadmap: Phase 1 exit criteria
//
// Tests chunk encode/decode and reassembly at various payload sizes and MTU boundaries.

package com.airtak

import com.airtak.data.ble.BleFraming
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BleFramingTest {

    @Test
    fun `single chunk for small payload`() {
        val data = ByteArray(100) { it.toByte() }
        val chunks = BleFraming.encode(data)
        assertEquals(1, chunks.size)
        val (hdr, payload) = BleFraming.decodeHeader(chunks[0])!!
        assertEquals(100.toUShort(), hdr.totalLength)
        assertEquals(0.toUShort(), hdr.chunkIndex)
        assertEquals(1.toUShort(), hdr.chunkCount)
        assertArrayEquals(data, payload)
    }

    @Test
    fun `two chunks for 509 byte payload`() {
        val data = ByteArray(509) { it.toByte() }
        val chunks = BleFraming.encode(data)
        assertEquals(2, chunks.size)
    }

    @Test
    fun `reassembler reconstructs original data`() {
        val data = ByteArray(600) { (it * 3).toByte() }
        val chunks = BleFraming.encode(data)
        val reassembler = BleFraming.Reassembler()
        var result: ByteArray? = null
        for (chunk in chunks) {
            result = reassembler.consume(chunk)
        }
        assertNotNull(result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `exact 508 byte payload is single chunk`() {
        val data = ByteArray(508) { 0xAB.toByte() }
        assertEquals(1, BleFraming.encode(data).size)
    }

    @Test
    fun `509 byte payload requires two chunks`() {
        val data = ByteArray(509) { 0xCD.toByte() }
        assertEquals(2, BleFraming.encode(data).size)
    }
}
