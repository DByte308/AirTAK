// AirTAK Android — test/kotlin/com/airtak/data/mesh/MeshWireTest.kt
//
// Validates the manual protobuf Data encoder and the MESH_RX envelope decoder
// against synthetic packets shaped like the firmware Router.cpp output. We
// roundtrip Data through the envelope decoder because Data lives in the
// vendored Meshtastic proto (not generated into com.airtak.proto).

package com.airtak.data.mesh

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshWireTest {

    private fun wrap(portnum: Int, payload: ByteArray): ByteArray {
        val data = MeshWire.encodeData(portnum, payload, wantResponse = false)
        return ByteBuffer.allocate(1 + 16 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put((portnum and 0xFF).toByte())
            putInt(0xAABBCCDD.toInt()); putInt(0x11223344); putInt(0x55667788)
            put(0x03.toByte()); put(0x42.toByte()); put(0x00.toByte()); put(0x00.toByte())
            put(data)
        }.array()
    }

    private fun wrapData(portnum: Int, data: ByteArray): ByteArray {
        return ByteBuffer.allocate(1 + 16 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put((portnum and 0xFF).toByte())
            putInt(0xAABBCCDD.toInt()); putInt(0x11223344); putInt(0x55667788)
            put(0x03.toByte()); put(0x42.toByte()); put(0x00.toByte()); put(0x00.toByte())
            put(data)
        }.array()
    }

    @Test fun encodeDataRoundtripsThroughEnvelope() {
        val msg = "TANGO DOWN".toByteArray(Charsets.UTF_8)
        val pkt = MeshWire.decodeMeshRx(wrap(Portnum.TEXT_MESSAGE_APP, msg))
        assertNotNull(pkt); pkt!!
        assertEquals(Portnum.TEXT_MESSAGE_APP, pkt.portnum)
        assertArrayEquals(msg, pkt.payload)
    }

    @Test fun decodeMeshRxParsesOtaHeaderFields() {
        val pkt = MeshWire.decodeMeshRx(wrap(Portnum.AIRTAK_PLAYER, byteArrayOf(0x01, 0x02, 0x03)))!!
        assertEquals(0xAABBCCDDL, pkt.destNodeId)
        assertEquals(0x11223344L, pkt.srcNodeId)
        assertEquals(0x55667788L, pkt.packetId)
        assertEquals(0x03, pkt.flags)
        assertEquals(0x42, pkt.channelHash)
        assertEquals(Portnum.AIRTAK_PLAYER, pkt.portnum)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), pkt.payload)
    }

    @Test fun decodeMeshRxRejectsTruncatedEnvelope() {
        assertEquals(null, MeshWire.decodeMeshRx(ByteArray(10)))
    }

    @Test fun encodeDataUsesMeshtasticFieldNumbers() {
        val data = MeshWire.encodeData(
            portnum = Portnum.AIRTAK_PLAYER,
            payload = byteArrayOf(0x42),
            wantResponse = true,
            destNodeId = 0x01020304,
            requestId = 0x55667788,
        )
        val pkt = MeshWire.decodeMeshRx(wrapData(Portnum.AIRTAK_PLAYER, data))!!
        assertEquals(true, pkt.wantResponse)
        assertEquals(0x01020304L, pkt.dataDestNodeId)
        assertEquals(0x55667788L, pkt.requestId)
    }

    @Test fun decodePositionReadsMeshtasticFixed32Fields() {
        val bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x0D.toByte()); putInt((51.5010 * 10_000_000).toInt())
            put(0x15.toByte()); putInt((-0.1416 * 10_000_000).toInt())
            put(0x18.toByte()); put(12.toByte())
            put(0x25.toByte()); putInt(1_700_000_000.toInt())
            put(0x70.toByte()); put(32.toByte())
        }
        val payload = bb.array().copyOf(bb.position())
        val pos = MeshWire.decodePosition(payload)!!
        assertEquals(51.5010, pos.latitude, 0.000001)
        assertEquals(-0.1416, pos.longitude, 0.000001)
        assertEquals(12, pos.altitudeM)
        assertEquals(32, pos.precisionBits)
    }
}
