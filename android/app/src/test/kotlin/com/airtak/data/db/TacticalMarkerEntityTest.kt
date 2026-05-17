// AirTAK Android — test/kotlin/com/airtak/data/db/TacticalMarkerEntityTest.kt
package com.airtak.data.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TacticalMarkerEntityTest {
    private lateinit var originalLocale: Locale

    @BeforeAll fun saveLocale() { originalLocale = Locale.getDefault() }
    @AfterAll  fun restoreLocale() { Locale.setDefault(originalLocale) }

    @Test fun roundtripsSinglePoint() {
        val pts = listOf(40.123456 to -74.654321)
        val s = TacticalMarkerEntity.encodePoints(pts)
        val out = TacticalMarkerEntity.decodePoints(s)
        assertEquals(1, out.size)
        assertEquals(40.123456, out[0].first, 1e-6)
        assertEquals(-74.654321, out[0].second, 1e-6)
    }

    @Test fun roundtripsPolylineUnder1mPrecision() {
        val pts = (0 until 8).map { i -> (40.000000 + i * 0.000010) to (-74.000000 - i * 0.000010) }
        val out = TacticalMarkerEntity.decodePoints(TacticalMarkerEntity.encodePoints(pts))
        assertEquals(pts.size, out.size)
        pts.zip(out).forEach { (a, b) ->
            assertTrue(kotlin.math.abs(a.first - b.first) < 1e-6)
            assertTrue(kotlin.math.abs(a.second - b.second) < 1e-6)
        }
    }

    @Test fun decodesEmpty() {
        assertEquals(emptyList<Pair<Double, Double>>(), TacticalMarkerEntity.decodePoints(""))
    }

    @Test fun skipsMalformedSegments() {
        val out = TacticalMarkerEntity.decodePoints("40.0,-74.0;garbage;41.0,-75.0")
        assertEquals(2, out.size)
        assertEquals(40.0, out[0].first, 1e-6)
        assertEquals(41.0, out[1].first, 1e-6)
    }

    @Test fun encodesWithDotDecimalUnderCommaDecimalLocale() {
        // Reproduces the bug where %.6f under a comma-decimal locale (e.g. de_DE)
        // produced "40,123456,-74,654321" and decodePoints discarded all points.
        Locale.setDefault(Locale.GERMANY)
        val encoded = TacticalMarkerEntity.encodePoints(listOf(40.123456 to -74.654321))
        assertTrue(encoded.contains('.'), "encoded must use dot decimal, was: $encoded")
        assertEquals(1, TacticalMarkerEntity.decodePoints(encoded).size)
    }
}
