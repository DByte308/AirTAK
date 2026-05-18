// AirTAK Android — data/gps/PhoneGpsProvider.kt
//
// Provides phone GPS position to the firmware when PositionConfig.gps_source
// is set to PHONE_GPS. Sends position updates via the ADMIN BLE characteristic
// (SET_PHONE_GPS command). Used by devices without built-in GPS or by users
// who prefer phone GPS over the device's L76K module.
//
// Lifecycle:
//   - When gps_source transitions to PHONE_GPS, Android LocationManager
//     location updates begin.
//   - Each update is sent to the firmware as AdminRequest{SET_PHONE_GPS}.
//   - When gps_source transitions to DEVICE_GPS (or the BLE link drops),
//     location updates stop.
//
// Throttling: position is sent to firmware at most once every 10 seconds
// and only when displacement exceeds 5 metres, matching the smart beaconing
// heartbeat (which only needs position at TX time, ≥ 15 s).

package com.airtak.data.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.airtak.data.ble.BleRepository
import com.airtak.data.config.ConfigRepository
import com.airtak.proto.AdminCommand
import com.airtak.proto.AdminRequest
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GpsSource
import com.airtak.proto.PhoneGpsPosition
import com.airtak.proto.PositionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneGpsProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: ConfigRepository,
    private val ble: BleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var active = false

    init {
        scope.launch {
            config.groupBytes.collect { map ->
                val bytes = map[ConfigGroup.POSITION]
                val cfg = bytes?.let { runCatching { PositionConfig.parseFrom(it) }.getOrNull() }
                val wantPhoneGps = cfg?.gpsSource == GpsSource.PHONE_GPS
                if (wantPhoneGps != active) {
                    active = wantPhoneGps
                    if (wantPhoneGps) start() else stop()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) { }
    }

    private fun stop() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(locationListener)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val req = AdminRequest.newBuilder()
                .setCommand(AdminCommand.SET_PHONE_GPS)
                .setPhoneGps(
                    PhoneGpsPosition.newBuilder()
                        .setLatitude(loc.latitude)
                        .setLongitude(loc.longitude)
                        .setAltitudeM(loc.altitude.toFloat())
                        .setAccuracyM(loc.accuracy)
                        .build(),
                )
                .build()
            ble.send(BleRepository.OutboundChannel.Admin, req.toByteArray())
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    companion object {
        private const val MIN_TIME_MS = 10_000L  // 10 s minimum interval
        private const val MIN_DISTANCE_M = 5.0f   // 5 m minimum displacement
    }
}
