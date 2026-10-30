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

    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { out ->
            out.write(data)
            out.finish()
            out.flush()
        }
        return bos.toByteArray()
    }

    /** A tiny stand-in for a real MVT protobuf tile. Contains the `place` layer name. */
    private fun mvt(): ByteArray = byteArrayOf(0x1A, 0x0E) + "AirTAK-fixture".toByteArray()

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
    fun servesVectorTileGunzippedWithVectorContentType() {
        // A vector tile is stored gzip-compressed protobuf (tilemaker "compress": "gzip"). MapLibre
        // expects a raw protobuf stream, so the listener must inflate it and serve the vector MIME
        // type (issue #7). The raw fixture's gzip bytes differ from the decoded bytes, which proves
        // the decoder ran rather than the body passing through.
        val vector = mvt()
        val server = start(LocalTileReader { z, x, y ->
            if (z == 3 && x == 4 && y == 5) gzip(vector) else null
        })

        val got = exchange(server.port, get("/tiles/3/4/5.pbf"))
        assertEquals(200, got.status)
        assertTrue(
            got.lower.contains("content-type: application/vnd.mapbox-vector-tile"),
            got.head,
        )
        assertEquals(vector.size, got.body.size, "body must be the decoded protobuf, not gzip")
        assertTrue(got.body.contentEquals(vector), "decoded body must match the original MVT bytes")
        assertTrue(
            !(got.body.size >= 2 && got.body[0] == 0x1F.toByte() && got.body[1] == 0x8B.toByte()),
            "body must not still be gzip-compressed",
        )

        // A HEAD on the vector path carries the vector content type but no body.
        val head = exchange(server.port, "HEAD /tiles/3/4/5.pbf HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(200, head.status)
        assertTrue(head.lower.contains("content-type: application/vnd.mapbox-vector-tile"), head.head)
        assertEquals(0, head.body.size, "HEAD must not carry a body")
    }

    @Test
    fun servesVectorTileRawWhenNotGzipped() {
        // Tolerates an archive that stores protobuf uncompressed rather than refusing it.
        val vector = mvt()
        val server = start(LocalTileReader { z, x, y -> if (z == 2 && x == 1 && y == 1) vector else null })
        val got = exchange(server.port, get("/tiles/2/1/1.pbf"))
        assertEquals(200, got.status)
        assertEquals(vector.size, got.body.size)
        assertTrue(got.body.contentEquals(vector))
    }

    @Test
    fun vectorsMissIs404AndRasterStillWorks() {
        // The reader only holds the raster tile at 1/1/1, so a vector request for a tile the
        // archive does not hold is a 404. The raster path keeps serving with its image content
        // type (the additive guarantee from issue #7: vector support must not change raster paths).
        val raster = png()
        val server = start(LocalTileReader { z, x, y -> if (z == 1 && x == 1 && y == 1) raster else null })
        assertEquals(404, exchange(server.port, get("/tiles/1/0/0.pbf")).status)

        val got = exchange(server.port, get("/tiles/1/1/1.png"))
        assertEquals(200, got.status)
        assertTrue(got.lower.contains("content-type: image/png"), got.head)
    }

    @Test
    fun rejectsPostRangeAndBadCoordinates() {
        val server = start(LocalTileReader { _, _, _ -> png() })

        assertEquals(405, exchange(server.port, "POST /tiles/1/1/1.png HTTP/1.1\r\nHost: h\r\n\r\n").status)
        assertEquals(501, exchange(server.port, get("/tiles/1/1/1.png", "Range: bytes=0-10\r\n")).status)
        val highZoom = exchange(server.port, get("/tiles/99/0/0.png"))
        assertEquals(400, highZoom.status, "raw response was: ${highZoom.head}")
        val wideX = exchange(server.port, get("/tiles/3/9999/0.png"))
        assertEquals(400, wideX.status, "raw response was: ${wideX.head}")
        // A remote route with no provider configured correctly answers 503. Whether a client-supplied
        // URL header influences anything is a separate property, proven in
        // clientSuppliedUrlHeaderCannotChooseTheProvider.
        val smuggle = exchange(server.port, get("/remote/3/4/5.png", "X-AirTak-Url: https://evil.example/{z}/{x}/{y}\r\n"))
        assertEquals(503, smuggle.status, smuggle.head)
    }

    /**
     * The provider is resolved inside the app, never from a request header. Proven by giving the
     * fake provider a call log: the request must reach the fake, and the header's URL must not
     * become the destination.
     */
    @Test
    fun clientSuppliedUrlHeaderCannotChooseTheProvider() {
        val called = mutableListOf<Triple<Int, Int, Int>>()
        val remote = object : RemoteTileSource {
            override suspend fun fetch(z: Int, x: Int, y: Int): RemoteTileOutcome {
                called += Triple(z, x, y)
                return RemoteTileOutcome.Found(png())
            }
        }
        val server = start(LocalTileReader { _, _, _ -> null }, remote)

        val res = exchange(server.port, get("/remote/3/4/5.png", "X-AirTak-Url: https://evil.example/{z}/{x}/{y}\r\n"))
        assertEquals(200, res.status, res.head)
        assertEquals(listOf(Triple(3, 4, 5)), called, "header must not redirect the provider choice")
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
        // Signals when the reader lambda has actually been entered. `activeHandlers == 1` fires at
        // handler registration, which is before the handler reads the request line; under load the
        // handler can still be mid-read when shutdown closes the socket, in which case the read
        // fails and the slot is released — falsely implying the blocked reader could be preempted.
        // Waiting for reader entry makes the shutdown timing deterministic.
        val readerEntered = CountDownLatch(1)
        val server = start(
            LocalTileReader { _, _, _ ->
                readerEntered.countDown()
                hold.await(30, TimeUnit.SECONDS)
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

        assertTrue(readerEntered.await(5, TimeUnit.SECONDS), "reader was never entered")
        assertTrue(waitUntil { server.activeHandlers == 1 }, "live handler never registered")
        // This reader is deliberately non-cooperative: it blocks in a plain call that no socket
        // close can interrupt. shutdown() must still close the port and drop the client, and must
        // report the slot as still held rather than pretend it was freed.
        val report = server.shutdown(drainWaitMs = 500)
        assertFalse(server.isRunning)
        assertTrue(report.portClosed, "port was not closed")
        assertTrue(report.connectionsClosed >= 1, "live connection was not counted as closed")
        assertFalse(report.slotsReleased, "a blocked synchronous reader cannot be preempted")
        assertEquals(1, report.slotsStillHeld)

        // A client blocked on a held handler must be disconnected, not left hanging.
        live.join(6_000)
        // Assert the refusal itself. Substituting a status when the connect fails would collide
        // with the very value being ruled out and fail on success.
        val refused = runCatching { exchange(port, get("/tiles/1/1/1.png"), 1_500) }
        assertTrue(refused.isFailure, "old port still answered after shutdown: ${refused.getOrNull()?.head}")

        // Once the reader returns on its own, the slot is released.
        hold.countDown()
        assertTrue(waitUntil { server.activeHandlers == 0 }, "slot never released after the reader returned")
    }

    @Test
    fun stoppedPortRefusesConnectionsAndRestartIsPossible() {
        val server = start(LocalTileReader { _, _, _ -> png() })
        val firstPort = server.port
        assertEquals(200, exchange(firstPort, get("/tiles/1/1/1.png")).status)

        server.shutdown()
        // After shutdown the old port must stop serving. A closed listener usually refuses the
        // connect (ConnectException) or drains it to the socket timeout (SocketTimeoutException),
        // but a connection accepted just before close can still be served a 200 by a handler that
        // was already running, so a single observe can race. Retry until the port reliably refuses
        // (or a deadline passes); the invariant is that it stops serving, not that the very first
        // connect fails.
        val refusalDeadline = System.currentTimeMillis() + 3_000
        var refused = false
        while (!refused && System.currentTimeMillis() < refusalDeadline) {
            val outcome = runCatching { exchange(firstPort, get("/tiles/1/1/1.png"), 1_500) }
            val ex = outcome.exceptionOrNull()
            val served200 = ex == null && outcome.getOrNull()?.status == 200
            if (ex is ConnectException || ex is SocketTimeoutException || !served200) refused = true
            else Thread.sleep(50)
        }
        assertTrue(refused, "the old port never stopped serving after shutdown")

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
            val silent = exchange(server.port, get("/remote/5/4/9.png"))
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

    /** Sends bytes, half-closes the request direction, and reads whatever response arrives. */
    private fun exchangeHalfClose(port: Int, payload: String, readTimeoutMs: Int = 4_000): Exchange {
        Socket(TILE_LOOPBACK, port).use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().write(payload.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            // No more request bytes, but the response direction stays open. This is what a client
            // that dies mid-request actually does to the stream.
            socket.shutdownOutput()
            val all = socket.getInputStream().readBytes()
            val text = all.toString(Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n").let { if (it == -1) text.length else it + 4 }
            return Exchange(text.substring(0, end), all.copyOfRange(end, all.size))
        }
    }

    private inner class CountingLocalReader : LocalTileReader {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun read(z: Int, x: Int, y: Int): ByteArray? {
            calls += "$z/$x/$y"
            return png()
        }
    }

    private inner class CountingRemoteSource(
        private val outcome: RemoteTileOutcome = RemoteTileOutcome.Missing,
    ) : RemoteTileSource {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun fetch(z: Int, x: Int, y: Int): RemoteTileOutcome {
            calls += "$z/$x/$y"
            return outcome
        }
    }

    @Test
    fun halfCloseAfterRequestLineIsRefusedAndNeverReachesASource() {
        // A complete request line with no header block is not a request. Routing it would consult
        // a tile source for a header-less request the client never finished sending.
        val local = CountingLocalReader()
        val remote = CountingRemoteSource()
        val server = start(local, remote)

        val res = exchangeHalfClose(server.port, "GET /tiles/3/4/5.png HTTP/1.1\r\n")

        assertEquals(400, res.status, "raw response was: ${res.head}")
        assertTrue(local.calls.isEmpty(), "local reader was consulted: ${local.calls}")
        assertTrue(remote.calls.isEmpty(), "remote source was consulted: ${remote.calls}")
    }

    @Test
    fun partialHeaderWithoutNewlineIsRefusedAndNeverReachesASource() {
        // The header line never terminated. Reading EOF-with-data as a finished header is the
        // exact conflation this path used to make.
        val local = CountingLocalReader()
        val remote = CountingRemoteSource()
        val server = start(local, remote)

        val res = exchangeHalfClose(
            server.port,
            "GET /tiles/3/4/5.png HTTP/1.1\r\nHost: 127.0.0.1",
        )

        assertEquals(400, res.status, "raw response was: ${res.head}")
        assertTrue(local.calls.isEmpty(), "local reader was consulted: ${local.calls}")
        assertTrue(remote.calls.isEmpty(), "remote source was consulted: ${remote.calls}")
    }

    @Test
    fun missingFinalBlankLineIsRefusedAndNeverReachesASource() {
        // Every header line is newline-terminated, but the empty line that ends the block is
        // absent. A parser that stops at EOF instead of the terminator accepts this.
        val local = CountingLocalReader()
        val remote = CountingRemoteSource()
        val server = start(local, remote)

        val res = exchangeHalfClose(
            server.port,
            "GET /tiles/3/4/5.png HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: */*\r\n",
        )

        assertEquals(400, res.status, "raw response was: ${res.head}")
        assertTrue(local.calls.isEmpty(), "local reader was consulted: ${local.calls}")
        assertTrue(remote.calls.isEmpty(), "remote source was consulted: ${remote.calls}")
    }

    @Test
    fun completeRequestStillRoutesAfterTheTruncationGuards() {
        // Guards that refuse too much are a regression too: the same server must still serve a
        // well-formed request, otherwise the four-hundred path could pass by refusing everything.
        val local = CountingLocalReader()
        val server = start(local)

        val res = exchange(server.port, get("/tiles/3/4/5.png"))

        assertEquals(200, res.status, "raw response was: ${res.head}")
        assertEquals(listOf("3/4/5"), local.calls)
    }

    @Test
    fun forwardedCacheControlWithCrlfCannotInjectASecondHeader() {
        // Ktor constrains header values on parse, but RemoteTileSource is an interface any caller
        // can implement, so the write path has to hold the line itself.
        val injected = "no-store\r\nX-Injected: pwned\r\nX-Also: yes"
        val remote = CountingRemoteSource(
            RemoteTileOutcome.Found(png(), injected),
        )
        val server = start(LocalTileReader { _, _, _ -> null }, remote)

        val res = exchange(server.port, get("/remote/3/4/5.png"))

        assertEquals(200, res.status, "raw response was: ${res.head}")
        assertFalse(res.lower.contains("x-injected"), "injected header reached the wire: ${res.head}")
        assertFalse(res.lower.contains("x-also"), "injected header reached the wire: ${res.head}")
        assertTrue(
            res.lower.contains("cache-control: no-store"),
            "expected a fail-closed no-store, got ${res.head}",
        )
        // Exactly one Cache-Control line, and the head block is not longer than it should be.
        val ccLines = res.head.lines().count { it.lowercase().startsWith("cache-control:") }
        assertEquals(1, ccLines, "expected one Cache-Control, got ${res.head}")
        assertEquals(listOf("3/4/5"), remote.calls)
    }

    @Test
    fun ordinaryCacheControlStillForwardsVerbatim() {
        // The injection guard must not quietly strip legitimate provider policy.
        val remote = CountingRemoteSource(
            RemoteTileOutcome.Found(png(), "public, max-age=3600, stale-while-revalidate=60"),
        )
        val server = start(LocalTileReader { _, _, _ -> null }, remote)

        val res = exchange(server.port, get("/remote/3/4/5.png"))

        assertEquals(200, res.status, "raw response was: ${res.head}")
        assertTrue(
            res.lower.contains("cache-control: public, max-age=3600, stale-while-revalidate=60"),
            "provider policy was altered: ${res.head}",
        )
    }

    private fun assertNotEquals(unexpected: Int, actual: Int) =
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual)

    private fun assertNotEquals(unexpected: Any, actual: Any) =
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual)

    private companion object {
        const val TILE_LOOPBACK = "127.0.0.1"
    }
}
