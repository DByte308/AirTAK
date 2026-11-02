package com.airtak.ui

import com.airtak.ui.settings.NumericInputError
import com.airtak.ui.settings.SettingsNumericField
import com.airtak.ui.settings.SettingsNumericInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsNumericInputTest {
    private val seconds = SettingsNumericField.entries.filter { it != SettingsNumericField.PlayerColor }

    @Test fun everySecondsFieldCanBeClearedWithoutReverting() {
        seconds.forEach {
            val input = SettingsNumericInput(it, "")
            assertEquals("", input.text)
            assertEquals(NumericInputError.Empty, input.error)
            assertNull(input.value)
        }
    }

    @Test fun leadingZerosAndIntermediateTypingArePreserved() {
        seconds.forEach { field ->
            listOf("0", "00", "007", "0075").forEach { text ->
                assertEquals(text, SettingsNumericInput(field, text).text)
            }
            assertEquals(75, SettingsNumericInput(field, "0075").value)
        }
    }

    @Test fun unsignedWholeNumberLimitsAreCheckedWithoutOverflow() {
        seconds.forEach {
            assertEquals(0, SettingsNumericInput(it, "0").value)
            assertEquals(Int.MAX_VALUE, SettingsNumericInput(it, Int.MAX_VALUE.toString()).value)
            listOf("2147483648", "9".repeat(80)).forEach { text ->
                assertEquals(NumericInputError.OutOfRange, SettingsNumericInput(it, text).error)
            }
        }
    }

    @Test fun secondsRejectNegativesFractionsAndSpecialNumbers() {
        seconds.forEach { field ->
            listOf("-1", "1.5", "1,5", "NaN", "Infinity", "1e2", "1 2").forEach {
                assertEquals(NumericInputError.NotWholeNumber, SettingsNumericInput(field, it).error, it)
            }
        }
    }

    @Test fun partialHexColorDoesNotGetPaddedWhileTyping() {
        listOf("F", "FF", "FF4", "FF4D", "FF4D5").forEach {
            val input = SettingsNumericInput(SettingsNumericField.PlayerColor, it)
            assertEquals(it, input.text)
            assertFalse(input.isValid)
        }
    }

    @Test fun lowercaseAndOptionalHashAreAcceptedWithoutChangingTheText() {
        listOf("00ab0f", "#00AB0F").forEach {
            val input = SettingsNumericInput(SettingsNumericField.PlayerColor, it)
            assertEquals(it, input.text)
            assertEquals(0x00AB0F, input.value)
        }
    }

    @Test fun invalidOrTooLongHexIsRejectedNotSilentlyTruncated() {
        listOf("FF4D5EG", "1234567", "GGGGGG", "FF FF FF").forEach {
            val input = SettingsNumericInput(SettingsNumericField.PlayerColor, it)
            assertEquals(it, input.text)
            assertEquals(NumericInputError.InvalidColor, input.error)
        }
    }

    @Test fun blackAndWhiteAreValidColors() {
        assertEquals(0, SettingsNumericInput(SettingsNumericField.PlayerColor, "000000").value)
        assertEquals(0xFFFFFF, SettingsNumericInput(SettingsNumericField.PlayerColor, "FFFFFF").value)
    }

    @Test fun incomingValuesAreFormattedOnlyWhenRead() {
        assertEquals("000012", SettingsNumericInput.fromValue(SettingsNumericField.PlayerColor, 0x12).text)
        assertEquals("75", SettingsNumericInput.fromValue(SettingsNumericField.BroadcastInterval, 75).text)
    }

    @Test fun invalidIncomingValuesAreNotSilentlyClamped() {
        assertFalse(SettingsNumericInput.fromValue(SettingsNumericField.DisplayTimeout, -1).isValid)
        assertFalse(SettingsNumericInput.fromValue(SettingsNumericField.PlayerColor, -1).isValid)
    }
}
