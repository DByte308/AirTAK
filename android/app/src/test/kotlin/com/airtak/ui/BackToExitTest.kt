package com.airtak.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The clock is a variable here, so the window is tested by moving it rather than by sleeping.
 * The regression these guard is the old behaviour: one back press at the root finished the
 * activity while the BLE foreground service kept the app alive, so the app looked like it never
 * closed and a single press was also far too easy to hit by accident.
 */
class BackToExitTest {

    private class Clock(var now: Long = 0L) {
        fun millis(): Long = now
    }

    @Test
    fun firstPressAsksForConfirmationInsteadOfClosing() {
        val clock = Clock()
        val back = BackToExit(nowMs = clock::millis)

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }

    @Test
    fun secondPressInsideTheWindowClosesTheApp() {
        val clock = Clock()
        val back = BackToExit(nowMs = clock::millis)

        back.onBackPressed()
        clock.now += 400L

        assertEquals(ExitDecision.Exit, back.onBackPressed())
    }

    @Test
    fun pressAfterTheWindowExpiredStartsOverInsteadOfClosing() {
        val clock = Clock()
        val back = BackToExit(windowMs = 1_000L, nowMs = clock::millis)

        back.onBackPressed()
        clock.now += 1_001L

        // The stale press must not combine with this one: closing on the second press after a
        // pause is how an app closes itself while the user was reading the map.
        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }

    @Test
    fun pressExactlyAtTheWindowEdgeCountsAsANewSequence() {
        val clock = Clock()
        val back = BackToExit(windowMs = 1_000L, nowMs = clock::millis)

        back.onBackPressed()
        clock.now += 1_000L

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }

    @Test
    fun pressJustInsideTheWindowStillCloses() {
        val clock = Clock()
        val back = BackToExit(windowMs = 1_000L, nowMs = clock::millis)

        back.onBackPressed()
        clock.now += 999L

        assertEquals(ExitDecision.Exit, back.onBackPressed())
    }

    @Test
    fun closingConsumesTheSequenceSoTheNextPressAsksAgain() {
        val clock = Clock()
        val back = BackToExit(nowMs = clock::millis)

        back.onBackPressed()
        assertEquals(ExitDecision.Exit, back.onBackPressed())
        clock.now += 100L

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }

    @Test
    fun resetDropsAnUnfinishedSequence() {
        val clock = Clock()
        val back = BackToExit(nowMs = clock::millis)

        back.onBackPressed()
        back.reset()

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }

    @Test
    fun threePressSettingCountsDownInItsOwnWords() {
        val clock = Clock()
        val back = BackToExit(pressesRequired = 3, nowMs = clock::millis)

        assertEquals(ExitDecision.Confirm(pressesLeft = 2), back.onBackPressed())
        clock.now += 100L
        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
        clock.now += 100L
        assertEquals(ExitDecision.Exit, back.onBackPressed())
    }

    @Test
    fun singlePressSettingClosesImmediately() {
        val clock = Clock()
        val back = BackToExit(pressesRequired = 1, nowMs = clock::millis)

        assertEquals(ExitDecision.Exit, back.onBackPressed())
    }

    @Test
    fun clockGoingBackwardsCannotSkipTheConfirmation() {
        // Monotonic clocks do not go backwards, but a handler that trusted a wall clock could,
        // and the failure mode would be closing the app on the first press.
        val clock = Clock(now = 10_000L)
        val back = BackToExit(windowMs = 1_000L, nowMs = clock::millis)

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
        clock.now = 5_000L

        assertEquals(ExitDecision.Confirm(pressesLeft = 1), back.onBackPressed())
    }
}
