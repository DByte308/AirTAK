// MASH Android — data/ble/BleConnectionService.kt
// Planning: §11.3 | Roadmap: 3.1
//
// ForegroundService (FOREGROUND_SERVICE_CONNECTED_DEVICE) that maintains the BLE
// connection independently of the UI lifecycle. Shows a persistent notification
// with connection state. Started by MainActivity; survives screen rotations.

package com.mash.data.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID        = "mash_ble_connection"
private const val NOTIFICATION_ID   = 1001
const val ACTION_START              = "com.mash.ble.START"
const val ACTION_STOP               = "com.mash.ble.STOP"

@AndroidEntryPoint
class BleConnectionService : Service() {

    @Inject lateinit var bleRepository: BleRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(ConnectionState.SCANNING))
        serviceScope.launch {
            bleRepository.connectionStateFlow().collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> bleRepository.connect()
            ACTION_STOP  -> {
                bleRepository.disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bleRepository.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MASH Device Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Maintains connection to MASH hardware" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val stateText = when (state) {
            ConnectionState.SCANNING     -> "Scanning…"
            ConnectionState.CONNECTING   -> "Connecting…"
            ConnectionState.CONNECTED    -> "Connected"
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BleConnectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MASH")
            .setContentText(stateText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
    }
}

