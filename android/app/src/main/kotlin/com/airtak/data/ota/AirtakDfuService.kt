// AirTAK Android — data/ota/AirtakDfuService.kt
// Planning: §3.2, §6.1 | Roadmap: 6.1
//
// Concrete DfuBaseService subclass required by the Nordic DFU library. The
// library starts this service from NrfBleDfuEngine.transfer(); it handles the
// bootloader handshake, init-packet upload, chunked firmware transfer with
// Packet Receipt Notifications, CRC validation, and the post-DFU reconnect.
// All protocol logic lives in the library — we only need to point it at the
// activity to resume after completion and let it run as a foreground service.

package com.airtak.data.ota

import android.app.Activity
import com.airtak.ui.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

class AirtakDfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java

    /** Disable the library's internal debug logging via `nRF Logger` — we
     *  consume progress through DfuProgressListenerAdapter in the engine. */
    override fun isDebug(): Boolean = false
}
