// AirTAK Android — config/constants/AdcMultiplierDefaultsTest.kt
// Issue #1: per-device ADC battery-voltage multiplier default lookup.

package com.airtak.config.constants

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdcMultiplierDefaultsTest {

    @Test
    fun testKnownBoardIdReturnsTableValue() {
        assertEquals(5.0f, AdcMultiplierDefaults.defaultFor("chatter2"))
        assertEquals(1.85f, AdcMultiplierDefaults.defaultFor("diy"))
        assertEquals(3.1f, AdcMultiplierDefaults.defaultFor("esp32-s3-pico"))
        assertEquals(5.1205f, AdcMultiplierDefaults.defaultFor("heltec_v3"))
        assertEquals(4.916f, AdcMultiplierDefaults.defaultFor("heltec_t114"))
        assertEquals(2.0f, AdcMultiplierDefaults.defaultFor("t-echo"))
        assertEquals(6.45f, AdcMultiplierDefaults.defaultFor("station-g1"))
    }

    @Test
    fun testBoardIdMatchingIsCaseAndDashInsensitive() {
        // underscore/hyphen and uppercase variants must resolve to the same entry
        assertEquals(AdcMultiplierDefaults.defaultFor("t_deck"), AdcMultiplierDefaults.defaultFor("t-deck"))
        assertEquals(2.11f, AdcMultiplierDefaults.defaultFor("T-DECK"))
        assertEquals(2.11f, AdcMultiplierDefaults.defaultFor("t3s3"))
        assertEquals(2.11f, AdcMultiplierDefaults.defaultFor("tlora_t3s3_v1"))
    }

    @Test
    fun testUnknownOrBlankBoardIdReturnsSafeDefault() {
        assertEquals(ADC_MULTIPLIER_DEFAULT, AdcMultiplierDefaults.defaultFor("not-a-real-board"))
        assertEquals(ADC_MULTIPLIER_DEFAULT, AdcMultiplierDefaults.defaultFor(""))
        assertEquals(ADC_MULTIPLIER_DEFAULT, AdcMultiplierDefaults.defaultFor(null))
    }
}
