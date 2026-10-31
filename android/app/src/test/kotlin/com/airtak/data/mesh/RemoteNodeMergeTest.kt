// AirTAK Android — test/data/mesh/RemoteNodeMergeTest.kt
// #24 receiver merge round-trip tests.
//
// These exercise pure, unit-testable merge logic (no Room / MeshRepository). The
// payloads are built by a tiny wire encoder so the three presence states
// (ABSENT / PRESENT-empty / PRESENT-value) are deterministic, independent of the
// proto3 `optional` regeneration.

package com.airtak.data.mesh

import com.airtak.data.db.RemoteNodeEntity
import com.airtak.proto.PlayerStatePacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// ── Tiny protobuf wire encoder (field presence is the point of the test) ──────
private fun varint(value: Long): ByteArray {
    val out = ArrayList<Byte>()
    var v = value
    do {
        var b = (v and 0x7f).toInt()
        v = v shr 7
        if (v != 0L) b = b or 0x80
        out.add(b.toByte())
    } while (v != 0L)
    return out.toByteArray()
}

private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

private enum class F { ABSENT, EMPTY, VALUE }

private fun strField(field: Int, state: F, value: String = ""): ByteArray = when (state) {
    F.ABSENT -> ByteArray(0)
    F.EMPTY -> tag(field, 2) + varint(0)
    F.VALUE -> {
        val b = value.toByteArray(Charsets.UTF_8)
        tag(field, 2) + varint(b.size.toLong()) + b
    }
}

private fun encPlayerPayload(
    nodeId: Long = 0,
    callsign: F = F.ABSENT,
    callsignValue: String = "",
    status: Long = 0,
    color: Long = 0,
    transition: F = F.ABSENT,
    fireteam: F = F.ABSENT,
    fireteamValue: String = "",
    squad: F = F.ABSENT,
    squadValue: String = "",
): ByteArray {
    var b = ByteArray(0)
    if (nodeId != 0L) { b += varint(1L shl 3); b += varint(nodeId) }
    b += strField(2, callsign, callsignValue)
    b += varint(3L shl 3); b += varint(status)  // status always present
    if (color != 0L) { b += varint(4L shl 3); b += varint(color) }
    if (transition != F.ABSENT) { b += tag(5, 0); b += varint(if (transition == F.VALUE) 1 else 0) }
    b += strField(6, fireteam, fireteamValue)
    b += strField(7, squad, squadValue)
    return b
}

private fun entity(callsign: String = "", unit: String = "") = RemoteNodeEntity(
    nodeId = 1L,
    callsign = callsign,
    fireteamId = unit,
    lastSeenMs = 0L,
)

private fun now() = 1234L

class RemoteNodeMergeTest {

    @Test
    fun `present value sets callsign`() {
        val payload = encPlayerPayload(callsign = F.VALUE, callsignValue = "CHK")
        val player = PlayerStatePacket.parseFrom(payload)
        val merged = mergePlayerState(entity(callsign = "OLD"), player, payload, now())
        assertEquals("CHK", merged.callsign)
    }

    @Test
    fun `old packet absent keeps prior callsign`() {
        val payload = encPlayerPayload(callsign = F.ABSENT)
        val player = PlayerStatePacket.parseFrom(payload)
        val merged = mergePlayerState(entity(callsign = "OLD"), player, payload, now())
        assertEquals("OLD", merged.callsign)   // absent -> keep
    }

    @Test
    fun `present empty clears to short-long fallback`() {
        val payload = encPlayerPayload(callsign = F.EMPTY)
        val player = PlayerStatePacket.parseFrom(payload)
        val merged = mergePlayerState(entity(callsign = "OLD"), player, payload, now())
        assertEquals("", merged.callsign)      // present-empty -> clear (name resolution falls back)
    }

    @Test
    fun `rename updates to new value`() {
        val payload = encPlayerPayload(callsign = F.VALUE, callsignValue = "NEW")
        val player = PlayerStatePacket.parseFrom(payload)
        val merged = mergePlayerState(entity(callsign = "OLD"), player, payload, now())
        assertEquals("NEW", merged.callsign)
    }

    @Test
    fun `unit membership set clear and absent`() {
        // set unit
        var payload = encPlayerPayload(fireteam = F.VALUE, fireteamValue = "Alpha")
        var merged = mergePlayerState(entity(unit = "OLD"), PlayerStatePacket.parseFrom(payload), payload, now())
        assertEquals("Alpha", merged.fireteamId)
        // clear unit
        payload = encPlayerPayload(fireteam = F.EMPTY)
        merged = mergePlayerState(entity(unit = "OLD"), PlayerStatePacket.parseFrom(payload), payload, now())
        assertEquals("", merged.fireteamId)
        // absent unit -> keep
        payload = encPlayerPayload(fireteam = F.ABSENT)
        merged = mergePlayerState(entity(unit = "KEEP"), PlayerStatePacket.parseFrom(payload), payload, now())
        assertEquals("KEEP", merged.fireteamId)
    }

    @Test
    fun `wireHasField true when present-empty`() {
        val payload = encPlayerPayload(callsign = F.EMPTY)
        assertEquals(true, MeshWire.wireHasField(payload, 2))
    }

    @Test
    fun `wireHasField false when absent`() {
        val payload = encPlayerPayload(callsign = F.ABSENT)
        assertEquals(false, MeshWire.wireHasField(payload, 2))
    }

    @Test
    fun `mergeNodeInfo null absent keeps present empty clears value sets`() {
        val existing = entity()
        // null (absent) -> keep stored short name
        assertEquals("KEEP", mergeNodeInfo(existing.copy(shortName = "KEEP"), MeshtasticUser(null, null, null), now()).shortName)
        // present-empty ("") -> clear
        assertEquals("", mergeNodeInfo(existing, MeshtasticUser(null, null, ""), now()).shortName)
        // present value -> set
        assertEquals("SV", mergeNodeInfo(existing, MeshtasticUser(null, null, "SV"), now()).shortName)
        // long name value -> set
        assertEquals("LONG", mergeNodeInfo(existing, MeshtasticUser(null, "LONG", null), now()).longName)
    }
}
