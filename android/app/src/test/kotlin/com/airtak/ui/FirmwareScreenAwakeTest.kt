package com.airtak.ui

import com.airtak.data.ota.FirmwareUpdateActivity
import com.airtak.ui.components.ScreenAwakeLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FirmwareScreenAwakeTest {
    @Test fun viewFlagIsEnabledThenRestored() {
        var flag = false
        val lease = ScreenAwakeLease({ flag }, { flag = it })
        assertTrue(flag)
        lease.close()
        assertFalse(flag)
    }

    @Test fun preexistingKeepAwakeIsPreserved() {
        var flag = true
        ScreenAwakeLease({ flag }, { flag = it }).close()
        assertTrue(flag)
    }

    @Test fun disposingTwiceDoesNotOverwriteLaterViewState() {
        var flag = false
        val lease = ScreenAwakeLease({ flag }, { flag = it })
        lease.close()
        flag = true
        lease.close()
        assertTrue(flag)
    }

    @Test fun successfulUpdateReleasesActivity() = runTest {
        val activity = FirmwareUpdateActivity()
        assertFalse(activity.isActive.value)
        val result = activity.duringUpdate {
            assertTrue(activity.isActive.value)
            "finished"
        }
        assertEquals("finished", result)
        assertFalse(activity.isActive.value)
    }

    @Test fun failedUpdateReleasesActivity() = runTest {
        val activity = FirmwareUpdateActivity()
        try {
            activity.duringUpdate<Unit> { throw IllegalStateException("test failure") }
            fail("expected failure")
        } catch (_: IllegalStateException) {
            assertFalse(activity.isActive.value)
        }
    }

    @Test fun returnedFailureAlsoReleasesActivity() = runTest {
        val activity = FirmwareUpdateActivity()
        val result = activity.duringUpdate { Result.failure<Unit>(IllegalStateException("transfer failed")) }
        assertTrue(result.isFailure)
        assertFalse(activity.isActive.value)
    }

    @Test fun cancellationReleasesActivity() = runTest {
        val activity = FirmwareUpdateActivity()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            activity.duringUpdate {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        assertTrue(activity.isActive.value)
        job.cancel()
        job.join()
        assertFalse(activity.isActive.value)
    }

    @Test fun overlappingUpdatesKeepActivityUntilLastCompletion() = runTest {
        val activity = FirmwareUpdateActivity()
        activity.duringUpdate {
            activity.duringUpdate { assertTrue(activity.isActive.value) }
            assertTrue(activity.isActive.value)
        }
        assertFalse(activity.isActive.value)
    }
}
