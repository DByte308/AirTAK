// AirTAK Android — data/gps/PhoneGpsProvider.kt
//
// Provides phone GPS position to the firmware when PositionConfig.gps_source
// is set to PHONE_GPS. Sends position updates via the ADMIN BLE characteristic
// (SET_PHONE_GPS command). Used by devices without built-in GPS or by users
// who prefer phone GPS over the device's L76K module.
//
// Lifecycle:
//   - When gps_source transitions to PHONE_GPS (device connected), Android
//     LocationManager location updates begin and are sent to firmware.
//   - When gps_source transitions to DEVICE_GPS (device connected), updates stop.
//   - When NO device is connected, phone GPS starts automatically and exposes
//     location via the `location` StateFlow for direct map consumption. This
//     covers the standalone-app case (no LoRa device). No BLE send occurs.
//
// Providers: registers both GPS_PROVIDER (satellite) and NETWORK_PROVIDER
// (Wi-Fi/cell tower) for faster initial fix and indoor coverage. Only the
// best-accuracy reading is emitted.
//
// Throttling: position is emitted at most once every 5 seconds per provider,
// or when displacement exceeds 5 metres.

package com.airtak.data.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PhoneLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val accuracyM: Float,
)

@Singleton
class PhoneGpsProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: ConfigRepository,
    private val ble: BleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var active = false
    private var sendToBle = false

    private val _location = MutableStateFlow<PhoneLocation?>(null)
    val location: StateFlow<PhoneLocation?> = _location.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _hasFix = MutableStateFlow(false)
    val hasFix: StateFlow<Boolean> = _hasFix.asStateFlow()

    init {
        // Synchronous initial check: start GPS immediately if no device is
        // connected, without waiting for the coroutine to process. This
        // guarantees that Android's GPS status-bar indicator appears and
        // LocationManager begins requesting fixes before any UI renders.
        val linkState = ble.linkState.value
        if (linkState !is LinkState.Connected) {
            applyState(true, targetBle = false)
        }

        // React to subsequent state changes (connect, disconnect, config push).
        scope.launch {
            combine(ble.linkState, config.groupBytes) { linkState, map ->
                linkState to map
            }.collect { (linkState, map) ->
                val isConnected = linkState is LinkState.Connected
                val bytes = map[ConfigGroup.POSITION]
                val cfg = bytes?.let { runCatching { PositionConfig.parseFrom(it) }.getOrNull() }
                val deviceWantsPhoneGps = cfg?.gpsSource == GpsSource.PHONE_GPS

                if (!isConnected) {
                    applyState(true, targetBle = false)
                } else if (deviceWantsPhoneGps) {
                    applyState(true, targetBle = true)
                } else {
                    applyState(false, targetBle = false)
                }
            }
        }
    }

    private fun applyState(wantActive: Boolean, targetBle: Boolean) {
        val wasActive = active
        active = wantActive
        sendToBle = targetBle
        if (wantActive && !wasActive) start()
        else if (!wantActive && wasActive) stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        var anyProvider = false
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
                anyProvider = true
            }
        } catch (_: SecurityException) { }

        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
                anyProvider = true
            }
        } catch (_: SecurityException) { }

        _isActive.value = anyProvider
    }

    private fun stop() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(locationListener)
        _isActive.value = false
        _hasFix.value = false
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            _hasFix.value = true
            val phoneLoc = PhoneLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude.toFloat(),
                accuracyM = loc.accuracy,
            )
            val current = _location.value
            if (current == null || loc.accuracy < current.accuracyM) {
                _location.value = phoneLoc
            }
            if (sendToBle) {
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
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val gpsStillOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            val netStillOn = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            _isActive.value = gpsStillOn || netStillOn
        }
    }

    companion object {
        private const val MIN_TIME_MS = 5_000L  // 5 s minimum interval (faster for initial fix)
        private const val MIN_DISTANCE_M = 5.0f  // 5 m minimum displacement
    }
}
