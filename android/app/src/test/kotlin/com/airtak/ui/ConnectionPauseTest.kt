package com.airtak.ui

import android.content.Context
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.prefs.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionPauseTest {
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.BLUETOOTH_SERVICE) } returns null
    }
    private var storedPause = false
    private val prefs = mockk<AppPreferences>(relaxed = true) {
        every { connectionPaused() } answers { storedPause }
        every { setConnectionPaused(any()) } answers { storedPause = firstArg() }
        every { bondedAddress() } returns "AA:BB:CC:DD:EE:FF"
    }

    @Test fun defaultIsEnabled() {
        assertFalse(BleRepository(context, prefs).connectionPaused.value)
    }

    @Test fun pausePersistsAcrossRepositoryRecreationWithoutForgettingBond() {
        val ble = BleRepository(context, prefs)
        assertTrue(ble.setConnectionPaused(true))
        assertTrue(ble.connectionPaused.value)
        assertTrue(BleRepository(context, prefs).connectionPaused.value)
        verify(exactly = 0) { prefs.setBondedAddress(any()) }
    }

    @Test fun pauseBlocksManualAndAutomaticConnectionAndScanning() {
        storedPause = true
        val ble = BleRepository(context, prefs)
        ble.connect("11:22:33:44:55:66")
        ble.reconnectBonded()
        ble.startScan()
        assertEquals(LinkState.Disconnected, ble.linkState.value)
        verify(exactly = 0) { prefs.setBondedAddress(any()) }
    }

    @Test fun resumeClearsPersistentPause() {
        storedPause = true
        val ble = BleRepository(context, prefs)
        assertTrue(ble.setConnectionPaused(false))
        assertFalse(ble.connectionPaused.value)
        assertFalse(BleRepository(context, prefs).connectionPaused.value)
    }

    @Test fun userPauseIsNotAFirmwareUpdateLock() {
        val ble = BleRepository(context, prefs)
        ble.setConnectionPaused(true)
        assertFalse(ble.firmwareUpdateInFlight)
    }

    @Test fun firmwareUpdateRejectsUserPauseAndResume() {
        val ble = BleRepository(context, prefs)
        ble.pauseAutoReconnect(true)
        assertFalse(ble.setConnectionPaused(true))
        assertFalse(ble.setConnectionPaused(false))
        assertFalse(storedPause)
        verify(exactly = 0) { prefs.setConnectionPaused(any()) }
    }

    @Test fun firmwareUnlockDoesNotClearUserPause() {
        storedPause = true
        val ble = BleRepository(context, prefs)
        ble.pauseAutoReconnect(true)
        ble.pauseAutoReconnect(false)
        ble.reconnectBonded()
        assertTrue(ble.connectionPaused.value)
        assertEquals(LinkState.Disconnected, ble.linkState.value)
    }

    @Test fun lateLinkLossCannotScheduleReconnectWhilePaused() {
        storedPause = true
        val ble = BleRepository(context, prefs)
        val callback = BleRepository::class.java.getDeclaredMethod(
            "onLinkLost", String::class.java, Int::class.javaPrimitiveType,
        )
        callback.isAccessible = true
        callback.invoke(ble, "AA:BB:CC:DD:EE:FF", 133)
        assertEquals(LinkState.Disconnected, ble.linkState.value)
    }
}
