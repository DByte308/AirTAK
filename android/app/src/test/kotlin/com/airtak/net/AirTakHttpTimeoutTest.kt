// AirTAK Android — test/kotlin/com/airtak/net/AirTakHttpTimeoutTest.kt
//
// Asserts the shipped client carries explicit time bounds, and that those bounds are what abort a
// stalled provider.
//
// Why the shape of this file is odd: Ktor keeps the installed HttpTimeout plugin's own properties
// private, so a test cannot read the configured values back off the client. Rather than reach for
// reflection, the declared values are asserted here and the *installation* is proven behaviourally
// against a real stalled socket — a client with a short socket timeout aborts, and the same client
// with the phases unset does not. Those two together show the configuration is both declared and
// actually doing something, which is more than a reflection assertion would prove.
//
// The virtual-time policy tests in RemoteTileFetcherTest deliberately construct clients with the
// phases unset, because `runTest` skips the coroutine clock forward and a real deadline would fire
// before any assertion ran. Without this file that necessary escape would leave production
// configuration unasserted.

package com.airtak.net

import com.airtak.config.constants.NET_CONNECT_TIMEOUT_MS
import com.airtak.config.constants.NET_REQUEST_TIMEOUT_MS
import com.airtak.config.constants.NET_SOCKET_TIMEOUT_MS
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class AirTakHttpTimeoutTest {

    /** Accepts the connection, reads the request, then stays silent. */
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

    // ── declared values ─────────────────────────────────

    @Test
    fun productionDefaultsAreTheDeclaredConstants() {
        val t = AirTakHttp.DEFAULT_TIMEOUTS
        assertEquals(NET_CONNECT_TIMEOUT_MS, t.connectMs)
        assertEquals(NET_SOCKET_TIMEOUT_MS, t.socketMs)
        assertEquals(NET_REQUEST_TIMEOUT_MS, t.requestMs)
    }

    @Test
    fun noPhaseIsLeftToAnEngineDefault() {
        // The point of declaring these is that nothing falls back to an implicit per-engine default
        // that is not written down anywhere in this tree.
        val t = AirTakHttp.DEFAULT_TIMEOUTS
        assertNotNull(t.connectMs, "connect phase unset")
        assertNotNull(t.socketMs, "read/socket phase unset")
        assertNotNull(t.requestMs, "whole-request phase unset")
    }

    @Test
    fun requestBoundIsNotShorterThanThePhasesItContains() {
        // A whole-request bound shorter than connect or read would abort healthy requests that are
        // simply using their per-phase allowance.
        val t = AirTakHttp.DEFAULT_TIMEOUTS
        assertTrue(t.requestMs!! >= t.connectMs!!, "request ${t.requestMs} < connect ${t.connectMs}")
        assertTrue(t.requestMs >= t.socketMs!!, "request ${t.requestMs} < read ${t.socketMs}")
    }

    // ── actually installed ──────────────────────────────

    @Test
    fun aConfiguredSocketTimeoutIsWhatAbortsAStalledProvider() = runBlocking {
        val server = stalledProvider()
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = 300,
            requestTimeoutMs = null,
        )
        val fetcher = RemoteTileFetcher(RequestBudgets(tileSpacingMs = 0L), client, totalTimeoutMs = null)
        try {
            val started = System.nanoTime()
            val result = fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(result is RemoteTileFetcher.Result.Failed, "expected Failed, got $result")
            assertTrue(elapsedMs < 4_000, "took ${elapsedMs}ms — the 300ms read bound did not bind")
        } finally {
            client.close(); server.close()
        }
    }

    @Test
    fun theSameStallDoesNotAbortWhenThePhasesAreUnset() = runBlocking {
        // Control for the test above: with nothing configured, the same silent provider is still
        // waiting. So the abort in the previous test came from this factory's configuration and not
        // from the engine, the loopback address, or the test harness giving up.
        val server = stalledProvider()
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            followRedirects = false,
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        val fetcher = RemoteTileFetcher(RequestBudgets(tileSpacingMs = 0L), client, totalTimeoutMs = null)
        try {
            val finished = withTimeoutOrNull(1_500) {
                fetcher.fetch(template(server.localPort), 3, 4, 5, 14)
            }
            assertTrue(finished == null, "unset phases must not abort the stall, but it returned $finished")
        } finally {
            client.close(); server.close()
        }
    }

    @Test
    fun unsetPhasesStayUnsetRatherThanBecomingZero() {
        // Ktor rejects a zero timeout, so substituting 0 for "no timeout" would throw at client
        // construction. The previous test only runs because null really means unset.
        val client = AirTakHttp.newClient(
            engine = CIO.create(),
            connectTimeoutMs = null,
            socketTimeoutMs = null,
            requestTimeoutMs = null,
        )
        assertNotNull(client, "a client with every phase unset must still construct")
        client.close()
    }
}
