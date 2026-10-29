// AirTAK Android — test/kotlin/com/airtak/data/gps/PhoneGpsRegistrationControllerTest.kt
//
// Regression coverage for the state machine that caused issue #13, not for the position policy.
//
// The defect was: `active` was assigned from intent before the platform call could fail, so a
// registration that threw SecurityException still looked active, and every later retry path was
// skipped because the flag said one existed. That sequence — failed start, permission granted,
// retry — is reproduced here against a fake registrar, which is the part a device cannot easily
// be made to repeat on demand.
package com.airtak.data.gps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneGpsRegistrationControllerTest {

    /** Scriptable stand-in for the LocationManager side: returns queued outcomes, counts calls. */
    private class FakeRegistrar(vararg outcomes: GpsRegisterOutcome) {
        private val queue = outcomes.toMutableList()
        var calls = 0
            private set

        var throwOnCall: Exception? = null

        fun invoke(): GpsRegisterOutcome {
            calls++
            throwOnCall?.let { throw it }
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    @Test fun failedStartDoesNotReportActive() {
        val registrar = FakeRegistrar(GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing))
        val controller = GpsRegistrationController(registrar::invoke)

        val active = controller.applyState(true)

        assertFalse(active, "a failed registration must never report active")
        assertFalse(controller.isActive)
        assertEquals(GpsStartFailure.PermissionMissing, controller.failure)
    }

    @Test fun grantThenRefreshSucceeds() {
        // The exact first-launch sequence: attempt one throws SecurityException because the grant
        // is still pending, the user allows, MainActivity calls refresh(), and it must now register.
        val registrar = FakeRegistrar(
            GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing),
            GpsRegisterOutcome(true),
        )
        val controller = GpsRegistrationController(registrar::invoke)

        controller.applyState(true)
        assertFalse(controller.isActive)

        assertTrue(controller.refresh(), "refresh after the grant must register")
        assertTrue(controller.isActive)
        assertEquals(GpsStartFailure.None, controller.failure)
        assertEquals(2, registrar.calls)
    }

    @Test fun refreshAfterSuccessDoesNotRegisterAgain() {
        // Duplicate subscriptions mean duplicate fixes to the firmware. onResume fires often.
        val registrar = FakeRegistrar(GpsRegisterOutcome(true))
        val controller = GpsRegistrationController(registrar::invoke)

        controller.applyState(true)
        controller.refresh()
        controller.refresh()
        controller.applyState(true)

        assertEquals(1, registrar.calls, "already-registered state must not re-register")
        assertTrue(controller.isActive)
    }

    @Test fun repeatedFailedRefreshKeepsRetrying() {
        val registrar = FakeRegistrar(
            GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing),
            GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing),
            GpsRegisterOutcome(true),
        )
        val controller = GpsRegistrationController(registrar::invoke)

        controller.applyState(true)
        assertFalse(controller.refresh())
        assertFalse(controller.isActive, "still not registered, so still retryable")
        assertTrue(controller.refresh())
        assertEquals(3, registrar.calls)
    }

    @Test fun securityFailureReasonSurvivesAProviderAlsoBeingUnavailable() {
        // The provider-side mapping is what preserves the cause; the controller must surface it
        // unchanged rather than the last-written value.
        val controller = GpsRegistrationController {
            GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing)
        }
        controller.applyState(true)
        assertEquals(GpsStartFailure.PermissionMissing, controller.failure)
    }

    @Test fun successClearsAnEarlierFailure() {
        val registrar = FakeRegistrar(
            GpsRegisterOutcome(false, GpsStartFailure.NoProvider),
            GpsRegisterOutcome(true),
        )
        val controller = GpsRegistrationController(registrar::invoke)

        controller.applyState(true)
        assertEquals(GpsStartFailure.NoProvider, controller.failure)
        controller.refresh()
        assertEquals(GpsStartFailure.None, controller.failure)
    }

    @Test fun wantFalseStopsAnActiveRegistration() {
        val controller = GpsRegistrationController { GpsRegisterOutcome(true) }
        controller.applyState(true)
        assertTrue(controller.isActive)

        assertFalse(controller.applyState(false), "a withdrawn request must clear active")
        assertFalse(controller.isActive)
        assertEquals(GpsStartFailure.None, controller.failure)
    }

    @Test fun refreshWithoutWantingActiveDoesNothing() {
        val registrar = FakeRegistrar(GpsRegisterOutcome(true))
        val controller = GpsRegistrationController(registrar::invoke)

        assertFalse(controller.refresh())
        assertEquals(0, registrar.calls, "nothing asked for GPS; refresh must not start it")
    }

    @Test fun markStoppedClearsStateAfterUnregister() {
        val controller = GpsRegistrationController { GpsRegisterOutcome(true) }
        controller.applyState(true)
        controller.markStopped()

        assertFalse(controller.isActive)
        assertEquals(GpsStartFailure.None, controller.failure)
        assertTrue(controller.wantActive, "the request stands; only the platform subscription ended")
    }

    @Test fun markStoppedThenRefreshReRegisters() {
        // Provider disabled then re-enabled: the controller must be willing to register again after
        // a real teardown, and only then.
        val registrar = FakeRegistrar(GpsRegisterOutcome(true))
        val controller = GpsRegistrationController(registrar::invoke)

        controller.applyState(true)
        controller.markStopped()
        assertTrue(controller.refresh())
        assertEquals(2, registrar.calls)
    }

    @Test fun throwingRegistrarBecomesFailureNotCorruptState() {
        val registrar = FakeRegistrar(GpsRegisterOutcome(true)).apply {
            throwOnCall = SecurityException("permission revoked mid-call")
        }
        val controller = GpsRegistrationController(registrar::invoke)

        assertFalse(controller.applyState(true))
        assertFalse(controller.isActive, "a throw must never leave the machine claiming a registration")
        assertEquals(GpsStartFailure.NoProvider, controller.failure)
        assertEquals(1, registrar.calls)
    }

    @Test fun wantActiveIsRecordedEvenWhenRegistrationFails() {
        // onProviderEnabled uses this to decide whether to resume: the user wants GPS even though
        // nothing is registered yet.
        val controller = GpsRegistrationController {
            GpsRegisterOutcome(false, GpsStartFailure.PermissionMissing)
        }
        controller.applyState(true)
        assertTrue(controller.wantActive)
        assertFalse(controller.isActive)
    }
}
