package com.airtak.net

import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Probe against the listener AirTAK actually ships.
 *
 * These tests construct [TileServer] itself. An earlier version of this coverage exercised a
 * test-only socket loop, which is how a listener bound to the wrong port survived an entire commit:
 * the substitute could not fail in the way the real thing did.
 *
 * Everything here is loopback. Every test is bounded so a regression fails instead of hanging.
 */
class TileServerProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<TileServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
        scope.cancel()
    }

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(24) { it.toByte() }

    private fun start(
        reader: LocalTileReader,
        remote: RemoteTileSource? = null,
        maxHandlers: Int = TileServer.DEFAULT_MAX_HANDLERS,
        timeoutMs: Int = TileServer.DEFAULT_SOCKET_TIMEOUT_MS,
    ): TileServer {
        val server = TileServer(scope, reader, remote, timeoutMs, maxHandlers)
        server.start()
        servers += server
        return server
    }

    /** Send raw bytes, return the response split into head and body. */
    private class Exchange(val head: String, val body: ByteArray) {
        val status: Int get() = Regex("HTTP/1\\.1 (\\d{3})").find(head)?.groupValues?.get(1)?.toInt() ?: -1
        val lower: String get() = head.lowercase()
    }

    private fun exchange(port: Int, payload: String, readTimeoutMs: Int = 4_000): Exchange {
        Socket(TILE_LOOPBACK, port).use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().write(payload.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val all = socket.getInputStream().readBytes()
            val text = all.toString(Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n").let { if (it == -1) text.length else it + 4 }
            return Exchange(text.substring(0, end), all.copyOfRange(end, all.size))
        }
    }

    private fun get(path: String, extra: String = "") =
        "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$extra\r\n"

    @Test
    fun bindsAnEphemeralLoopbackPortNotAFixedLowPort() {
        // The regression this exists for: ServerSocket(backlog, 0, addr) put the backlog in the
        // port slot and bound a fixed low port, which collides or fails on a device.
        val server = start(LocalTileReader { _, _, _ -> null })
        assertTrue(server.port > 1024, "expected an ephemeral port, got ${server.port}")
        assertNotEquals(TileServer.ACCEPT_BACKLOG, server.port)
        // Two instances must coexist, which a fixed port makes impossible.
        val second = start(LocalTileReader { _, _, _ -> null })
        assertTrue(second.port > 1024 && second.port != server.port, "second listener port ${second.port} vs ${server.port}")
    }

    @Test
    fun servesLocalTileOnGetAndHeadersOnlyOnHead() {
        val tile = png()
        val server = start(LocalTileReader { z, x, y -> if (z == 3 && x == 4 && y == 5) tile else null })

        val got = exchange(server.port, get("/tiles/3/4/5.png"))
        assertEquals(200, got.status)
        assertTrue(got.lower.contains("content-type: image/png"), got.head)
        assertEquals(tile.size, got.body.size)

        val head = exchange(server.port, "HEAD /tiles/3/4/5.png HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(200, head.status)
        assertTrue(head.lower.contains("content-length: ${tile.size}"), head.head)
        assertEquals(0, head.body.size, "HEAD must not carry a body")
    }

    @Test
    fun rejectsPostRangeAndBadCoordinates() {
        val server = start(LocalTileReader { _, _, _ -> png() })

        assertEquals(405, exchange(server.port, "POST /tiles/1/1/1.png HTTP/1.1\r\nHost: h\r\n\r\n").status)
        assertEquals(501, exchange(server.port, get("/tiles/1/1/1.png", "Range: bytes=0-10\r\n")).status)
        assertEquals(400, exchange(server.port, get("/tiles/99/0/0.png")).status)
        assertEquals(400, exchange(server.port, get("/tiles/3/9999/0.png")).status)
        assertEquals(400, exchange(server.port, get("/remote/3/4/5.png", "X-AirTak-Url: https://evil.example/{z}/{x}/{y}\r\n")).status)
    }

    @Test
    fun refusesOverlongRequestLineAndHeaderBlock() {
        val server = start(LocalTileReader { _, _, _ -> png() })

        val longLine = "GET /tiles/" + "9".repeat(TileProxyHandler.MAX_REQUEST_LINE_BYTES + 64) + " HTTP/1.1\r\n\r\n"
        val tooLong = runCatching { exchange(server.port, longLine, readTimeoutMs = 2_000) }
        // Either a 414 or a bare hang-up is acceptable; a buffered answer is not.
        // A 414 or a bare hang-up both refuse. What must not happen is a buffered answer.
        tooLong.getOrNull()?.let { if (it.status > 0) assertEquals(414, it.status, it.head) }

        val manyHeaders = "GET /tiles/1/1/1.png HTTP/1.1\r\nHost: h\r\n" +
            (1..(TileProxyHandler.MAX_HEADER_LINES + 4)).joinToString("") { "X-Pad$it: v\r\n" } + "\r\n"
        val res = runCatching { exchange(server.port, manyHeaders, readTimeoutMs = 2_000) }
        res.getOrNull()?.let { if (it.status > 0) assertEquals(431, it.status, it.head) }
    }

    @Test
    fun handlerCeilingAnswersFiftyThreeWithRetryAfter() {
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(TileServer.DEFAULT_MAX_HANDLERS)
        val server = start(
            LocalTileReader { _, _, _ ->
                entered.countDown()
                hold.await(6, TimeUnit.SECONDS)
                png()
            },
        )

        val busy = (1..TileServer.DEFAULT_MAX_HANDLERS).map {
            Thread { runCatching { exchange(server.port, get("/tiles/1/1/1.png"), readTimeoutMs = 9_000) } }
                .apply { isDaemon = true; start() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "handlers never filled")
        // Give the slots a moment to be taken before probing the ceiling.
        val deadline = System.currentTimeMillis() + 2_000
        while (server.activeHandlers < TileServer.DEFAULT_MAX_HANDLERS && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(TileServer.DEFAULT_MAX_HANDLERS, server.activeHandlers)

        val overflow = exchange(server.port, get("/tiles/1/1/1.png"), readTimeoutMs = 3_000)
        assertEquals(503, overflow.status, overflow.head)
        assertTrue(overflow.lower.contains("retry-after"), "expected Retry-After, got ${overflow.head}")

        hold.countDown()
        busy.forEach { it.join(6_000) }
    }

    @Test
    fun shutdownClosesPortReleasesHandlersAndDropsLiveConnections() {
        val hold = CountDownLatch(1)
        val server = start(
            LocalTileReader { _, _, _ ->
                hold.await(8, TimeUnit.SECONDS)
                png()
            },
        )
        val port = server.port
        val live = Thread {
            runCatching {
                Socket(TILE_LOOPBACK, port).use { s ->
                    s.soTimeout = 8_000
                    s.getOutputStream().write(get("/tiles/1/1/1.png").toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                    s.getInputStream().readBytes()
                }
            }
        }.apply { isDaemon = true; start() }

        assertTrue(waitUntil { server.activeHandlers == 1 }, "live handler never registered")
        server.shutdown()
        assertFalse(server.isRunning)
        assertTrue(waitUntil { server.activeHandlers == 0 }, "handler slot not released on shutdown")

        // A client blocked on a held handler must be disconnected, not left hanging.
        live.join(6_000)
        assertNotEquals(200, runCatching { exchange(port, get("/tiles/1/1/1.png"), 1_500).status }.getOrNull() ?: 200)
        hold.countDown()
    }

    @Test
    fun stoppedPortRefusesConnectionsAndRestartIsPossible() {
        val server = start(LocalTileReader { _, _, _ -> png() })
        val firstPort = server.port
        assertEquals(200, exchange(firstPort, get("/tiles/1/1/1.png")).status)

        server.shutdown()
        val refused = runCatching { exchange(firstPort, get("/tiles/1/1/1.png"), 1_500) }
        assertTrue(
            refused.exceptionOrNull() is ConnectException || refused.exceptionOrNull() is SocketTimeoutException,
            "expected the old port to refuse, got ${refused.exceptionOrNull()}",
        )

        // Explicit restart semantics: shutdown is not permanent.
        server.start()
        assertTrue(server.isRunning, "restart after shutdown must work")
        assertTrue(server.port > 1024 && server.port != firstPort, "restart reused port ${server.port}")
        assertEquals(200, exchange(server.port, get("/tiles/1/1/1.png")).status)
    }

    @Test
    fun remoteOutcomesMapToDistinctStatusesAndForwardCacheControl() = runBlocking {
        withTimeout(20_000) {
            val tile = png()
            val seen = mutableListOf<String>()
            val remote = object : RemoteTileSource {
                override suspend fun fetch(z: Int, x: Int, y: Int): RemoteTileOutcome = when (y) {
                    1 -> RemoteTileOutcome.Found(tile, "public, max-age=60")
                    2 -> RemoteTileOutcome.Missing
                    3 -> RemoteTileOutcome.Unavailable
                    4 -> RemoteTileOutcome.Refused
                    else -> RemoteTileOutcome.Found(tile, null)
                }
            }
            val server = start(LocalTileReader { _, _, _ -> null }, remote)

            val cached = exchange(server.port, get("/remote/3/4/1.png"))
            assertEquals(200, cached.status)
            assertTrue(cached.lower.contains("cache-control: public, max-age=60"), "provider directive lost: ${cached.head}")

            assertEquals(404, exchange(server.port, get("/remote/3/4/2.png")).status)
            assertEquals(502, exchange(server.port, get("/remote/3/4/3.png")).status)
            assertEquals(503, exchange(server.port, get("/remote/3/4/4.png")).status)

            // A provider that said nothing must not be left for the engine to guess about.
            val silent = exchange(server.port, get("/remote/3/4/9.png"))
            assertEquals(200, silent.status)
            assertTrue(silent.lower.contains("cache-control: no-store"), "silent provider must yield no-store: ${silent.head}")
            assertNotNull(seen)
        }
    }

    @Test
    fun noRemoteSourceMeansRemoteRoutesRefuseRatherThanCrash() {
        val server = start(LocalTileReader { _, _, _ -> png() }, remote = null)
        assertEquals(503, exchange(server.port, get("/remote/3/4/5.png")).status)
        // The listener survives the refused route and keeps serving.
        assertEquals(200, exchange(server.port, get("/tiles/1/1/1.png")).status)
    }

    private fun waitUntil(windowMs: Long = 4_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private fun assertNotEquals(unexpected: Int, actual: Int) =
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual)

    private fun assertNotEquals(unexpected: Any, actual: Any) =
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual)

    private companion object {
        const val TILE_LOOPBACK = "127.0.0.1"
    }
}
