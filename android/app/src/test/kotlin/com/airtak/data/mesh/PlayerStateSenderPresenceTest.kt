package com.airtak.data.mesh

import com.airtak.data.db.RemoteNodeEntity
import com.airtak.proto.PlayerStatePacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Uses the real committed generated bindings, not the tiny receiver-test wire encoder. */
class PlayerStateSenderPresenceTest {
    @Test fun senderSerializesExplicitlyEmptyIdentityAndAllUnitFields() {
        val bytes = PlayerStatePacket.newBuilder().applyNamePresence("", "", "", "", "").build().toByteArray()
        val packet = PlayerStatePacket.parseFrom(bytes)
        assertTrue(packet.hasCallsign())
        assertTrue(packet.hasFireteamId())
        assertTrue(packet.hasSquadId())
        assertTrue(packet.hasPlatoonId())
        assertTrue(packet.hasCompanyId())
        listOf(2, 6, 7, 8, 9).forEach { assertTrue(MeshWire.wireHasField(bytes, it), "missing field $it") }
    }

    @Test fun senderNullsRemainAbsentAfterSerialization() {
        val bytes = PlayerStatePacket.newBuilder().applyNamePresence(null, null, null, null, null).build().toByteArray()
        val packet = PlayerStatePacket.parseFrom(bytes)
        assertFalse(packet.hasCallsign())
        assertFalse(packet.hasFireteamId())
        assertFalse(packet.hasSquadId())
        assertFalse(packet.hasPlatoonId())
        assertFalse(packet.hasCompanyId())
    }

    @Test fun realSenderRoundTripSetsClearsKeepsAndRenamesPeerIdentity() {
        var peer = RemoteNodeEntity(nodeId = 1L, callsign = "OLD", lastSeenMs = 0L)
        fun receive(callsign: String?) {
            val bytes = PlayerStatePacket.newBuilder().applyNamePresence(callsign, null, null, null, null).build().toByteArray()
            peer = mergePlayerState(peer, PlayerStatePacket.parseFrom(bytes), bytes, 1234L)
        }
        receive("NEW")
        assertEquals("NEW", peer.callsign)
        receive(null)
        assertEquals("NEW", peer.callsign)
        receive("")
        assertEquals("", peer.callsign)
        receive("RENAMED")
        assertEquals("RENAMED", peer.callsign)
    }
}
