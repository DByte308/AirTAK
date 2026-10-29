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
    fun ensure(): S? {
        handle?.let { return it }
        return try {
            val started = open()
            handle = started
            val port = portOf(started)
            _state.value = State.Running(port)
            onDiagnostic("loopback tile listener started on port $port")
            started
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            handle = null
            val reason = t.message ?: t.javaClass.simpleName
            _state.value = State.Failed(reason)
            onDiagnostic("loopback tile listener failed to start: $reason")
            null
        }
    }

    /**
     * Close the current listener and return to [State.Idle] so a later [ensure] opens a fresh one.
     * A close failure is reported but does not clear the failure state of a later start attempt.
     */
    fun closeCurrent(): State {
        val existing = handle ?: return _state.value
        handle = null
        return try {
            close(existing)
            _state.value = State.Idle
            onDiagnostic("loopback tile listener closed")
            State.Idle
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val reason = t.message ?: t.javaClass.simpleName
            _state.value = State.Failed("close failed: $reason")
            onDiagnostic("loopback tile listener close failed: $reason")
            State.Failed("close failed: $reason")
        }
    }
}
