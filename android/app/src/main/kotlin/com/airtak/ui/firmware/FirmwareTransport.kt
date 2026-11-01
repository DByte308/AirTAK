package com.airtak.ui.firmware

import android.net.Uri

internal enum class FirmwareTransport { Usb, Bluetooth }

/** The file picker must use the chosen transport, never infer BLE from a file URI. */
internal fun dispatchFirmwareFile(
    uri: Uri?,
    transport: FirmwareTransport,
    startUsb: (Uri) -> Unit,
    startBluetooth: (Uri) -> Unit,
) {
    if (uri == null) return
    when (transport) {
        FirmwareTransport.Usb -> startUsb(uri)
        FirmwareTransport.Bluetooth -> startBluetooth(uri)
    }
}
