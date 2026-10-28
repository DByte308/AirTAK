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
    @Synchronized
    fun shutdown() {
        running = false
        server?.let { runCatching { it.close() } }
        server = null
        val sockets = synchronized(openSockets) { openSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
        synchronized(openSockets) { openSockets.clear() }
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = socketTimeoutMs
            val input = socket.getInputStream()

            // Overflow throws rather than returning null. A null meaning "too long" and a null
            // meaning "end of stream" are different events, and reading the first as the second
            // would route a truncated header block as if it were a complete request.
            val requestLine = input.readCappedLine(TileProxyHandler.MAX_REQUEST_LINE_BYTES)
                ?: run {
                    writeStatus(socket, 414)
                    return@withContext
                }
            val headerNames = mutableListOf<String>()
            var headerLines = 0
            while (true) {
                val line = input.readCappedLine(TileProxyHandler.MAX_HEADER_BYTES) ?: break
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

    private fun InputStream.readCappedLine(maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream(maxOf(64, minOf(maxBytes, 1024)))
        var terminated = false
        while (true) {
            val b = read()
            if (b == -1) break
            if (b == '\n'.code) {
                terminated = true
                break
            }
            if (bytes.size() >= maxBytes) throw IOException("header line exceeds $maxBytes bytes")
            bytes.write(b)
        }
        if (!terminated && bytes.size() == 0) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
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

    private fun writeTile(socket: Socket, bytes: ByteArray, headOnly: Boolean, cacheControl: String?) {
        val cc = cacheControl?.let { "Cache-Control: $it\r\n" }.orEmpty()
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
        const val ACCEPT_BACKLOG = 16
        const val DEFAULT_SOCKET_TIMEOUT_MS = 5_000
        const val DEFAULT_MAX_HANDLERS = 8
    }
}
