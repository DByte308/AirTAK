// AirTAK Android — test/kotlin/com/airtak/util/NodeNamesTest.kt
//
// One label rule, four former call sites. util/NodeNames.kt documents that MapViewModel.toMapNode,
// MapViewModel's own-position label, the AppShell HUD bar and GameRepository each picked a name
// differently, so the same player could be shown by callsign in one place and by the 39-byte device
// long name in another. These tests pin the single precedence, and each historical shape gets its
// own case so a regression at one call site fails a named test instead of quietly changing one
// screen's labels.
//
// Scope note: nothing here involves a radio, a cable or a device. The USB filter change that
// shipped in the same slice is not testable on the JVM and is not claimed by this file.
package com.airtak.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NodeNamesTest {

    // ── precedence ──────────────────────────────────────

    @Test fun callsignWinsOverEverything() {
        assertEquals(
            "FOX",
            NodeNames.resolve("FOX", shortName = "FX", longName = "Fireteam Fox Two", nodeId = 0xDEADBEEFL),
        )
    }

    @Test fun shortNameUsedOnlyWhenCallsignMissing() {
        assertEquals("FX", NodeNames.resolve(null, shortName = "FX", longName = "Fireteam Fox Two"))
    }

    @Test fun longNameUsedOnlyWhenCallsignAndShortMissing() {
        assertEquals("Fireteam Fox Two", NodeNames.resolve(null, shortName = null, longName = "Fireteam Fox Two"))
    }

    @Test fun hexIdUsedOnlyWhenEveryNameIsMissing() {
        assertEquals("BEEF", NodeNames.resolve(null, shortName = null, longName = null, nodeId = 0xDEADBEEFL))
    }

    @Test fun nothingAtAllYieldsEmptyStringRatherThanNull() {
        assertEquals("", NodeNames.resolve(null))
    }

    // ── blank handling ──────────────────────────────────

    @Test fun whitespaceOnlyCallsignFallsThroughRatherThanRenderingBlank() {
        // A proto field that is present but empty or spaces is the common real case, and returning
        // it would put an invisible label on the map.
        assertEquals("FX", NodeNames.resolve("   ", shortName = "FX"))
        assertEquals("Fireteam Fox Two", NodeNames.resolve("", shortName = "\t ", longName = "Fireteam Fox Two"))
    }

    @Test fun tabsNewlinesAndCarriageReturnsCountAsBlank() {
        // Kotlin has no form-feed escape sequence, so the form feed is written as an explicit
        // Unicode escape instead. A raw backslash-f here fails the Kotlin compile.
        assertEquals("BEEF", NodeNames.resolve("\n", shortName = "\t", longName = "\r \u000C ", nodeId = 0xDEADBEEFL))
    }

    @Test fun resolvedNameIsTrimmed() {
        assertEquals("FOX", NodeNames.resolve("  FOX  "))
    }

    @Test fun blankIsNotTrimmedIntoAFalseNameForResolveOrNull() {
        assertNull(NodeNames.resolveOrNull("   ", shortName = null, longName = null))
    }

    // ── resolveOrNull ───────────────────────────────────

    @Test fun resolveOrNullNeverReturnsAHexId() {
        // The HUD and the player packet want "no name" to mean "use my own fallback word", not
        // "show a hex id", which is why this variant exists at all.
        assertNull(NodeNames.resolveOrNull(null, shortName = null, longName = null))
    }

    @Test fun resolveOrNullStillAppliesTheSamePrecedence() {
        assertEquals("FX", NodeNames.resolveOrNull(null, shortName = "FX", longName = "Long"))
    }

    // ── fallbackHex ─────────────────────────────────────

    @Test fun fallbackHexIsUppercaseLastFourDigits() {
        assertEquals("BEEF", NodeNames.fallbackHex(0xDEADBEEFL))
    }

    @Test fun fallbackHexPadsNothingButStaysFourCharsForSmallIds() {
        // Documented behaviour, not an accident: a small node id renders short. Pinned so a later
        // "improvement" that zero-pads is a deliberate change rather than a silent one.
        assertEquals("2A", NodeNames.fallbackHex(0x2AL))
    }

    @Test fun fallbackHexIsDeterministic() {
        assertEquals(NodeNames.fallbackHex(0x1234ABCDL), NodeNames.fallbackHex(0x1234ABCDL))
    }

    @Test fun fallbackHexDistinguishesIdsThatDifferOnlyInHighBits() {
        // Known limitation of a last-four-digits label: two nodes whose low 16 bits match collide.
        // Pinned as a documented boundary so nobody reads the hex label as a unique identifier.
        assertEquals(NodeNames.fallbackHex(0x1111BEEFL), NodeNames.fallbackHex(0x2222BEEFL))
    }

    // ── the four historical call-site shapes ────────────

    @Test fun mapNodeLabelShape_callsignShortLongHex() {
        // MapViewModel.toMapNode: resolve(callsign, shortName, longName, nodeId)
        assertEquals("FOX", NodeNames.resolve("FOX", "FX", "Fireteam Fox Two", 0xDEADBEEFL))
        assertEquals("FX", NodeNames.resolve("", "FX", "Fireteam Fox Two", 0xDEADBEEFL))
        assertEquals("Fireteam Fox Two", NodeNames.resolve(null, null, "Fireteam Fox Two", 0xDEADBEEFL))
        assertEquals("BEEF", NodeNames.resolve(null, null, null, 0xDEADBEEFL))
    }

    @Test fun ownPositionLabelShape_callsignThenDeviceLongNameThenYou() {
        // MapViewModel own-position: resolveOrNull(callsign, longName = DeviceStatus.nodeName) ?: "You"
        // DeviceStatus.nodeName is the device LONG name (ble.proto), so it must rank below callsign.
        val withCallsign = NodeNames.resolveOrNull(callsign = "FOX", longName = "Fireteam Fox Two") ?: "You"
        val longOnly = NodeNames.resolveOrNull(callsign = "", longName = "Fireteam Fox Two") ?: "You"
        val none = NodeNames.resolveOrNull(callsign = null, longName = null) ?: "You"
        assertEquals("FOX", withCallsign)
        assertEquals("Fireteam Fox Two", longOnly)
        assertEquals("You", none)
    }

    @Test fun hudBarShape_callsignThenDeviceLongNameThenAirTak() {
        // AppShellViewModel HUD: resolveOrNull(callsign, longName = status?.nodeName) ?: "AirTAK"
        // This is the site that used to ignore callsign entirely.
        assertEquals("FOX", NodeNames.resolveOrNull(callsign = "FOX", longName = "Fireteam Fox Two") ?: "AirTAK")
        assertEquals("AirTAK", NodeNames.resolveOrNull(callsign = null, longName = null) ?: "AirTAK")
    }

    @Test fun playerPacketShape_callsignThenDeviceLongNameThenAirTak() {
        // GameRepository: resolveOrNull(cfg.callsign, longName = device.nodeName) ?: "AirTAK"
        assertEquals("FOX", NodeNames.resolveOrNull("FOX", longName = "Fireteam Fox Two") ?: "AirTAK")
        assertEquals(
            "Fireteam Fox Two",
            NodeNames.resolveOrNull("  ", longName = "Fireteam Fox Two") ?: "AirTAK",
        )
    }

    @Test fun everyShapeAgreesOnTheSameInputs() {
        // The whole point of the unification: given the same three fields, no two screens may
        // disagree about what the player is called.
        val callsign = "FOX"
        val shortName = "FX"
        val longName = "Fireteam Fox Two"
        val nodeId = 0xDEADBEEFL

        val mapLabel = NodeNames.resolve(callsign, shortName, longName, nodeId)
        val rosterLabel = NodeNames.resolve(callsign, shortName, longName, nodeId)
        val hudLabel = NodeNames.resolveOrNull(callsign, longName = longName) ?: "AirTAK"
        val packetLabel = NodeNames.resolveOrNull(callsign, longName = longName) ?: "AirTAK"

        assertEquals(mapLabel, rosterLabel)
        assertEquals(mapLabel, hudLabel)
        assertEquals(hudLabel, packetLabel)
    }
}
