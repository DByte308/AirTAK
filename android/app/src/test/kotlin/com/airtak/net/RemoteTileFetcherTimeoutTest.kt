// AirTAK Android — test/kotlin/com/airtak/net/RemoteTileFetcherTimeoutTest.kt
//
// Proves the fetch is time-bounded against a provider that really stalls.
//
// Why this needs a real socket and a real engine: MockEngine materialises the response content
// before the handler returns, so there is no such thing as a MockEngine response that arrives
// slowly. A MockEngine-only "timeout test" would assert that a timeout is configured, not that a
// stalled provider actually stops costing the caller time. So this file uses the CIO engine against
// a localhost ServerSocket that accepts the connection, reads the request, and then says nothing.
//
// Scope: 127.0.0.1 only. Nothing here contacts an external host, and the shipped client still uses
// the Android engine — CIO appears in tests alone.

package com.airtak.net

import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

class RemoteTileFetcherTimeoutTest {

    private val budgets = RequestBudgets(tileSpacingMs = 0L)

    /**
     * A provider that accepts the connection and reads the request, then never writes a response.
     * This is the failure a timeout exists for: the connect succeeds, so nothing before the read
     * can notice, and without a bound the caller waits on a socket that will never produce.
     */
    private fun stalledProvider(): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    runCatching {
                        socket.getInputStream().readNBytes(4096)
                        // Hold the connection open and stay silent. No status line, no headers.
                        while (!socket.isClosed) { Thread.sleep(50) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server to thread
    }

    private fun template(port: Int) = "http://127.0.0.1:$port/{z}/{x}/{y}.png"

    @Test
    fun stalledProviderStopsCostingTimeAtTheRequestTimeout() = runBlocking {
        val (server, _) = stalledProvider()
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = 1_000,
            socketTimeoutMs = 500,
            requestTimeoutMs = 700,
        )
        val fetcher = RemoteTileFetcher(budgets, client, totalTimeoutMs = 10_000)
        try {
            val started = System.nanoTime()
            val result = fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(result is RemoteTileFetcher.Result.Failed, "expected Failed, got $result")
            val reason = (result as RemoteTileFetcher.Result.Failed).reason
            assertTrue(
                reason.contains("700") || reason.contains("timeout", ignoreCase = true) ||
                    reason.contains("timed out", ignoreCase = true),
                "the reason must say the provider timed out, not just that it failed: $reason",
            )
            // The bound is the point: 700 ms, not the engine's minute-scale implicit default.
            assertTrue(elapsedMs < 4_000, "took ${elapsedMs}ms — the request timeout did not bind")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun outerFetchTimeoutIsTheBackstopWhenThePluginBoundsAreDisabled() = runBlocking {
        val (server, _) = stalledProvider()
        // null leaves each plugin phase unset, so only the caller's own total bound remains.
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        val fetcher = RemoteTileFetcher(budgets, client, totalTimeoutMs = 900)
        try {
            val started = System.nanoTime()
            val result = fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(result is RemoteTileFetcher.Result.Failed, "expected Failed, got $result")
            val reason = (result as RemoteTileFetcher.Result.Failed).reason
            assertTrue(
                reason.contains("timed out after 900ms"),
                "the backstop must identify itself and its own bound: $reason",
            )
            assertTrue(elapsedMs < 4_000, "took ${elapsedMs}ms — the outer bound did not fire")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun aTimeoutIsAFailedFetchAndNotACancellationOfTheCaller() = runBlocking {
        // TimeoutCancellationException extends CancellationException. If the fetch let it escape as
        // cancellation, a single stalled provider would tear down the proxy's coroutine scope and
        // take every other in-flight tile with it.
        val (server, _) = stalledProvider()
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        val fetcher = RemoteTileFetcher(budgets, client, totalTimeoutMs = 600)
        try {
            val outcome = fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            assertTrue(outcome is RemoteTileFetcher.Result.Failed, "got $outcome")
            // The caller's scope is still alive and usable afterwards.
            val stillWorking = withTimeoutOrNull(2_000.milliseconds) {
                delay(10)
                "scope alive"
            }
            assertEquals("scope alive", stillWorking)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun genuineCancellationStillPropagatesThroughTheTimeoutWrapper() = runBlocking {
        val (server, _) = stalledProvider()
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        // A total bound far in the future so the only thing that can end this is cancellation.
        val fetcher = RemoteTileFetcher(budgets, client, totalTimeoutMs = 600_000)
        try {
            var caught: Throwable? = null
            val job = launch {
                try {
                    fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
                } catch (e: CancellationException) {
                    caught = e
                }
            }
            delay(300)
            job.cancel()
            job.join()
            assertTrue(
                caught is CancellationException,
                "cancellation must propagate untouched so proxy teardown stops fetches, got $caught",
            )
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun zoomBeyondTheConfiguredCeilingIsRefusedBeforeAnySocket() = runBlocking {
        // fetch() is public and takes its own coordinates, so it must not depend on one particular
        // caller having validated them first.
        val (server, _) = stalledProvider()
        val client = AirTakHttp.newClient(engine = CIO.create(), followRedirects = false)
        val fetcher = RemoteTileFetcher(budgets, client, totalTimeoutMs = 1_000)
        try {
            val r = fetcher.fetch(template(server.localPort), 21, 0, 0, maxZoom = 14)
            assertTrue(r is RemoteTileFetcher.Result.Refused, "z=21 against maxZoom=14 got $r")
            assertTrue((r as RemoteTileFetcher.Result.Refused).reason.contains("21"))
        } finally {
            client.close()
            server.close()
        }
    }
}

/**
 * Whose timeout is it? The fetch translates TimeoutCancellationException into a Failed result, and
 * a caller's own outer `withTimeout` raises the same exception type. Swallowing that turns a parent
 * that is going away into an ordinary failure the caller keeps waiting on, and its scope never
 * observes the cancellation. These pin the two cases apart.
 */
class RemoteTileFetcherCancellationOwnershipTest {

    private fun stalledProvider(): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    runCatching {
                        socket.getInputStream().readNBytes(4096)
                        while (!socket.isClosed) { Thread.sleep(50) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun template(port: Int) = "http://127.0.0.1:$port/{z}/{x}/{y}.png"

    private fun fetcher(server: ServerSocket, ownTimeout: Long?): RemoteTileFetcher {
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        return RemoteTileFetcher(
            RequestBudgets(tileSpacingMs = 0L),
            client,
            totalTimeoutMs = ownTimeout,
        )
    }

    @Test
    fun outerCallerTimeoutPropagatesRatherThanBecomingAFailedResult() = runBlocking {
        val server = stalledProvider()
        // The fetch would happily wait 30s; the caller gave up after 300ms. The caller's verdict has
        // to win. If the fetch translated this into Result.Failed, the call would return normally,
        // reachedAfterTimeout would be true, and the parent would keep waiting on a fetch it has
        // already abandoned.
        val fetcher = fetcher(server, ownTimeout = 30_000)
        try {
            var propagated: Throwable? = null
            var reachedAfterTimeout = false
            try {
                withTimeout(300.milliseconds) {
                    fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
                }
                reachedAfterTimeout = true
            } catch (e: CancellationException) {
                propagated = e
            }
            assertTrue(
                propagated is TimeoutCancellationException,
                "the caller's timeout must propagate as cancellation, got $propagated",
            )
            assertTrue(!reachedAfterTimeout, "a caller timeout must not be converted into a result")
        } finally {
            server.close()
        }
    }

    /**
     * The reproduction, and the reason the CIO test above is not enough on its own.
     *
     * With a real engine the outer cancellation surfaces as a JobCancellationException, which is a
     * CancellationException but not a TimeoutCancellationException, so the type check rethrows it and
     * the flaw stays hidden. A suspending MockEngine handler rethrows the cancelling job's own cause,
     * which for an outer `withTimeout` IS a TimeoutCancellationException — and that is the case the
     * fetch must not translate into a Failed result. Without the ensureActive() guard this test
     * fails: the call returns normally as Failed and the caller keeps waiting on a fetch that was
     * already abandoned.
     */
    @Test
    fun outerCallerTimeoutSurvivesASuspendingEngineToo() = runBlocking {
        val never = MockEngine {
            kotlinx.coroutines.awaitCancellation()
            respond(ByteArray(1), HttpStatusCode.OK, headersOf())
        }
        val client = AirTakHttp.newClient(
            engine = never,
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        val fetcher = RemoteTileFetcher(
            RequestBudgets(tileSpacingMs = 0L),
            client,
            totalTimeoutMs = 30_000,
        )
        var propagated: Throwable? = null
        var reachedAfterTimeout = false
        try {
            withTimeout(250.milliseconds) {
                fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
            }
            reachedAfterTimeout = true
        } catch (e: CancellationException) {
            propagated = e
        }
        assertTrue(
            propagated is TimeoutCancellationException,
            "the caller's timeout must propagate, got $propagated",
        )
        assertTrue(
            !reachedAfterTimeout,
            "an outer caller timeout must never be translated into Result.Failed",
        )
        client.close()
    }

    @Test
    fun ownTimeoutIsAFailedResultAndTheCallerStaysActive() = runBlocking {
        val server = stalledProvider()
        val fetcher = fetcher(server, ownTimeout = 300)
        try {
            val result = fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            assertTrue(result is RemoteTileFetcher.Result.Failed, "got $result")
            val alive = withTimeoutOrNull(2_000.milliseconds) {
                delay(10)
                "caller active"
            }
            assertEquals("caller active", alive)
        } finally {
            server.close()
        }
    }
}

/**
 * An invalid request must be rejected before the pacing budget is touched. The pacer reserves real
 * elapsed time per provider, so a request that was never going to be issued would still push back
 * every later request to that provider.
 */
class RemoteTileFetcherCoordinateGateTest {

    private fun template() = "https://tiles.example/{z}/{x}/{y}.png"

    private fun fetcher(budgets: RequestBudgets): RemoteTileFetcher {
        val engine = MockEngine { respond(ByteArray(1), HttpStatusCode.OK, headersOf()) }
        return RemoteTileFetcher(
            budgets,
            AirTakHttp.newClient(
                engine = engine,
                followRedirects = false,
                connectTimeoutMs = null,
                socketTimeoutMs = null,
                requestTimeoutMs = null,
            ),
            totalTimeoutMs = null,
        )
    }

    @Test
    fun rejectedCoordinatesDoNotConsumeTheProviderBudget() = runTest {
        val budgets = RequestBudgets(tileSpacingMs = 5_000L)
        val fetcher = fetcher(budgets)
        val r = fetcher.fetch(template(), 99, 0, 0, maxZoom = 14)
        assertTrue(r is RemoteTileFetcher.Result.Refused, "got $r")
        assertEquals(
            0L,
            budgets.tileBudgetFor("tiles.example").pendingDelayMs(),
            "a refused request must not have spent any of the provider's pacing interval",
        )
    }

    @Test
    fun outOfRangeTileCoordinatesAreRefused() = runTest {
        val fetcher = fetcher(RequestBudgets(tileSpacingMs = 0L))
        // zoom 3 has valid tiles 0..7 only.
        listOf(Triple(3, 8, 0), Triple(3, 0, 8), Triple(3, -1, 0), Triple(3, 0, -1)).forEach { (z, x, y) ->
            val r = fetcher.fetch(template(), z, x, y, maxZoom = 14)
            assertTrue(r is RemoteTileFetcher.Result.Refused, "z=$z x=$x y=$y got $r")
        }
        assertTrue(
            fetcher.fetch(template(), 3, 7, 7, maxZoom = 14) !is RemoteTileFetcher.Result.Refused,
            "the highest valid tile at zoom 3 must still be accepted",
        )
    }

    @Test
    fun hugeZoomIsRefusedWithoutOverflowingTheCoordinateBound() = runTest {
        val fetcher = fetcher(RequestBudgets(tileSpacingMs = 0L))
        // 1L shl z overflows for z >= 63, and a negative span would make every x/y look in range.
        listOf(31, 40, 62, 63, 100, Int.MAX_VALUE).forEach { z ->
            val r = fetcher.fetch(template(), z, 0, 0, maxZoom = Int.MAX_VALUE)
            assertTrue(r is RemoteTileFetcher.Result.Refused, "z=$z must be refused, got $r")
        }
    }

    @Test
    fun negativeMaxZoomIsRefused() = runTest {
        val fetcher = fetcher(RequestBudgets(tileSpacingMs = 0L))
        assertTrue(fetcher.fetch(template(), 3, 4, 5, maxZoom = -1) is RemoteTileFetcher.Result.Refused)
    }

    @Test
    fun malformedTemplateIsRejectedWithoutSpendingTheProviderInterval() = runTest {
        val budgets = RequestBudgets(tileSpacingMs = 5_000L)
        val fetcher = fetcher(budgets)
        // A stray placeholder fails the URL build, which must happen before the pacing budget is
        // touched: a rejected request must not delay every later request to that provider.
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}/{w}.png", 3, 4, 5, maxZoom = 14)
        assertTrue(r is RemoteTileFetcher.Result.Refused, "got $r")
        assertEquals(
            0L,
            budgets.tileBudgetFor("tiles.example").pendingDelayMs(),
            "a malformed template must not have spent any of the provider's pacing interval",
        )
    }
}
