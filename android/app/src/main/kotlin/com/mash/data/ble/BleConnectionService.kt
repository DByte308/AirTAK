// MASH Android — data/ble/BleConnectionService.kt
// Planning: §11.3 | Roadmap: 3.1
//
// FOREGROUND_SERVICE with foregroundServiceType="connectedDevice" (§11.2).
// Maintains BLE connection independently of UI lifecycle.
// Shows persistent notification with device name and battery level.

package com.mash.data.ble

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BleConnectionService : Service() {

    @Inject lateinit var bleRepository: BleRepository

    override fun onCreate() {
        super.onCreate()
        // TODO Phase 3.1: Start foreground notification (FOREGROUND_SERVICE_CONNECTED_DEVICE)
        // TODO Phase 3.1: Launch BLE connection coroutine in lifecycleScope
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO Phase 3.1: Handle START / STOP / RETRY actions from notification
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO Phase 3.1: Cancel BLE connection coroutine cleanly
    }
}
