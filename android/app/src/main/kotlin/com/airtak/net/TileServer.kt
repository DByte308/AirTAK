package com.airtak.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Reads one tile out of local storage.
 *
 * Deliberately a single method rather than a database handle: the listener must be instantiable in
 * a plain JVM test with no Android context, so that the class under test is the shipped class and
 * not a test-only reimplementation of it.
 */
fun interface LocalTileReader {
    fun read(z: Int, x: Int, y: Int): ByteArray?
}

sealed interface RemoteTileOutcome {
    /** [cacheControl] is the provider's own header value, forwarded rather than stripped. */
    data class Found(val bytes: ByteArray, val cacheControl: String? = null) : RemoteTileOutcome {
        override fun equals(other: Any?): Boolean =
            other is Found && other.cacheControl == cacheControl && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode() * 31 + (cacheControl?.hashCode() ?: 0)
    }

    /** Provider answered, tile does not exist. */
    data object Missing : RemoteTileOutcome

    /** Provider unreachable or errored. */
    data object Unavailable : RemoteTileOutcome

    /** Policy refused the request; nothing was sent upstream. */
    data object Refused : RemoteTileOutcome
}

interface RemoteTileSource {
    suspend fun fetch(z: Int, x: Int, y: Int): RemoteTileOutcome
}

/**
 * The loopback tile listener AirTAK actually ships.
 *
 * Extracted from the repository so the real listener, its routing, its bounds and its teardown are
 * testable on a JVM without Android. Tests instantiate this class, not a stand-in.
 *
 * The bind address is not configurable. It is always 127.0.0.1 with an ephemeral port, because a
 * setting that could bind a public interface would turn a tile cache into a proxy reachable from the
 * network, and no legitimate use of this app needs one.
 */
class TileServer(
    private val scope: CoroutineScope,
    private val localTiles: LocalTileReader,
    private val remoteTiles: RemoteTileSource? = null,
    private val socketTimeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS,
    private val maxConcurrentHandlers: Int = DEFAULT_MAX_HANDLERS,
    private val maxZoom: Int = TileProxyHandler.MAX_SUPPORTED_ZOOM,
) {
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    private val handlerSlots = Semaphore(maxConcurrentHandlers)
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /** Ephemeral port once started, or -1 when stopped. */
    val port: Int
        get() = server?.localPort ?: -1

    val isRunning: Boolean
        get() = running

    /** Connections currently being served. Exposed so a test can prove handlers are released. */
    val activeHandlers: Int
        get() = maxConcurrentHandlers - handlerSlots.availablePermits()

    /**
     * Start listening. Safe to call again after [shutdown]; the previous socket is not reused.
     *
     * @throws IOException when the loopback bind fails.
     */
    @Synchronized
    fun start() {
        if (running) return
        // ServerSocket(port, backlog, address). Port 0 asks the kernel for an ephemeral port.
        // Passing the backlog in the port slot bound the listener to a fixed low port instead,
        // which collides with another service or fails outright on a device.
        val socket = ServerSocket(0, ACCEPT_BACKLOG, InetAddress.getByName(LOOPBACK))
        server = socket
        running = true
        scope.launch { acceptLoop(socket) }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: break
            // A finite backlog bounds only the kernel's pending set. accept() hands each connection
            // straight on, so without a slot ceiling a burst grows an unbounded number of live
            // handlers on a phone.
            if (!handlerSlots.tryAcquire()) {
                rejectOverload(accepted)
                continue
            }
            openSockets.add(accepted)
            scope.launch {
                try {
                    handle(accepted)
                } finally {
                    openSockets.remove(accepted)
                    handlerSlots.release()
                    runCatching { accepted.close() }
                }
            }
        }
    }

    private fun rejectOverload(socket: Socket) {
        runCatching {
            socket.getOutputStream().write(
                (
                    "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n" +
                        "Retry-After: 1\r\nContent-Length: 0\r\n\r\n"
                    ).toByteArray(),
            )
            socket.getOutputStream().flush()
        }
        runCatching { socket.close() }
    }

    /**
     * Close the listener and every live connection.
     *
     * Cancelling the coroutine scope is not enough: accept() and socket reads block a platform
     * thread and do not observe coroutine cancellation, so the only thing that unblocks them is
     * closing the socket underneath. A handler blocked on a provider fetch is released by the scope
     * the caller owns; the socket close is what makes it observable to the client immediately.
     */
    /**
     * What a shutdown actually achieved.
     *
     * A handler blocked in a synchronous read cannot be preempted: closing the socket disconnects
     * the client, but the worker keeps running until its read returns, and a `suspend` modifier on
     * the reader would not change that for a blocking call such as a SQLite query. Releasing the
     * slot at socket close instead would let blocked workers pile up without bound while the
     * counter reported them gone. So the honest answer is a report: the port and the connections
     * are always closed, and slot release is reported as pending when a worker is uncooperative.
     */
    data class ShutdownReport(
        val portClosed: Boolean,
        val connectionsClosed: Int,
        val slotsReleased: Boolean,
        val slotsStillHeld: Int,
    )

    @Synchronized
    fun shutdown(drainWaitMs: Long = DEFAULT_DRAIN_WAIT_MS): ShutdownReport {
        running = false
        server?.let { runCatching { it.close() } }
        server = null
        val sockets = synchronized(openSockets) { openSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
        synchronized(openSockets) { openSockets.clear() }

        val deadline = System.currentTimeMillis() + drainWaitMs
        while (activeHandlers > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val held = activeHandlers
        return ShutdownReport(
            portClosed = server == null,
            connectionsClosed = sockets.size,
            slotsReleased = held == 0,
            slotsStillHeld = held,
        )
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = socketTimeoutMs
            val input = socket.getInputStream()

            // A request is only routable once its header block has actually terminated. EOF is not
            // a terminator: a client that half-closes after the request line has not sent a
            // complete request, and routing it would consult a tile source on a header-less request.
            val requestLine = try {
                when (val line = input.readCappedLine(TileProxyHandler.MAX_REQUEST_LINE_BYTES)) {
                    is RequestLine.Complete -> line.value
                    is RequestLine.Absent -> {
                        writeStatus(socket, 400)
                        return@withContext
                    }

                    is RequestLine.Truncated -> {
                        writeStatus(socket, 400)
                        return@withContext
                    }
                }
            } catch (tooLong: RequestLineTooLong) {
                writeStatus(socket, 414)
                return@withContext
            }
            val headerNames = mutableListOf<String>()
            var headerLines = 0
            while (true) {
                val line = try {
                    when (val read = input.readCappedLine(TileProxyHandler.MAX_HEADER_BYTES)) {
                        is RequestLine.Complete -> read.value
                        is RequestLine.Absent -> {
                            writeStatus(socket, 400)
                            return@withContext
                        }

                        is RequestLine.Truncated -> {
                            writeStatus(socket, 400)
                            return@withContext
                        }
                    }
                } catch (tooLong: RequestLineTooLong) {
                    writeStatus(socket, 431)
                    return@withContext
                }
                if (line.isEmpty()) break
                headerLines++
                if (headerLines > TileProxyHandler.MAX_HEADER_LINES) {
                    writeStatus(socket, 431)
                    return@withContext
                }
                headerNames += line.substringBefore(':').trim()
            }

            when (val route = TileProxyHandler.route(requestLine, headerNames, maxZoom)) {
                is TileRoute.Reject -> writeStatus(socket, route.status)

                is TileRoute.Local -> {
                    val bytes = localTiles.read(route.z, route.x, route.y)
                    if (bytes == null) writeStatus(socket, 404, route.headOnly) else writeTile(socket, bytes, route.headOnly, null)
                }

                is TileRoute.Remote -> {
                    val source = remoteTiles
                        ?: run {
                            writeStatus(socket, 503, route.headOnly)
                            return@withContext
                        }
                    when (val result = source.fetch(route.z, route.x, route.y)) {
                        is RemoteTileOutcome.Found ->
                            // Forward what the provider said. When it said nothing, say no-store
                            // rather than staying silent and letting the engine guess.
                            writeTile(socket, result.bytes, route.headOnly, result.cacheControl ?: "no-store")

                        RemoteTileOutcome.Missing -> writeStatus(socket, 404, route.headOnly)
                        RemoteTileOutcome.Unavailable -> writeStatus(socket, 502, route.headOnly)
                        RemoteTileOutcome.Refused -> writeStatus(socket, 503, route.headOnly)
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // Malformed or aborted request: close without a partial response.
        }
    }

    /**
     * One line read from the request stream, distinguishing the three things that used to be
     * conflated. A single nullable String could not tell "stream ended cleanly" from "stream ended
     * in the middle of a line", and treating the second as the first is what let a truncated
     * header block be routed as a complete request.
     */
    private sealed interface RequestLine {
        /** End of stream with nothing buffered: the client sent nothing more. */
        data object Absent : RequestLine

        /** End of stream with buffered bytes that never reached a newline. */
        data class Truncated(val value: String) : RequestLine

        /** A real LF ended the line, so the line is complete. */
        data class Complete(val value: String) : RequestLine
    }

    /** Thrown on overflow only. Overflow and end-of-stream must not share a signal. */
    private class RequestLineTooLong(maxBytes: Int) :
        IOException("request line or header exceeds $maxBytes bytes")

    private fun InputStream.readCappedLine(maxBytes: Int): RequestLine {
        val bytes = ByteArrayOutputStream(maxOf(64, minOf(maxBytes, 1024)))
        while (true) {
            val b = read()
            if (b == -1) {
                if (bytes.size() == 0) return RequestLine.Absent
                return RequestLine.Truncated(
                    bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r'),
                )
            }
            if (b == '\n'.code) {
                return RequestLine.Complete(
                    bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r'),
                )
            }
            if (bytes.size() >= maxBytes) throw RequestLineTooLong(maxBytes)
            bytes.write(b)
        }
    }

    private fun writeStatus(socket: Socket, status: Int, headOnly: Boolean = false) {
        val reason = when (status) {
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            414 -> "URI Too Long"
            431 -> "Header Fields Too Large"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
        runCatching {
            socket.getOutputStream().use {
                it.write(head.toByteArray())
                it.flush()
            }
        }
        if (headOnly) return
    }

    /**
     * The forwarded value is only safe if it cannot end its own header line. Ktor's client rejects
     * CR and LF inside a header value while parsing, so a value arriving through
     * [RemoteTileFetcher] is already constrained — but [RemoteTileSource] is a single-method
     * interface any caller can implement, and this is the last checkpoint before the bytes go on
     * the wire. An embedded CRLF would turn one value into two headers.
     *
     * An unsafe value is replaced with `no-store` rather than dropped: refusing to say anything
     * would let the engine apply its own default caching, which is the failure the forwarding
     * exists to prevent.
     */
    private fun cacheControlHeader(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val safe = trimmed.isNotEmpty() &&
            trimmed.length <= MAX_FORWARDED_HEADER_CHARS &&
            trimmed.none { it == '\r' || it == '\n' || it == '\u0000' }
        return if (safe) "Cache-Control: $trimmed\r\n" else "Cache-Control: no-store\r\n"
    }

    private fun writeTile(socket: Socket, bytes: ByteArray, headOnly: Boolean, cacheControl: String?) {
        val cc = cacheControlHeader(cacheControl).orEmpty()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: ${bytes.contentType()}\r\n$cc" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray())
                if (!headOnly) out.write(bytes)
                out.flush()
            }
        }
    }

    private fun ByteArray.contentType(): String = when {
        size >= 8 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() -> "image/png"
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() -> "image/jpeg"
        size >= 12 && String(this, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "image/webp"
        else -> "application/octet-stream"
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Ceiling for a forwarded header value; longer values are replaced, not truncated. */
        const val MAX_FORWARDED_HEADER_CHARS = 512
        const val ACCEPT_BACKLOG = 16
        const val DEFAULT_SOCKET_TIMEOUT_MS = 5_000
        const val DEFAULT_MAX_HANDLERS = 8
        const val DEFAULT_DRAIN_WAIT_MS = 2_000L
    }
}
