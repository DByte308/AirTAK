package com.airtak.ui

import com.airtak.data.prefs.ExitMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExitModeTest {
    @Test fun missingPreferenceKeepsDoubleBack() {
        assertEquals(ExitMode.DoubleBack, ExitMode.fromStored(null))
    }

    @Test fun unknownPreferenceKeepsDoubleBack() {
        assertEquals(ExitMode.DoubleBack, ExitMode.fromStored("unknown"))
    }

    @Test fun allModesRoundTrip() {
        ExitMode.entries.forEach { assertEquals(it, ExitMode.fromStored(it.name)) }
    }

    @Test fun doubleBackModeKeepsLinkWhenTaskRemoved() {
        assertFalse(ExitMode.DoubleBack.closesOnTaskRemoval(false))
        assertFalse(ExitMode.DoubleBack.closesOnTaskRemoval(true))
    }

    @Test fun recentsModeClosesWhenTaskRemoved() {
        assertTrue(ExitMode.Recents.closesOnTaskRemoval(false))
    }

    @Test fun onlyDoubleBackModeClosesOnBack() {
        assertTrue(ExitMode.DoubleBack.closesOnBack)
        assertFalse(ExitMode.Recents.closesOnBack)
        assertFalse(ExitMode.ButtonOnly.closesOnBack)
    }

    @Test fun buttonOnlyKeepsLinkWhenTaskRemoved() {
        assertFalse(ExitMode.ButtonOnly.closesOnTaskRemoval(false))
        assertFalse(ExitMode.ButtonOnly.closesOnTaskRemoval(true))
    }

    @Test fun buttonOnlyStoredValueIsRecognized() {
        assertEquals(ExitMode.ButtonOnly, ExitMode.fromStored("ButtonOnly"))
    }

    @Test fun recentsModeProtectsFirmwareUpdate() {
        assertFalse(ExitMode.Recents.closesOnTaskRemoval(true))
    }
}
