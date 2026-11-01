package com.airtak.ui

import android.net.Uri
import com.airtak.ui.firmware.FirmwareTransport
import com.airtak.ui.firmware.dispatchFirmwareFile
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FirmwareTransportTest {
    @Test fun usbChoiceNeverStartsBluetoothUpdate() {
        val uri = mockk<Uri>()
        var usbUri: Uri? = null
        dispatchFirmwareFile(uri, FirmwareTransport.Usb, { usbUri = it }, { fail("USB must not require BLE") })
        assertSame(uri, usbUri)
    }

    @Test fun bluetoothChoiceNeverStartsUsbUpdate() {
        val uri = mockk<Uri>()
        var bluetoothUri: Uri? = null
        dispatchFirmwareFile(uri, FirmwareTransport.Bluetooth, { fail("Wrong transport") }, { bluetoothUri = it })
        assertSame(uri, bluetoothUri)
    }

    @Test fun cancellingFileSelectionNeverStartsAnUpdate() {
        FirmwareTransport.entries.forEach { transport ->
            dispatchFirmwareFile(null, transport, { fail("Cancelled picker") }, { fail("Cancelled picker") })
        }
    }

    @Test fun retryWithSameFileUsesTheNewTransport() {
        val uri = mockk<Uri>()
        val calls = mutableListOf<FirmwareTransport>()
        dispatchFirmwareFile(uri, FirmwareTransport.Bluetooth, { calls += FirmwareTransport.Usb }, { calls += FirmwareTransport.Bluetooth })
        dispatchFirmwareFile(uri, FirmwareTransport.Usb, { calls += FirmwareTransport.Usb }, { calls += FirmwareTransport.Bluetooth })
        assertEquals(listOf(FirmwareTransport.Bluetooth, FirmwareTransport.Usb), calls)
    }
}
