package com.airtak.net

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Runs on the coroutine test scheduler, so the "time" the pacer sees is virtual time and these
 * tests finish instantly rather than really sleeping.
 */
class MinimumIntervalPacerTest {

    @Test
    fun spacesConsecutiveStartsByTheInterval() = runTest {
        val pacer = MinimumIntervalPacer(250L) { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        repeat(4) {
            pacer.acquire()
            starts += testScheduler.currentTime
        }
        assertEquals(listOf(0L, 250L, 500L, 750L), starts)
    }

    @Test
    fun concurrentAcquiresShareOneBudgetInsteadOfMultiplyingRate() = runTest {
        // The regression this guards: a pacer created per job would let three parallel jobs each
        // go at 1 req/s and produce 3 req/s. One shared instance must serialise them.
        val pacer = MinimumIntervalPacer(1_000L) { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        coroutineScope {
            repeat(3) {
                launch {
                    pacer.acquire()
                    starts += testScheduler.currentTime
                }
            }
        }
        assertEquals(listOf(0L, 1_000L, 2_000L), starts.sorted())
    }

    @Test
    fun penalizePushesTheNextStartForward() = runTest {
        val pacer = MinimumIntervalPacer(100L) { testScheduler.currentTime }
        pacer.acquire()
        pacer.penalize(5_000L)
        pacer.acquire()
        assertEquals(5_000L, testScheduler.currentTime, "Retry-After must dominate the interval")
    }

    @Test
    fun cancelledWaitDoesNotRefundOrBankASlot() = runTest {
        // Sol's constraint: cancellation and dedupe must not hand slots back, or a burst of
        // cancellations turns a fixed budget into an unthrottled burst.
        val pacer = MinimumIntervalPacer(1_000L) { testScheduler.currentTime }
        pacer.acquire()                                  // t=0, next allowed 1000
        val doomed = launch { pacer.acquire() }          // will reserve 2000, then wait
        testScheduler.runCurrent()                        // let it actually reserve before cancelling
        doomed.cancelAndJoin()
        pacer.acquire()                                  // must still land on 2000
        assertEquals(2_000L, testScheduler.currentTime, "a cancelled acquire must not refund its slot")
    }

    @Test
    fun pendingDelayReportsRemainingWaitWithoutReserving() = runTest {
        val pacer = MinimumIntervalPacer(1_000L) { testScheduler.currentTime }
        pacer.acquire()
        assertEquals(1_000L, pacer.pendingDelayMs())
        assertEquals(1_000L, pacer.pendingDelayMs(), "reading must not consume the slot again")
    }

    @Test
    fun parsesDeltaSecondsRetryAfter() {
        assertEquals(120_000L, parseRetryAfterMs("120"))
        assertEquals(0L, parseRetryAfterMs("0"))
        assertEquals(0L, parseRetryAfterMs("-5"), "negative must clamp rather than go backwards")
    }

    @Test
    fun parsesHttpDateRetryAfter() {
        val now = 1_445_412_000_000L // 2015-10-21T07:20:00Z
        val parsed = parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", nowMs = now)
        assertEquals(480_000L, parsed)
    }

    @Test
    fun malformedRetryAfterDegradesToNull() {
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs("soon"))
        assertNull(parseRetryAfterMs(""))
    }

    @Test
    fun tileAndGeocoderBudgetsAreIndependent() {
        // Operator requirement: tile pacing must not share the geocoder's 1 req/s gate.
        assertTrue(TILE_MIN_SPACING_MS < GEOCODER_MIN_SPACING_MS)
        assertEquals(250L, TILE_MIN_SPACING_MS)
        assertEquals(1_000L, GEOCODER_MIN_SPACING_MS)
    }
}
