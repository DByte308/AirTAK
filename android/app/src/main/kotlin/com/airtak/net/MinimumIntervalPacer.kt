package com.airtak.net

// Enforces a minimum gap between the start of consecutive outbound requests.
//
// One instance is one budget. That is the whole point: the budget has to be shared by every
// download job and every concurrent search, otherwise two parallel jobs each pacing themselves at
// 1 req/s still produce 2 req/s. Callers must hold a single instance, not create one per request.
//
// A reserved slot is never given back. If a request is cancelled, deduplicated by a cache hit, or
// fails and is retried, the slot stays spent. Refunding it would let a burst of cancellations or a
// retry loop turn a "1 req/s" budget into an unthrottled burst, which is the opposite of the point.
//
// This is a self-imposed courtesy floor for whichever provider the user configured. It is not a
// claim that any particular provider permits the resulting volume; that judgement stays with the
// user and the provider's terms.

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MinimumIntervalPacer(
    private val minIntervalMs: Long,
    /** Injectable so tests can drive it from a coroutine test scheduler's virtual clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** No request may start before this epoch-millis instant. */
    private var earliestStartMs = 0L

    /**
     * Wait until a slot is available, then reserve it.
     *
     * Cancellation-safe: if the caller is cancelled while waiting, no slot was taken and nothing
     * needs unwinding. [kotlinx.coroutines.CancellationException] propagates untouched.
     */
    suspend fun acquire() {
        val waitMs = mutex.withLock {
            val now = clock()
            val wait = (earliestStartMs - now).coerceAtLeast(0L)
            earliestStartMs = maxOf(now, earliestStartMs) + minIntervalMs
            wait
        }
        if (waitMs > 0L) delay(waitMs)
    }

    /**
     * Push the next allowed start forward, used for a server's Retry-After or our own retry
     * backoff. Takes effect even when it is shorter than the configured interval, because the
     * interval still applies on top.
     */
    suspend fun penalize(delayMs: Long) {
        if (delayMs <= 0L) return
        mutex.withLock {
            earliestStartMs = maxOf(earliestStartMs, clock() + delayMs)
        }
    }

    /** Milliseconds until the next slot, for diagnostics and tests. Does not reserve. */
    suspend fun pendingDelayMs(): Long = mutex.withLock {
        (earliestStartMs - clock()).coerceAtLeast(0L)
    }
}

/**
 * Parse an HTTP Retry-After value: either delta-seconds ("120") or an IMF-fixdate
 * ("Wed, 21 Oct 2015 07:28:00 GMT"). Returns null when absent or unparseable, so a malformed
 * header degrades to our normal pacing instead of either hanging or hammering.
 */
fun parseRetryAfterMs(headerValue: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val raw = headerValue?.trim() ?: return null
    raw.toLongOrNull()?.let { seconds ->
        return (seconds.coerceAtLeast(0L) * 1000L)
    }
    return runCatching {
        val headerInstantMs = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
        (headerInstantMs - nowMs).coerceAtLeast(0L)
    }.getOrNull()
}

/** Convenience for the tile path: 250 ms start spacing, one shared instance per app process. */
fun tilePacer(): MinimumIntervalPacer = MinimumIntervalPacer(TILE_MIN_SPACING_MS)

/** Convenience for the geocoder path: Nominatim asks for at most one request per second. */
fun geocoderPacer(): MinimumIntervalPacer = MinimumIntervalPacer(GEOCODER_MIN_SPACING_MS)

/**
 * Tile start spacing. Chosen as a conservative courtesy floor, not derived from any published
 * provider limit: at 250 ms a 15 000 tile download spends roughly 63 minutes in pacing alone,
 * which is slow enough to be honest to a self-hosted provider and slow enough that the UI has to
 * show progress and offer cancellation, which it does.
 */
const val TILE_MIN_SPACING_MS = 250L

/** Nominatim usage policy: no more than one request per second. */
const val GEOCODER_MIN_SPACING_MS = 1_000L
