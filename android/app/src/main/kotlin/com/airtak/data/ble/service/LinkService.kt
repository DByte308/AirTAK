// AirTAK Android — data/ble/service/LinkService.kt
// Planning: §11.10 (BLE lifecycle), §11.2 (FOREGROUND_SERVICE_CONNECTED_DEVICE)
// Roadmap: 3.7
//
// Foreground service that keeps the BLE link alive while the app is
// backgrounded. The persistent notification reads "AirTAK — Linked to <name>"
// with a Disconnect action. The service does not own the BLE state machine —
// it is a host process that prevents the app from being killed while a bonded
// device exists. Reconnect logic lives in BleRepository.

package com.airtak.data.ble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airtak.R
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.airtak.config.constants.ACTION_LINK_DISCONNECT
import com.airtak.config.constants.ACTION_LINK_START
import com.airtak.config.constants.LINK_CHANNEL_ID
import com.airtak.config.constants.LINK_NOTIFICATION_ID
import javax.inject.Inject

@AndroidEntryPoint
class LinkService : Service() {

    @Inject lateinit var bleRepository: BleRepository
    @Inject lateinit var statusRepository: DeviceStatusRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat(buildNotification(getString(R.string.service_title_searching)))
        observerJob = scope.launch {
            combine(bleRepository.linkState, statusRepository.status) { state, status ->
                renderTitle(state, status?.nodeName)
            }.collect { title -> updateNotification(title) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LINK_DISCONNECT -> {
                bleRepository.disconnect()
                stopSelf()
            }
            ACTION_LINK_START -> bleRepository.reconnectBonded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderTitle(state: LinkState, nodeName: String?): String = when (state) {
        is LinkState.Connected     -> getString(R.string.service_title, nodeName.ifDisplayName(state.address))
        is LinkState.Connecting    -> getString(R.string.service_title, nodeName.ifDisplayName(state.address))
        is LinkState.Reconnecting  -> getString(R.string.service_title, nodeName.ifDisplayName(state.address))
        else                       -> getString(R.string.service_title_searching)
    }

    private fun String?.ifDisplayName(fallback: String): String = this?.takeIf { it.isNotBlank() } ?: fallback

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(LINK_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                LINK_CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                LINK_NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(LINK_NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(LINK_NOTIFICATION_ID, buildNotification(title))
    }

    private fun buildNotification(title: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).apply { action = ACTION_LINK_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LINK_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.service_channel_desc))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    getString(R.string.service_action_disconnect),
                    disconnect,
                ).build(),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java).setAction(ACTION_LINK_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
