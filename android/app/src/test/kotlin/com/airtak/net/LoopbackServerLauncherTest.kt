package com.airtak.net

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The listener launcher's failure path, exercised without a device or a real socket.
 *
 * The defect this exists for: `runCatching { server.start() }.onFailure { field = null }` was the
 * whole error handling for the tile listener, so a bind failure produced no log, no state, and
 * nothing the UI could show. A test that only asserts "ensure() returns null on failure" would
 * have passed against that code. These tests assert the reason survives and is readable.
 */
class LoopbackServerLauncherTest {

    private class FakeListener(val port: Int) {
        var closed = false
    }

    private class Harness(
        private val closeFails: Boolean = false,
        private val openResult: () -> FakeListener,
    ) {
        val diagnostics = CopyOnWriteArrayList<String>()
        var openCalls = 0
        val launcher = LoopbackServerLauncher(
            open = { openCalls++; openResult() },
            close = { l ->
                if (closeFails) throw IOException("close refused")
                l.closed = true
            },
            portOf = { it.port },
            onDiagnostic = { diagnostics += it },
        )
    }

    @Test
    fun startFailureBecomesReadableStateInsteadOfAnInvisibleNull() {
        val h = Harness { throw IOException("bind failed: permission denied") }

        assertNull(h.launcher.ensure())

        val state = h.launcher.state.value
        assertTrue(
            state is LoopbackServerLauncher.State.Failed &&
                state.reason == "bind failed: permission denied",
            "expected the bind reason in state, got $state",
        )
        assertTrue(
            h.diagnostics.any { it.contains("bind failed: permission denied") },
            "expected a diagnostic carrying the reason, got ${h.diagnostics}",
        )
    }

    @Test
    fun failureReasonFallsBackToExceptionTypeWhenTheMessageIsNull() {
        val h = Harness { throw IllegalStateException() }

        assertNull(h.launcher.ensure())

        val state = h.launcher.state.value
        assertTrue(
            state is LoopbackServerLauncher.State.Failed &&
                state.reason == "IllegalStateException",
            "expected the exception type as the reason, got $state",
        )
    }

    @Test
    fun aFailedStartDoesNotLatchAndTheNextAttemptCanSucceed() {
        var attempts = 0
        val h = Harness {
            attempts++
            if (attempts == 1) throw IOException("port busy")
            FakeListener(4321)
        }

        assertNull(h.launcher.ensure())
        assertTrue(h.launcher.state.value is LoopbackServerLauncher.State.Failed)

        val listener = h.launcher.ensure()
        assertNotNull(listener, "a transient bind failure must not disable the proxy permanently")
        assertEquals(
            LoopbackServerLauncher.State.Running(4321),
            h.launcher.state.value,
        )
        assertEquals(2, h.openCalls)
    }

    @Test
    fun ensureIsIdempotentWhileAListenerIsLive() {
        val h = Harness { FakeListener(9000) }

        val first = h.launcher.ensure()
        val second = h.launcher.ensure()

        assertSame(first, second)
        assertEquals(1, h.openCalls, "a live listener must not be re-opened on every call")
        assertEquals(LoopbackServerLauncher.State.Running(9000), h.launcher.state.value)
    }

    @Test
    fun closingReturnsToIdleAndAllowsAFreshListener() {
        val h = Harness { FakeListener(7000) }
        val first = h.launcher.ensure()!!

        assertEquals(LoopbackServerLauncher.State.Idle, h.launcher.closeCurrent())
        assertTrue(first.closed, "the listener must actually be closed, not just dereferenced")
        assertNull(h.launcher.current)

        val second = h.launcher.ensure()
        assertNotNull(second)
        assertEquals(2, h.openCalls)
    }

    @Test
    fun closeFailureIsReportedRatherThanSwallowed() {
        val h = Harness(closeFails = true) { FakeListener(7100) }
        h.launcher.ensure()

        val state = h.launcher.closeCurrent()

        assertTrue(
            state is LoopbackServerLauncher.State.Failed && state.reason.contains("close refused"),
            "expected the close reason, got $state",
        )
        assertNull(h.launcher.current, "a failed close must still drop the handle, not leak it")
    }

    @Test
    fun cancellationFromTheOpenSeamPropagatesInsteadOfBecomingAFailedState() {
        val h = Harness { throw CancellationException("scope gone") }

        try {
            h.launcher.ensure()
            fail("cancellation must propagate, not be recorded as a bind failure")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // correct
        }

        assertTrue(
            h.launcher.state.value is LoopbackServerLauncher.State.Idle,
            "a cancelled caller must not be reported as a listener failure",
        )
    }

    @Test
    fun stateIsExposedAsAFlowTheUiCanObserve() {
        val h = Harness { FakeListener(5000) }
        val flow: StateFlow<LoopbackServerLauncher.State> = h.launcher.state

        assertEquals(LoopbackServerLauncher.State.Idle, flow.value)
        h.launcher.ensure()
        assertEquals(LoopbackServerLauncher.State.Running(5000), flow.value)
    }
}
