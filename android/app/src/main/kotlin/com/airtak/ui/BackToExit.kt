// AirTAK Android — ui/BackToExit.kt
//
// Decides what a system-back press at the root of the app should do.
//
// AirTAK had no back handling at all: back on the map finished the activity while the BLE
// foreground service kept the process, the persistent notification and the reconnect loop alive,
// and START_STICKY brought that service back after the system stopped it. The app therefore never
// looked closed. This class owns only the "press again to close" decision; MainActivity owns the
// actual teardown, because that needs the link, the service and the activity.
//
// Plain class with an injected clock so the window behaviour is testable without an activity, the
// same shape as net/MinimumIntervalPacer.

package com.airtak.ui

import com.airtak.config.constants.UI_BACK_TO_EXIT_PRESSES
import com.airtak.config.constants.UI_BACK_TO_EXIT_WINDOW_MS

/** What the caller must do about a back press that no screen consumed. */
sealed interface ExitDecision {
    /** Keep the app open and tell the user how to close it. [pressesLeft] more presses will. */
    data class Confirm(val pressesLeft: Int) : ExitDecision

    /** This press completed the sequence: close the app now. */
    data object Exit : ExitDecision
}

/**
 * Counts back presses inside one window. The first press asks for confirmation, the press that
 * completes [pressesRequired] closes the app, and a gap longer than [windowMs] starts the count
 * over so an old press can never combine with a new one into an unintended exit.
 */
class BackToExit(
    private val pressesRequired: Int = UI_BACK_TO_EXIT_PRESSES,
    private val windowMs: Long = UI_BACK_TO_EXIT_WINDOW_MS,
    private val nowMs: () -> Long,
) {
    init {
        require(pressesRequired >= 1) { "pressesRequired must be at least 1, was $pressesRequired" }
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
    }

    private var presses = 0
    private var windowStartMs = 0L

    /** Record one back press and report what should happen because of it. */
    fun onBackPressed(): ExitDecision {
        val now = nowMs()
        val elapsed = now - windowStartMs
        // elapsed < 0 only happens if a caller injects a clock that can jump backwards. Treated as
        // a new window on purpose: the alternative failure is closing the app on one press.
        val freshWindow = presses == 0 || elapsed >= windowMs || elapsed < 0
        presses = if (freshWindow) 1 else presses + 1
        if (freshWindow) windowStartMs = now

        if (presses >= pressesRequired) {
            // Consumed: the next press begins a new sequence rather than closing a second time.
            presses = 0
            return ExitDecision.Exit
        }
        return ExitDecision.Confirm(pressesRequired - presses)
    }

    /** Forget an unfinished sequence, e.g. when the app came back to the foreground. */
    fun reset() {
        presses = 0
    }
}
