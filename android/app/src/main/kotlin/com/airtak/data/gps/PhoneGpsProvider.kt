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
//   - When gps_source is DEVICE_GPS (device connected), phone GPS keeps
//     tracking for map fallback (location StateFlow) but does NOT forward
//     to firmware — the device uses its own GPS module.
//   - When NO device is connected, phone GPS starts automatically and exposes
//     location via the `location` StateFlow for direct map consumption. This
//     covers the standalone-app case (no LoRa device). No BLE send occurs.
//
// Providers: registers both GPS_PROVIDER (satellite) and NETWORK_PROVIDER
// (Wi-Fi/cell tower) for faster initial fix and indoor coverage.
//
// Registration and retry: registration can legitimately fail — the runtime location
// permission may not be granted yet on first launch, because MainActivity raises that prompt
// in onCreate while Hilt is already constructing this singleton. A failed registration used to
// be swallowed and, worse, `active` was set from intent rather than from the result, so no later
// code path could retry it: the provider stayed dead for the whole process and the map had no
// own position to centre on. Registration result is now the only thing that sets `active`, the
// reason is published as `startFailure`, and `refresh()` re-attempts it after a permission grant
// or an app resume.
//
// Emission policy (see shouldEmitLocation): a reading replaces the current one when it is
// genuinely better — the user moved, the accuracy improved, or the current value has no usable
// accuracy — and never when it is older. "Only the best accuracy" alone is not safe: accuracy is
// 0.0f when a provider does not report it, so a first reading with unknown accuracy would make
// every later real fix look worse and be discarded forever.
//
// Throttling: LocationManager is asked for at most one update per 5 seconds per provider and only
// after 5 metres of movement; shouldEmitLocation then decides whether the reading replaces the
// current one.

package com.airtak.data.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.airtak.config.constants.GPS_LAST_KNOWN_MAX_ACCURACY_M
import com.airtak.config.constants.GPS_LAST_KNOWN_MAX_AGE_MS
import com.airtak.config.constants.GPS_MIN_DISTANCE_M
import com.airtak.config.constants.GPS_MIN_TIME_MS
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A phone position reading.
 *
 * [accuracyM] is null when the provider did not report an accuracy figure. It must not be
 * represented as 0.0f: that reads as "perfectly accurate" to any comparison and permanently
 * blocks every later fix.
 *
 * [timeMs] is the fix time from [Location.getTime], used only to reject readings that are older
 * than what we already hold.
 */
data class PhoneLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float,
    val accuracyM: Float? = null,
    val timeMs: Long = 0L,
)

/** Why phone GPS is not currently registered. Published so the UI can say something true. */
enum class GpsStartFailure { None, PermissionMissing, NoProvider }

/**
 * True when an accuracy figure is present and meaningful.
 *
 * [Location.getAccuracy] returns 0.0f when the provider does not supply accuracy, and
 * [Location.hasAccuracy] is the documented test. A non-positive figure carries no information.
 */
internal fun hasUsableAccuracy(accuracyM: Float?): Boolean = accuracyM != null && accuracyM > 0f

/**
 * Ground distance in metres between two points (haversine, spherical earth).
 *
 * Deliberately not [Location.distanceTo]: that is an Android API and this decision has to be
 * testable on the JVM without a device or Robolectric.
 */
internal fun groundDistanceM(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusM * atan2Safe(sqrt(a), sqrt(1 - a))
}

private fun atan2Safe(y: Double, x: Double): Double = kotlin.math.atan2(y, x)

/**
 * Decide whether [incoming] replaces [current] as the phone position.
 *
 * Order matters, and each rule exists because of a concrete failure:
 *  1. No current value → take it. There is nothing to compare against.
 *  2. Older than current → reject, even if it looks more accurate. An out-of-order or stale
 *     delivery must never move the marker backwards, and "best accuracy wins" applied to a stale
 *     fix would do exactly that.
 *  3. Moved more than [GPS_MIN_DISTANCE_M] → take it. The user's position on the map matters more
 *     than a marginal accuracy figure, and this is the rule the old accuracy-only gate silently
 *     dropped.
 *  4. Current value has no usable accuracy and the incoming one does → take it. Without this rule
 *     a first reading whose accuracy is unknown blocks every later fix forever.
 *  5. Both accuracies usable and incoming is better → take it. This is the jitter filtering the
 *     original comment described, and it is kept.
 *  6. Otherwise → reject. A newer reading that is less accurate and shows no movement is jitter,
 *     not information; accepting every newer timestamp would defeat the filtering on purpose.
 */
internal fun shouldEmitLocation(current: PhoneLocation?, incoming: PhoneLocation): Boolean {
    if (current == null) return true

    val currentHasTime = current.timeMs > 0L
    val incomingHasTime = incoming.timeMs > 0L
    if (currentHasTime && incomingHasTime && incoming.timeMs < current.timeMs) return false

    val moved = groundDistanceM(
        current.latitude, current.longitude, incoming.latitude, incoming.longitude,
    ) > GPS_MIN_DISTANCE_M
    if (moved) return true

    val incomingUsable = hasUsableAccuracy(incoming.accuracyM)
    val currentUsable = hasUsableAccuracy(current.accuracyM)
    if (!currentUsable && incomingUsable) return true
    if (currentUsable && incomingUsable && incoming.accuracyM!! < current.accuracyM!!) return true
    return false
}

/**
 * Decide whether a cached last-known location is fit to centre a map on.
 *
 * A last-known fix can be hours old and hundreds of metres off, and centring on it silently would
 * show the user a place they are not. So it is accepted only inside an age and accuracy budget.
 * A negative age means the wall clock and the fix time disagree (clock skew or a provider that
 * stamps odd times); provenance is then unknown, so it is rejected rather than trusted.
 */
internal fun isLastKnownUsable(ageMs: Long, accuracyM: Float?): Boolean {
    if (ageMs < 0L) return false
    if (ageMs > GPS_LAST_KNOWN_MAX_AGE_MS) return false
    return hasUsableAccuracy(accuracyM) && accuracyM!! <= GPS_LAST_KNOWN_MAX_ACCURACY_M
}

/** Reason text for a recenter that cannot happen. Kept here so the strings are testable in one place. */
internal fun noPositionReason(
    registered: Boolean,
    hasFix: Boolean,
    failure: GpsStartFailure,
): String = when {
    !registered && failure == GpsStartFailure.PermissionMissing ->
        "Location permission not granted — allow location to see your position"
    !registered && failure == GpsStartFailure.NoProvider ->
        "No location provider available — turn device location on"
    !registered ->
        "Location not active — turn device location on or connect a device"
    !hasFix ->
        "No position yet — waiting for a GPS fix"
    else ->
        "No position yet — waiting for a location or a connected device"
}

@Singleton
class PhoneGpsProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: ConfigRepository,
    private val ble: BleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** What the current link/config state asks for, independent of whether registration worked. */
    private var wantActive = false

    /** True only while at least one provider is actually registered. Never set from intent. */
    private var active = false
    private var sendToBle = false

    private val _location = MutableStateFlow<PhoneLocation?>(null)
    val location: StateFlow<PhoneLocation?> = _location.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _hasFix = MutableStateFlow(false)
    val hasFix: StateFlow<Boolean> = _hasFix.asStateFlow()

    private val _startFailure = MutableStateFlow(GpsStartFailure.None)

    /** Why registration is not working, so the UI can name the reason instead of doing nothing. */
    val startFailure: StateFlow<GpsStartFailure> = _startFailure.asStateFlow()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            _hasFix.value = true
            val phoneLoc = PhoneLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude.toFloat(),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                timeMs = loc.time,
            )
            if (shouldEmitLocation(_location.value, phoneLoc)) {
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
            if (!gpsStillOn && !netStillOn) {
                _hasFix.value = false
                _location.value = null
            }
        }
    }

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
                    applyState(true, targetBle = false)
                }
            }
        }
    }

    private fun applyState(wantActive: Boolean, targetBle: Boolean) {
        this.wantActive = wantActive
        sendToBle = targetBle
        if (wantActive && !active) {
            // Registration result, not intent, decides `active`. If it fails the state stays
            // false, so a later refresh() (permission grant, resume) takes the same branch again
            // instead of being skipped by an "already active" flag that was never true.
            active = register()
        } else if (!wantActive && active) {
            stop()
            active = false
        }
    }

    /**
     * Re-attempt registration after something changed outside the link/config streams — the
     * runtime permission grant and app foreground are the two that matter.
     *
     * Cheap and idempotent: it does nothing when registration is already in place.
     */
    fun refresh() {
        if (wantActive && !active) active = register()
    }

    /**
     * Register for location updates and report whether anything was actually registered.
     *
     * The permission is checked before the call rather than discovered through a caught
     * [SecurityException]: on first launch the grant is still pending while this singleton is
     * built, and swallowing that exception left no trace of why the app had no position.
     */
    @SuppressLint("MissingPermission")
    private fun register(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            _startFailure.value = GpsStartFailure.NoProvider
            _isActive.value = false
            return false
        }
        if (!hasLocationPermission()) {
            _startFailure.value = GpsStartFailure.PermissionMissing
            _isActive.value = false
            return false
        }

        val available = runCatching { lm.allProviders }.getOrNull().orEmpty()
        var registered = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in available) continue
            try {
                lm.requestLocationUpdates(
                    provider,
                    GPS_MIN_TIME_MS,
                    GPS_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper(),
                )
                registered = true
            } catch (_: SecurityException) {
                _startFailure.value = GpsStartFailure.PermissionMissing
            } catch (_: IllegalArgumentException) {
                // Provider vanished between the query and the call; the other one may still work.
            }
        }

        _startFailure.value = if (registered) GpsStartFailure.None else GpsStartFailure.NoProvider
        _isActive.value = registered
        if (registered) seedFromLastKnown(lm)
        return registered
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Seed from a cached fix so the map has something to centre on before the first fresh fix.
     *
     * Only within the age and accuracy budget in [isLastKnownUsable]: an old or vague cached
     * position would put a marker on a place the user is not, which is worse than no position.
     */
    @SuppressLint("MissingPermission")
    private fun seedFromLastKnown(lm: LocationManager) {
        if (_location.value != null) return
        val now = System.currentTimeMillis()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
            if (!isLastKnownUsable(now - loc.time, accuracy)) continue
            _location.value = PhoneLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude.toFloat(),
                accuracyM = accuracy,
                timeMs = loc.time,
            )
            _hasFix.value = true
            return
        }
    }

    private fun stop() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(locationListener)
        _isActive.value = false
        _hasFix.value = false
        _location.value = null
        _startFailure.value = GpsStartFailure.None
    }


}
