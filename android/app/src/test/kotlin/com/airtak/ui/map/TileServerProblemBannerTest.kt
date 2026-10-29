package com.airtak.ui.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sanitizer that stands between an exception message and a string on screen.
 *
 * The reason reaching the banner is whatever a failed bind threw, so it can name a bind address or
 * a provider host. A screenshot of this banner is something a user might send to support, and the
 * full reason is already in the diagnostic log, so the UI copy must not be the leak.
 */
class TileServerProblemBannerTest {

    @Test
    fun noProblemMeansNoDetail() {
        assertNull(tileServerProblemDetail(null))
    }

    @Test
    fun anOrdinaryReasonSurvivesUnchanged() {
        assertEquals(
            "bind failed: permission denied",
            tileServerProblemDetail("bind failed: permission denied"),
        )
    }

    @Test
    fun aProviderUrlIsRedactedRatherThanDisplayed() {
        val out = tileServerProblemDetail(
            "refused https://tiles.example-cdn.com/v1/{z}/{x}/{y}.png?key=SECRET123",
        ) ?: error("expected some detail to remain")

        assertFalse(out.contains("example-cdn.com"), "host leaked to UI: $out")
        assertFalse(out.contains("SECRET123"), "query secret leaked to UI: $out")
        assertFalse(out.contains("://"), "scheme leaked to UI: $out")
        assertTrue(out.contains("[address]"), "expected a redaction marker, got $out")
    }

    @Test
    fun aBindAddressWithPortIsRedacted() {
        val out = tileServerProblemDetail("could not bind 127.0.0.1:43210") ?: error("expected detail")

        assertFalse(out.contains("43210"), "port leaked to UI: $out")
        assertFalse(out.contains("127.0.0.1"), "address leaked to UI: $out")
    }

    @Test
    fun newlinesAndControlCharactersCannotRideIntoTheLabel() {
        val out = tileServerProblemDetail("bind failed\r\nINJECT\tsecond\u0000line")
            ?: error("expected detail")

        assertFalse(out.contains('\r'), "CR survived: $out")
        assertFalse(out.contains('\n'), "LF survived: $out")
        assertFalse(out.contains('\t'), "TAB survived: $out")
        assertFalse(out.contains('﻿'), "NUL survived: $out")
        assertEquals("bind failed INJECT second line", out)
    }

    @Test
    fun aVeryLongReasonIsBoundedSoTheBannerStaysBounded() {
        val out = tileServerProblemDetail("x".repeat(5000)) ?: error("expected detail")

        assertTrue(out.length <= 120, "detail was ${out.length} chars, expected <= 120")
        assertTrue(out.endsWith("…"), "expected a truncation marker, got …")
    }

    @Test
    fun aReasonThatSanitisesToNothingShowsNothing() {
        // A detail line that is only a redaction marker says nothing a user can act on, and it
        // makes the banner look broken. The headline alone is the honest rendering.
        assertNull(tileServerProblemDetail("https://tiles.example.com/"))
        assertNull(tileServerProblemDetail("   "))
        assertNull(tileServerProblemDetail("\r\n"))
    }

    @Test
    fun aStackFrameStyleReasonDoesNotBecomeAMultiLineDump() {
        val out = tileServerProblemDetail(
            "java.net.BindException: Address already in use\n" +
                "\tat java.net.PlainSocketImpl.socketBind(Native Method)",
        ) ?: error("expected detail")

        assertFalse(out.contains("\n"), "stack trace reached the UI as multiple lines: $out")
        assertFalse(out.contains("PlainSocketImpl"), "stack frame leaked: $out")
        assertTrue(out.length <= 120, "expected a bounded single line, got ${out.length}")
    }
}
