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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
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
