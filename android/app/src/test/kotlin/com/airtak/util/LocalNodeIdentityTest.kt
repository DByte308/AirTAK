package com.airtak.util

import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceConfig
import com.airtak.proto.DeviceStatus
import com.airtak.proto.GameConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalNodeIdentityTest {
    private val status = DeviceStatus.newBuilder().setNodeId(-1).setNodeName("Status Long").build()

    private fun identity(callsign: String = "", short: String = "", long: String = "") =
        LocalNodeIdentity.from(mapOf(
            ConfigGroup.GAME to GameConfig.newBuilder().setCallsign(callsign).build().toByteArray(),
            ConfigGroup.DEVICE to DeviceConfig.newBuilder()
                .setShortName(short).setLongName(long).build().toByteArray(),
        ))

    @Test fun callsignWinsOverDistinctShortAndLongNamesAndIsTrimmed() {
        assertEquals("CALL", identity("  CALL  ", "SHRT", "Configured Long").displayName(status))
    }

    @Test fun blankCallsignFallsBackToShortName() {
        assertEquals("SHRT", identity(" \t", " SHRT ", "Configured Long").displayName(status))
    }

    @Test fun blankShortNameFallsBackToConfiguredLongName() {
        assertEquals("Configured Long", identity("", "  ", " Configured Long ").displayName(status))
    }

    @Test fun deviceStatusLongNameWorksBeforeDeviceConfigRead() {
        assertEquals("Status Long", LocalNodeIdentity().displayName(status))
    }

    @Test fun namelessOwnNodeKeepsUiFallbackRatherThanShowingHex() {
        val unnamed = status.toBuilder().clearNodeName().build()
        assertEquals("AirTAK", identity().displayName(unnamed))
        assertEquals("You", identity().displayName(unnamed, fallback = "You"))
    }

    @Test fun noStatusOrNamesUsesOneSharedAppFallback() {
        assertEquals("AirTAK", identity().displayName(null))
    }

    @Test fun malformedGameConfigDoesNotDiscardValidDeviceIdentity() {
        val parsed = LocalNodeIdentity.from(mapOf(
            ConfigGroup.GAME to byteArrayOf(0x80.toByte()),
            ConfigGroup.DEVICE to DeviceConfig.newBuilder().setShortName("SHRT").build().toByteArray(),
        ))
        assertEquals("SHRT", parsed.displayName(status))
    }

    @Test fun malformedDeviceConfigDoesNotDiscardValidCallsign() {
        val parsed = LocalNodeIdentity.from(mapOf(
            ConfigGroup.GAME to GameConfig.newBuilder().setCallsign("CALL").build().toByteArray(),
            ConfigGroup.DEVICE to byteArrayOf(0x80.toByte()),
        ))
        assertEquals("CALL", parsed.displayName(status))
    }

    @Test fun updatedSnapshotsUseNewNamesAndFallbackAfterClear() {
        assertEquals("FIRST", identity("FIRST", "SHRT", "Long").displayName(status))
        assertEquals("SECOND", identity("SECOND", "SHRT", "Long").displayName(status))
        assertEquals("SHRT", identity("", "SHRT", "Long").displayName(status))
        assertEquals("Long", identity("", "", "Long").displayName(status))
        assertEquals("AirTAK", identity().displayName(status.toBuilder().clearNodeName().build()))
    }

    @Test fun broadcastTrimsTheRealCallsign() {
        assertEquals("CALL", identity(" CALL ", "SHRT", "Long").broadcastCallsign())
    }

    @Test fun broadcastDoesNotPromoteShortOrLongNamesIntoCallsign() {
        assertEquals("", identity("  ", "SHRT", "Long").broadcastCallsign())
    }

    @Test fun namelessBroadcastNeverSendsUiFallbackLabels() {
        assertEquals("", identity().broadcastCallsign())
    }

    @Test fun namedOwnNodeMatchesPeerResolverWhenCallsignIsBlank() {
        val own = identity("", "SHRT", "Long").displayName(status)
        val peer = NodeNames.resolve("", "SHRT", "Long", 0xFFFFFFFFL)
        assertEquals(peer, own)
    }
}
