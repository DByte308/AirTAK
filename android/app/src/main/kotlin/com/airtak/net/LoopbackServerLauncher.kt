package com.airtak.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts a loopback listener once, and keeps the reason when starting it fails.
 *
 * This exists because the tile listener's entire error handling used to be
 * `runCatching { server.start() }.onFailure { field = null }`. A bind failure therefore left no
 * log, no state, and nothing the UI could show: the app simply served no tiles and looked
 * identical to an app that had no tiles to serve. That is the same invisible-failure shape issue
 * #13 was about, where a GPS registration failure was swallowed and never retried.
 *
 * Generic over the listener type so the class is constructible in a plain JVM test with no Android
 * context and no real socket: the regression for a failed bind is a lambda that throws.
 *
 * [ensure] and [closeCurrent] are serialised on one lock. Two callers reaching `ensure` at the same
 * moment would otherwise each run the check-then-open sequence and each bind a socket, while only
 * one handle could be stored — the other listener would keep accepting connections with nothing
 * left to close it.
 */
class LoopbackServerLauncher<S>(
    private val open: () -> S,
    private val close: (S) -> Unit,
    private val portOf: (S) -> Int,
    private val onDiagnostic: (String) -> Unit = {},
) {

    sealed interface State {
        /** Nothing attempted yet. */
        data object Idle : State

        /** Listener is up on [port]. */
        data class Running(val port: Int) : State

        /** The last attempt failed. A later [ensure] may still succeed. */
        data class Failed(val reason: String) : State
    }

    private val lock = Any()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var handle: S? = null

    /** The live listener, or null when there is none. Does not start anything. */
    val current: S? get() = handle

    /**
     * Return the live listener, starting one if necessary. Null means the listener is not
     * available and [state] says why.
     *
     * A previous failure does not latch. A bind can fail on a transient port conflict and succeed
     * on the next call, so refusing to retry would turn a momentary collision into a permanently
     * dead proxy.
     */
    fun ensure(): S? = synchronized(lock) {
        handle?.let { return it }

        val started = try {
            open()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            noteFailure("listener failed to start", t)
            return null
        }

        // A successful open whose port lookup throws must not orphan the socket that is already
        // bound: the handle is not stored yet, so nothing else would ever close this listener.
        val port = try {
            portOf(started)
        } catch (t: Throwable) {
            closeQuietly(started)
            if (t is CancellationException) throw t
            noteFailure("listener started but its port could not be read", t)
            return null
        }

        handle = started
        _state.value = State.Running(port)
        // Diagnostics run after the transition and cannot throw into it. A log sink that fails is
        // not allowed to turn a live listener into a null return.
        diagnose("loopback tile listener started on port $port")
        started
    }

    /**
     * Close the current listener and return to [State.Idle] so a later [ensure] opens a fresh one.
     * The handle is dropped before [close] runs, so a close that throws cannot leave a listener
     * that is still reachable through this launcher but never closed again.
     */
    fun closeCurrent(): State = synchronized(lock) {
        val existing = handle ?: return _state.value
        handle = null
        val next = try {
            close(existing)
            State.Idle
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            State.Failed("close failed: ${reasonOf(t)}")
        }
        _state.value = next
        diagnose(
            if (next is State.Idle) "loopback tile listener closed"
            else "loopback tile listener close failed: ${(next as State.Failed).reason}",
        )
        next
    }

    private fun noteFailure(context: String, t: Throwable) {
        handle = null
        val reason = reasonOf(t)
        _state.value = State.Failed(reason)
        diagnose("loopback tile $context: $reason")
    }

    private fun closeQuietly(candidate: S) {
        runCatching { close(candidate) }
    }

    /**
     * A diagnostic sink is an observer, not part of the lifecycle contract. If it throws, the
     * listener state must be exactly what it was before the log line.
     */
    private fun diagnose(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private fun reasonOf(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
