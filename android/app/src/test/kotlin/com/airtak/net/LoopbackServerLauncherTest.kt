package com.airtak.net

import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    fun concurrentEnsureOpensExactlyOneListener() {
        // The check-then-open sequence in ensure() is not atomic. Without serialisation both
        // threads bind a socket and only one handle survives, leaving a listener that accepts
        // connections and can never be closed.
        // Barrier releases all four threads into ensure() at the same instant; the sleep inside
        // open() widens the window the lock is supposed to close so a missing lock fails loudly
        // rather than passing by timing luck.
        val barrier = CyclicBarrier(4)
        val opened = AtomicInteger()
        val h = Harness {
            Thread.sleep(40)
            FakeListener(8000 + opened.incrementAndGet())
        }

        val results = java.util.concurrent.CopyOnWriteArrayList<FakeListener?>()
        val threads = (1..4).map {
            Thread {
                barrier.await(5, TimeUnit.SECONDS)
                results += h.launcher.ensure()
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals(1, opened.get(), "ensure() opened ${opened.get()} listeners, expected exactly 1")
        assertEquals(4, results.size)
        assertTrue(results.all { it === results.first() }, "callers received different listener instances")
        assertNotNull(results.first(), "every caller must get the one live listener")
    }

    @Test
    fun closeAndEnsureNeverInterleaveIntoTwoListeners() {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val launcher = LoopbackServerLauncher(
            open = { Thread.sleep(15); FakeListener(9000 + opened.incrementAndGet()) },
            close = { closed.incrementAndGet(); Thread.sleep(10) },
            portOf = { it.port },
        )

        val stop = AtomicBoolean(true)
        val churn = (1..4).map {
            Thread {
                while (stop.get()) {
                    launcher.ensure()
                    launcher.closeCurrent()
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(600)
        stop.set(false)
        churn.forEach { it.join(10_000) }

        launcher.closeCurrent()
        // Every listener that was opened must have been closed: open count == close count means no
        // socket was dropped on the floor by a concurrent ensure/close interleaving.
        assertEquals(
            opened.get(),
            closed.get(),
            "opened ${opened.get()} listeners but closed ${closed.get()} — a listener leaked",
        )
        assertEquals(LoopbackServerLauncher.State.Idle, launcher.state.value)
    }

    @Test
    fun aThrowingDiagnosticDoesNotDropALiveListener() {
        // Logging is an observer. If the sink throws after a successful open, the caller must still
        // receive the live listener rather than null with a socket still bound.
        var opens = 0
        val launcher = LoopbackServerLauncher(
            open = { opens++; FakeListener(6000) },
            close = { },
            portOf = { it.port },
            onDiagnostic = { throw IllegalStateException("log sink is broken") },
        )

        val listener = launcher.ensure()

        assertNotNull(listener, "a broken log sink must not turn a successful start into null")
        assertEquals(LoopbackServerLauncher.State.Running(6000), launcher.state.value)
        assertSame(listener, launcher.current)
        assertEquals(1, opens)
        // And the failure path still reports state even when the sink is broken.
        val failing = LoopbackServerLauncher<FakeListener>(
            open = { throw IOException("bind refused") },
            close = { },
            portOf = { it.port },
            onDiagnostic = { throw IllegalStateException("log sink is broken") },
        )
        assertNull(failing.ensure())
        assertTrue(failing.state.value is LoopbackServerLauncher.State.Failed)
    }

    @Test
    fun portLookupFailureClosesTheListenerItAlreadyOpened() {
        var closed = 0
        val launcher = LoopbackServerLauncher(
            open = { FakeListener(6100) },
            close = { closed++ },
            portOf = { throw IllegalStateException("port not assigned") },
        )

        assertNull(launcher.ensure())
        assertEquals(1, closed, "a listener opened before a failed port lookup must be closed")
        assertNull(launcher.current)
        assertTrue(
            (launcher.state.value as? LoopbackServerLauncher.State.Failed)?.reason
                ?.contains("port not assigned") == true,
            "expected the port reason, got ${launcher.state.value}",
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
