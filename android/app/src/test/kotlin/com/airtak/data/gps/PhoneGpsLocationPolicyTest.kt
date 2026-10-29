// AirTAK Android — test/kotlin/com/airtak/data/gps/PhoneGpsLocationPolicyTest.kt
//
// JVM tests for the pure decisions inside PhoneGpsProvider: which reading replaces the current
// position, whether a cached last-known fix may be used for centring, and what the recenter
// control says when there is nothing to centre on.
//
// These are the parts of issue #13 that can be proven without a device. The camera call itself and
// the toast are instrumented-level and are NOT claimed as verified here.
package com.airtak.data.gps

import com.airtak.config.constants.GPS_LAST_KNOWN_MAX_ACCURACY_M
import com.airtak.config.constants.GPS_LAST_KNOWN_MAX_AGE_MS
import com.airtak.config.constants.GPS_MIN_DISTANCE_M
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneGpsLocationPolicyTest {

    private fun loc(
        lat: Double = 52.5,
        lon: Double = 13.4,
        accuracyM: Float? = 10f,
        timeMs: Long = 1_000_000L,
    ) = PhoneLocation(latitude = lat, longitude = lon, altitudeM = 0f, accuracyM = accuracyM, timeMs = timeMs)

    // ── hasUsableAccuracy ───────────────────────────────

    @Test fun missingAccuracyIsNotUsable() {
        assertFalse(hasUsableAccuracy(null))
    }

    @Test fun zeroAccuracyIsNotUsable() {
        // Location.getAccuracy() reports 0.0f when the provider supplies no figure. Treating that
        // as "perfectly accurate" is what froze the position permanently at the first reading.
        assertFalse(hasUsableAccuracy(0f))
        assertFalse(hasUsableAccuracy(-1f))
    }

    @Test fun nonFiniteAccuracyIsNotUsable() {
        // POSITIVE_INFINITY > 0f is true, so a positivity-only check would call this usable and
        // then treat it as unbeatable, blocking every real fix afterwards.
        assertFalse(hasUsableAccuracy(Float.POSITIVE_INFINITY))
        assertFalse(hasUsableAccuracy(Float.NaN))
        assertFalse(hasUsableAccuracy(Float.NEGATIVE_INFINITY))
    }

    @Test fun positiveAccuracyIsUsable() {
        assertTrue(hasUsableAccuracy(5f))
    }

    // ── shouldEmitLocation ──────────────────────────────

    @Test fun firstReadingIsAlwaysEmitted() {
        assertTrue(shouldEmitLocation(current = null, incoming = loc()))
    }

    @Test fun olderReadingIsRejectedEvenWhenMoreAccurate() {
        val current = loc(accuracyM = 40f, timeMs = 2_000L)
        val staleBetter = loc(accuracyM = 2f, timeMs = 1_000L)
        assertFalse(shouldEmitLocation(current, staleBetter))
    }

    @Test fun newerButLessAccurateWithoutMovementIsRejected() {
        // The jitter filtering the original comment described must survive the fix: a newer
        // timestamp on its own is not information.
        val current = loc(accuracyM = 5f, timeMs = 2_000L)
        val jitter = loc(accuracyM = 60f, timeMs = 3_000L)
        assertFalse(shouldEmitLocation(current, jitter))
    }

    @Test fun newerAndMoreAccurateIsEmitted() {
        val current = loc(accuracyM = 40f, timeMs = 2_000L)
        assertTrue(shouldEmitLocation(current, loc(accuracyM = 8f, timeMs = 3_000L)))
    }

    @Test fun equalAccuracyWithoutMovementIsRejected() {
        val current = loc(accuracyM = 12f, timeMs = 2_000L)
        assertFalse(shouldEmitLocation(current, loc(accuracyM = 12f, timeMs = 3_000L)))
    }

    @Test fun unknownCurrentAccuracyIsReplacedByAnyKnownFix() {
        // The regression itself: a first reading with unknown accuracy used to block every later
        // fix, because nothing is ever "better" than 0.0f.
        val current = loc(accuracyM = 0f, timeMs = 2_000L)
        assertTrue(shouldEmitLocation(current, loc(accuracyM = 30f, timeMs = 3_000L)))
    }

    @Test fun unknownIncomingAccuracyWithoutMovementIsRejected() {
        val current = loc(accuracyM = 8f, timeMs = 2_000L)
        assertFalse(shouldEmitLocation(current, loc(accuracyM = null, timeMs = 3_000L)))
    }

    @Test fun bothAccuraciesUnknownStillFollowsMovement() {
        val current = loc(accuracyM = null, timeMs = 2_000L)
        val moved = loc(lat = 52.5005, lon = 13.4, accuracyM = null, timeMs = 3_000L)
        assertTrue(shouldEmitLocation(current, moved))
    }

    @Test fun movementBeyondThresholdWinsOverWorseAccuracy() {
        val current = loc(accuracyM = 3f, timeMs = 2_000L)
        // ~55 m north of 52.5 — comfortably past GPS_MIN_DISTANCE_M.
        val moved = loc(lat = 52.5005, lon = 13.4, accuracyM = 90f, timeMs = 3_000L)
        assertTrue(shouldEmitLocation(current, moved))
    }

    @Test fun movementBelowThresholdDoesNotEmit() {
        val current = loc(accuracyM = 3f, timeMs = 2_000L)
        // ~1.1 m north: under GPS_MIN_DISTANCE_M, and worse accuracy, so it is jitter.
        val drifted = loc(lat = 52.50001, lon = 13.4, accuracyM = 90f, timeMs = 3_000L)
        assertFalse(shouldEmitLocation(current, drifted))
    }

    @Test fun readingsWithoutTimestampsFallBackToAccuracyAndMovement() {
        val current = loc(accuracyM = 40f, timeMs = 0L)
        assertTrue(shouldEmitLocation(current, loc(accuracyM = 5f, timeMs = 0L)))
        assertFalse(shouldEmitLocation(current, loc(accuracyM = 80f, timeMs = 0L)))
    }

    // ── groundDistanceM ─────────────────────────────────

    @Test fun groundDistanceMatchesKnownLatitudeStep() {
        // One degree of latitude is ~111.19 km on the spherical model used here.
        val metres = groundDistanceM(0.0, 0.0, 1.0, 0.0)
        assertTrue(metres in 111_000.0..111_400.0, "got $metres")
    }

    @Test fun groundDistanceIsSymmetricAndZeroForSamePoint() {
        assertEquals(0.0, groundDistanceM(48.1, 11.6, 48.1, 11.6), 1e-9)
        val a = groundDistanceM(52.5, 13.4, 52.51, 13.41)
        val b = groundDistanceM(52.51, 13.41, 52.5, 13.4)
        assertEquals(a, b, 1e-6)
    }

    @Test fun antipodalDistanceStaysFinite() {
        // Near-antipodal points can push the haversine sum outside [0, 1] in floating point; sqrt
        // of a negative is NaN, and NaN compares false against every threshold, which would
        // silently stop the position from ever being treated as "moved".
        val cases = listOf(
            Triple(0.0, 0.0, 180.0) to Triple(0.0, 180.0, 0.0),
            Triple(89.9999, 0.0, 0.0) to Triple(-89.9999, 180.0, 0.0),
            Triple(-45.0, 135.0, 0.0) to Triple(45.0, -45.0, 0.0),
        )
        for ((from, to) in cases) {
            val metres = groundDistanceM(from.first, from.second, to.first, to.second)
            assertFalse(metres.isNaN(), "NaN for $from -> $to")
            assertTrue(metres.isFinite(), "non-finite for $from -> $to")
            assertTrue(metres > 0.0, "expected positive distance for $from -> $to, got $metres")
        }
    }

    @Test fun antipodalDistanceIsCloseToHalfCircumference() {
        val metres = groundDistanceM(0.0, 0.0, 0.0, 180.0)
        assertTrue(metres in 20_000_000.0..20_100_000.0, "got $metres")
    }

    @Test fun thresholdConstantIsWhatThePolicyAssumes() {
        // The policy compares against GPS_MIN_DISTANCE_M; a silent change to that constant would
        // change behaviour without any test above noticing. Pin the value the tests reason about.
        assertEquals(5.0f, GPS_MIN_DISTANCE_M, 1e-6f)
    }

    // ── isLastKnownUsable ───────────────────────────────

    @Test fun freshAccurateLastKnownIsUsable() {
        assertTrue(isLastKnownUsable(ageMs = 5_000L, accuracyM = 12f))
    }

    @Test fun staleLastKnownIsRejected() {
        assertFalse(isLastKnownUsable(ageMs = GPS_LAST_KNOWN_MAX_AGE_MS + 1, accuracyM = 5f))
    }

    @Test fun impreciseLastKnownIsRejected() {
        assertFalse(isLastKnownUsable(ageMs = 1_000L, accuracyM = GPS_LAST_KNOWN_MAX_ACCURACY_M + 1f))
    }

    @Test fun lastKnownWithoutAccuracyIsRejected() {
        assertFalse(isLastKnownUsable(ageMs = 1_000L, accuracyM = null))
        assertFalse(isLastKnownUsable(ageMs = 1_000L, accuracyM = 0f))
    }

    @Test fun futureTimestampLastKnownIsRejected() {
        // Negative age means the wall clock and the fix time disagree; provenance is unknown, so
        // it is not trusted as a centring target.
        assertFalse(isLastKnownUsable(ageMs = -1_000L, accuracyM = 4f))
    }

    @Test fun budgetBoundariesAreInclusive() {
        assertTrue(isLastKnownUsable(ageMs = GPS_LAST_KNOWN_MAX_AGE_MS, accuracyM = GPS_LAST_KNOWN_MAX_ACCURACY_M))
    }

    // ── noPositionReason ────────────────────────────────

    @Test fun permissionFailureIsNamedAsPermission() {
        val reason = noPositionReason(
            registered = false,
            providersEnabled = true,
            hasFix = false,
            failure = GpsStartFailure.PermissionMissing,
        )
        assertTrue(reason.contains("permission", ignoreCase = true), reason)
    }

    @Test fun waitingForFixIsNamedAsFixAndNotAsPermission() {
        val reason = noPositionReason(
            registered = true,
            providersEnabled = true,
            hasFix = false,
            failure = GpsStartFailure.None,
        )
        assertTrue(reason.contains("fix", ignoreCase = true), reason)
        assertFalse(reason.contains("permission", ignoreCase = true), reason)
    }

    @Test fun missingProviderIsNamedAsProvider() {
        val reason = noPositionReason(
            registered = false,
            providersEnabled = true,
            hasFix = false,
            failure = GpsStartFailure.NoProvider,
        )
        assertTrue(reason.contains("provider", ignoreCase = true), reason)
    }

    @Test fun registeredButLocationOffIsNamedAsLocationOff() {
        // The registered-vs-enabled distinction: the subscription is held, the radio is off. Telling
        // this user to grant a permission would send them to the wrong setting.
        val reason = noPositionReason(
            registered = true,
            providersEnabled = false,
            hasFix = false,
            failure = GpsStartFailure.None,
        )
        assertTrue(reason.contains("off", ignoreCase = true) || reason.contains("turn", ignoreCase = true), reason)
        assertFalse(reason.contains("permission", ignoreCase = true), reason)
    }

    @Test fun notRegisteredAndLocationOffPrefersLocationOffOverProviderAbsence() {
        val reason = noPositionReason(
            registered = false,
            providersEnabled = false,
            hasFix = false,
            failure = GpsStartFailure.NoProvider,
        )
        assertTrue(reason.contains("turn", ignoreCase = true), reason)
    }

    @Test fun everyNoPositionStateProducesNonEmptyText() {
        // The bug this replaces was a silent `return`; an empty string would reproduce it.
        for (failure in GpsStartFailure.entries) {
            for (registered in listOf(true, false)) {
                for (enabled in listOf(true, false)) {
                    for (hasFix in listOf(true, false)) {
                        val reason = noPositionReason(registered, enabled, hasFix, failure)
                        assertTrue(
                            reason.isNotBlank(),
                            "$registered/$enabled/$hasFix/$failure produced '$reason'",
                        )
                    }
                }
            }
        }
    }
}
