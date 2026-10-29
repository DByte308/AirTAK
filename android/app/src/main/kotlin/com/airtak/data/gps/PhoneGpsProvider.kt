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
import kotlin.math.atan2
import kotlin.math.cos
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

/** Outcome of one registration attempt against the platform. */
internal data class GpsRegisterOutcome(
    val registered: Boolean,
    val failure: GpsStartFailure = GpsStartFailure.None,
)

/**
 * Phone-GPS registration state machine, deliberately free of Android types.
 *
 * The issue #13 defect lived in exactly this logic and was untestable where it sat: `active` was
 * assigned from intent before the platform call could fail, so a failed registration looked like a
 * successful one and no later path retried it. Keeping the state machine pure means that failure
 * mode — and the retry after a permission grant — is covered by JVM tests instead of only by a
 * device.
 *
 * [register] is the only effectful part and is injected, so tests can make it fail once and then
 * succeed, which is the real first-launch sequence.
 */
internal class GpsRegistrationController(
    private val register: () -> GpsRegisterOutcome,
) {
    /** True only while a platform registration actually exists. Never set from intent. */
    var isActive: Boolean = false
        private set

    /** What the surrounding link/config state asks for, independent of whether it worked. */
    var wantActive: Boolean = false
        private set

    var failure: GpsStartFailure = GpsStartFailure.None
        private set

    /** Apply a want-active request; returns the registration state afterwards. */
    fun applyState(want: Boolean): Boolean {
        wantActive = want
        if (want && !isActive) attempt()
        else if (!want && isActive) markStopped()
        return isActive
    }

    /** Retry after something changed outside the link/config streams. Idempotent. */
    fun refresh(): Boolean {
        if (wantActive && !isActive) attempt()
        return isActive
    }

    /** Called once the platform listener has actually been removed. */
    fun markStopped() {
        isActive = false
        failure = GpsStartFailure.None
    }

    private fun attempt() {
        val outcome = try {
            register()
        } catch (e: Exception) {
            // A platform call that throws must not leave this machine claiming a registration
            // that never happened; that is the bug this class exists to prevent.
            GpsRegisterOutcome(registered = false, failure = GpsStartFailure.NoProvider)
        }
        isActive = outcome.registered
        failure = if (outcome.registered) GpsStartFailure.None else outcome.failure
    }
}

/**
 * True when an accuracy figure is present and meaningful.
 *
 * [Location.getAccuracy] returns 0.0f when the provider does not supply accuracy, and
 * [Location.hasAccuracy] is the documented test. A non-positive figure carries no information, and
 * neither does a non-finite one: `Float.POSITIVE_INFINITY > 0f` is true, so a bare positivity check
 * would call an infinite accuracy "usable" and then treat it as the best value on the device.
 */
internal fun hasUsableAccuracy(accuracyM: Float?): Boolean =
    accuracyM != null && accuracyM.isFinite() && accuracyM > 0f

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
    // Clamped before the square roots: for near-antipodal points the floating-point sum can land a
    // hair outside [0, 1], and sqrt() of a negative is NaN — which would then compare false against
    // every threshold and silently stop the position from ever updating.
    val a = (sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2))
        .coerceIn(0.0, 1.0)
    return 2 * earthRadiusM * atan2(sqrt(a), sqrt(1 - a))
}

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

/**
 * Reason text for a recenter that cannot happen.
 *
 * Kept here, and given the three separate facts, so the strings are testable in one place: being
 * registered, having a provider switched on, and having a fix are different states and the user
 * needs the one that matches what to do about it.
 */
internal fun noPositionReason(
    registered: Boolean,
    providersEnabled: Boolean,
    hasFix: Boolean,
    failure: GpsStartFailure,
): String = when {
    !registered && failure == GpsStartFailure.PermissionMissing ->
        "Location permission not granted — allow location to see your position"
    !registered && !providersEnabled ->
        "Device location is off — turn it on, or connect a device"
    !registered && failure == GpsStartFailure.NoProvider ->
        "No location provider available — no GPS or network location on this device"
    !registered ->
        "Location updates not running — reopen the map or connect a device"
    registered && !providersEnabled ->
        "Device location is off — turn it on to update your position"
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

    /**
     * The single authority on whether phone GPS is registered. Nothing else may write [_isActive]:
     * the previous code had `active` set from intent in one place and `_isActive` overwritten from
     * provider-enabled guesses in another, so the UI could report a registration that did not exist
     * and the retry path could be skipped because a flag said one did.
     */
    private val registration = GpsRegistrationController(::registerOnce)
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

    /**
     * Whether any location provider is switched on, which is a different fact from being
     * registered: a subscription stays in place while the user has device location off, and it
     * starts delivering again without any re-registration when they turn it back on.
     *
     * Collapsing the two into one flag is what made the old code both wrong and unrecoverable —
     * `onProviderDisabled` overwrote the availability guess over the registration truth.
     */
    private val _providersEnabled = MutableStateFlow(false)
    val providersEnabled: StateFlow<Boolean> = _providersEnabled.asStateFlow()

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
            // A delivery is proof the subscription is live and a provider is on, whichever
            // callback path missed saying so.
            if (!_providersEnabled.value) _providersEnabled.value = true
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

        /**
         * A provider the user switched back on.
         *
         * Resume only when we want updates and do not currently hold a registration. A registration
         * that exists while its provider is disabled is still a registration — Android keeps it and
         * resumes delivery on its own — so re-registering here would add a second listener and start
         * duplicate fixes flowing to the firmware.
         */
        override fun onProviderEnabled(provider: String) {
            if (registration.wantActive && !registration.isActive) {
                registration.refresh()
            }
            publishState()
        }

        override fun onProviderDisabled(provider: String) {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val gpsStillOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            val netStillOn = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
            // Registration state is deliberately untouched here: the subscription is still held,
            // it just has nothing to deliver, and re-registering would duplicate it. Availability
            // is published separately, and the fix we were holding is now stale.
            if (!gpsStillOn && !netStillOn) {
                _hasFix.value = false
                _location.value = null
            }
            publishState()
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
        sendToBle = targetBle
        val wasActive = registration.isActive
        val nowActive = registration.applyState(wantActive)
        // The controller flipped to inactive because the request went away, not because a retry
        // failed: only that case has platform state to tear down.
        if (wasActive && !nowActive) unregister()
        publishState()
    }

    /**
     * Re-attempt registration after something changed outside the link/config streams — the
     * runtime permission grant and app foreground are the two that matter.
     *
     * Cheap and idempotent: it does nothing when registration is already in place.
     */
    fun refresh() {
        registration.refresh()
        publishState()
    }

    /**
     * Publish the authoritative state and nothing else: registration comes from the controller,
     * availability from the platform, so the UI can never drift from either.
     */
    private fun publishState() {
        _isActive.value = registration.isActive
        _startFailure.value = registration.failure
        _providersEnabled.value = anyProviderEnabled()
    }

    private fun anyProviderEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val network = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        return gps || network
    }

    /**
     * Register for location updates and report whether anything was actually registered.
     *
     * The permission is checked before the call rather than discovered through a caught
     * [SecurityException]: on first launch the grant is still pending while this singleton is
     * built, and swallowing that exception left no trace of why the app had no position.
     */
    @SuppressLint("MissingPermission")
    private fun registerOnce(): GpsRegisterOutcome {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return GpsRegisterOutcome(false, GpsStartFailure.NoProvider)
        if (!hasLocationPermission()) {
            return GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing)
        }

        val available = runCatching { lm.allProviders }.getOrNull().orEmpty()
        var registered = false
        var permissionDenied = false
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
                // Remembered rather than reported immediately: the other provider may still
                // register, and the final reason is decided once below.
                permissionDenied = true
            } catch (_: IllegalArgumentException) {
                // Provider vanished between the query and the call; the other one may still work.
            }
        }

        if (registered) {
            // Only the position cache is touched here. Registration state belongs to the
            // controller, which has not committed this outcome yet — publishing from inside the
            // attempt would expose a state the caller is about to write again. The caller
            // (applyState / refresh) publishes once the outcome is committed.
            seedFromLastKnown(lm)
            return GpsRegisterOutcome(true, GpsStartFailure.None)
        }
        // A permission denial outranks "no provider": it is the actionable cause, and losing it
        // behind a generic NoProvider would send the user to the wrong setting.
        return GpsRegisterOutcome(
            registered = false,
            failure = if (permissionDenied) GpsStartFailure.PermissionMissing else GpsStartFailure.NoProvider,
        )
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

    private fun unregister() {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm?.removeUpdates(locationListener)
        registration.markStopped()
        _isActive.value = false
        _hasFix.value = false
        _location.value = null
        _startFailure.value = GpsStartFailure.None
        _providersEnabled.value = false
    }


}
