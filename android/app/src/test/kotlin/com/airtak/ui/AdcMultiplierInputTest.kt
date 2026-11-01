package com.airtak.ui

import com.airtak.ui.settings.AdcInputError
import com.airtak.ui.settings.AdcMultiplierInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdcMultiplierInputTest {
    @Test fun clearingTheFieldKeepsItEmpty() {
        val input = AdcMultiplierInput("")
        assertEquals("", input.text)
        assertEquals(AdcInputError.Empty, input.error)
        assertNull(input.value)
    }

    @Test fun trailingCommaIsNotReformatted() {
        val input = AdcMultiplierInput("2,")
        assertEquals("2,", input.text)
        assertEquals(2f, input.value)
    }

    @Test fun trailingDotIsNotReformatted() {
        val input = AdcMultiplierInput("2.")
        assertEquals("2.", input.text)
        assertEquals(2f, input.value)
    }

    @Test fun fractionalZerosArePreservedDuringTyping() {
        listOf("2", "2,", "2,0", "2,00", "2,005").forEach {
            assertEquals(it, AdcMultiplierInput(it).text)
        }
        assertEquals(2.005f, AdcMultiplierInput("2,005").value)
    }

    @Test fun dotAndCommaParseToTheSameValue() {
        assertEquals(AdcMultiplierInput("2.56").value, AdcMultiplierInput("2,56").value)
    }

    @Test fun leadingSeparatorCanBeCompleted() {
        assertFalse(AdcMultiplierInput(",").isValid)
        assertEquals(0.5f, AdcMultiplierInput(",5").value)
        assertEquals(0.5f, AdcMultiplierInput(".5").value)
    }

    @Test fun boundariesIncludeZeroForBoardDefault() {
        assertEquals(0f, AdcMultiplierInput("0").value)
        assertEquals(100f, AdcMultiplierInput("100").value)
    }

    @Test fun outOfRangeValuesAreRejectedNotClamped() {
        listOf("-1", "100.01", "100.0000001", "1000").forEach {
            assertEquals(AdcInputError.OutOfRange, AdcMultiplierInput(it).error, it)
            assertNull(AdcMultiplierInput(it).value, it)
        }
    }

    @Test fun invalidFormatsAndSpecialFloatsAreRejected() {
        listOf("NaN", "Infinity", "1e2", "2,5.6", "2..5", "text", "1 2", ".").forEach {
            assertEquals(AdcInputError.InvalidNumber, AdcMultiplierInput(it).error, it)
        }
    }

    @Test fun valuesReadFromDeviceUsePlainDecimalWithoutScientificNotation() {
        listOf(0f, 2.56f, 0.00001f, Float.MIN_VALUE, 100f).forEach { value ->
            val input = AdcMultiplierInput.fromValue(value)
            assertTrue(input.isValid, input.text)
            assertFalse(input.text.contains("E"), input.text)
            assertEquals(value, input.value)
        }
    }

    @Test fun corruptStoredValuesFallBackToBoardDefault() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 101f).forEach {
            assertEquals(0f, AdcMultiplierInput.fromValue(it).value)
        }
    }

    @Test fun underflowMustNotAccidentallySelectBoardDefault() {
        assertEquals(AdcInputError.InvalidNumber, AdcMultiplierInput("0." + "0".repeat(60) + "1").error)
    }
}
