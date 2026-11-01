package com.airtak.ui

import android.content.Context
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.prefs.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppExitConnectionTest {
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.BLUETOOTH_SERVICE) } returns null
    }
    private val prefs = mockk<AppPreferences>(relaxed = true) {
        every { bondedAddress() } returns "AA:BB:CC:DD:EE:FF"
    }

    private fun deliverLinkLoss(ble: BleRepository) {
        val callback = BleRepository::class.java.getDeclaredMethod(
            "onLinkLost", String::class.java, Int::class.javaPrimitiveType,
        )
        callback.isAccessible = true
        callback.invoke(ble, "AA:BB:CC:DD:EE:FF", 133)
    }

    @Test fun exitDisconnectsWithoutForgettingBond() {
        val ble = BleRepository(context, prefs)
        ble.stopForAppExit()
        assertEquals(LinkState.Disconnected, ble.linkState.value)
        verify(exactly = 0) { prefs.setBondedAddress(any()) }
    }

    @Test fun lateLinkLossCannotRestartConnectionAfterExit() {
        val ble = BleRepository(context, prefs)
        ble.stopForAppExit()
        deliverLinkLoss(ble)
        assertEquals(LinkState.Disconnected, ble.linkState.value)
    }

    @Test fun explicitReconnectClearsExitLatch() {
        val ble = BleRepository(context, prefs)
        ble.stopForAppExit()
        ble.reconnectBonded()
        // There is no Bluetooth adapter in this test, but a fresh connection was attempted.
        assertEquals(LinkState.Failed("Bluetooth unavailable"), ble.linkState.value)
    }

    @Test fun exitIsIdempotent() {
        val ble = BleRepository(context, prefs)
        ble.stopForAppExit()
        ble.stopForAppExit()
        deliverLinkLoss(ble)
        assertEquals(LinkState.Disconnected, ble.linkState.value)
    }
}
