package com.airtak.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
    /** Reader for vector (format=pbf) archives, or null to fall back to [localTiles]. */
    private val localVectorTiles: LocalTileReader? = null,
    private val remoteTiles: RemoteTileSource? = null,
    private val socketTimeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS,
    private val maxConcurrentHandlers: Int = DEFAULT_MAX_HANDLERS,
    private val maxZoom: Int = TileProxyHandler.MAX_SUPPORTED_ZOOM,
) {
    @Volatile
    private var server: ServerSocketChannel? = null

    @Volatile
    private var running = false

    private val handlerSlots = Semaphore(maxConcurrentHandlers)
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    // Lazy disconnect watcher (a dedicated Selector thread, never a Dispatchers.IO thread).
    // Created on first use so a server that never serves a remote tile pays no thread cost.
    @Volatile
    private var disconnectWatcher: DisconnectWatcher? = null

    private fun disconnectWatcher(): DisconnectWatcher =
        disconnectWatcher ?: synchronized(this) {
            disconnectWatcher ?: DisconnectWatcher().also { disconnectWatcher = it }
        }

    /** Ephemeral port once started, or -1 when stopped. */
    val port: Int
        get() = (server?.localAddress as? InetSocketAddress)?.port ?: -1

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
        // ServerSocketChannel(loopback, port 0) asks the kernel for an ephemeral port.
        // The old ServerSocket(port, backlog, address) call put the backlog in the port slot and
        // bound a fixed low port instead, which collides with another service or fails outright on
        // a device. A ServerSocketChannel is also required so each accepted connection exposes a
        // SocketChannel (via Socket.getChannel()), which is what the non-blocking disconnect
        // watcher needs to observe a client going away (issue #9).
        val channel = ServerSocketChannel.open()
        channel.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
        server = channel
        running = true
        scope.launch { acceptLoop(channel) }
    }

    private suspend fun acceptLoop(socket: ServerSocketChannel) {
        while (running && socket.isOpen) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: break
            val acceptedSocket = accepted.socket()
            // A finite backlog bounds only the kernel's pending set. accept() hands each connection
            // straight on, so without a slot ceiling a burst grows an unbounded number of live
            // handlers on a phone.
            if (!handlerSlots.tryAcquire()) {
                rejectOverload(acceptedSocket)
                continue
            }
            openSockets.add(acceptedSocket)
            scope.launch {
                try {
                    handle(acceptedSocket)
                } finally {
                    openSockets.remove(acceptedSocket)
                    handlerSlots.release()
                    runCatching { acceptedSocket.close() }
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
            // The listener never reads this connection's request (it is refused on arrival), so the
            // client's bytes sit unread in the receive buffer. On a channel-backed socket (from the
            // ServerSocketChannel listener) closing with unread inbound data sends an RST and drops
            // the 503 above. Draining the buffered bytes makes the close graceful so the client
            // actually sees the refusal and can honour Retry-After.
            socket.getInputStream().let { input ->
                while (input.available() > 0) {
                    if (input.read() < 0) break
                }
            }
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
        disconnectWatcher?.let { runCatching { it.close() } }
        disconnectWatcher = null
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
                    val reader = if (route.vector) (localVectorTiles ?: localTiles) else localTiles
                    val raw = reader.read(route.z, route.x, route.y)
                    if (raw == null) {
                        writeStatus(socket, 404, route.headOnly)
                    } else if (route.vector) {
                        // An MVT tile is stored gzip-compressed protobuf (tilemaker's
                        // "compress": "gzip"). MapLibre expects a raw protobuf stream, so decode on
                        // the fly. Not-gzip bytes are served as-is rather than refused.
                        val decoded = if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
                            runCatching { gunzip(raw) }.getOrDefault(raw)
                        } else raw
                        writeTile(socket, decoded, route.headOnly, null, VECTOR_CONTENT_TYPE)
                    } else {
                        writeTile(socket, raw, route.headOnly, null)
                    }
                }

                is TileRoute.Remote -> {
                    val source = remoteTiles
                        ?: run {
                            writeStatus(socket, 503, route.headOnly)
                            return@withContext
                        }

                    // Issue #9: while the (potentially long, up to the request timeout) fetch is
                    // running, watch the client socket for disconnect without occupying a thread of
                    // the bounded Dispatchers.IO pool. The watcher is a single dedicated Selector
                    // thread that does a non-blocking read (EOF detection) on the client connection.
                    // On EOF it cancels this handler job, which releases the handler slot promptly
                    // instead of holding it until the fetch's own timeout expires.
                    val handlerJob = currentCoroutineContext().job
                    val channel = socket.getChannel()
                    val watchKey = channel?.let { disconnectWatcher().watch(it) { handlerJob.cancel() } }
                    val result = try {
                        source.fetch(route.z, route.x, route.y)
                    } finally {
                        // Restore the channel as blocking for the response write and drop the watch.
                        // Idempotent and safe if the watcher already cancelled the key on disconnect.
                        if (watchKey != null) runCatching { disconnectWatcher().unwatch(watchKey) }
                    }
                    when (result) {
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

    private fun writeTile(
        socket: Socket,
        bytes: ByteArray,
        headOnly: Boolean,
        cacheControl: String?,
        contentTypeOverride: String? = null,
    ) {
        val cc = cacheControlHeader(cacheControl).orEmpty()
        val body = bytes
        val contentType = contentTypeOverride ?: body.contentType()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n$cc" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray())
                if (!headOnly) out.write(body)
                out.flush()
            }
        }
    }

    /** Inflate a gzip stream. Used for stored MVT tiles that the archive keeps compressed. */
    private fun gunzip(bytes: ByteArray): ByteArray {
        java.util.zip.GZIPInputStream(bytes.inputStream()).use { input ->
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
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

        /** MapLibre accepts this for a vector tile source (`application/x-protobuf` also works). */
        const val VECTOR_CONTENT_TYPE = "application/vnd.mapbox-vector-tile"
    }
}

/**
 * Detects a client disconnecting mid-request without occupying a thread of the bounded
 * Dispatchers.IO pool (issue #9).
 *
 * A single dedicated daemon thread runs a java.nio [Selector]. Each in-progress connection is
 * registered (OP_READ) while its handler is busy serving the request. When the peer closes, the
 * channel becomes readable and a non-blocking read returns EOF; the watcher then invokes the
 * registered callback, which the handler uses to cancel its own job and release its handler slot
 * promptly instead of holding it until the fetch's own request timeout expires.
 *
 * Because the read is non-blocking and lives on one selector thread shared by every connection, no
 * connection ever blocks a worker thread — that is what the reverted version got wrong (it did a
 * blocking read that starved Dispatchers.IO and broke seven tests).
 *
 * The channel's blocking mode is owned by the handler: blocking during request parsing and response
 * writing, non-blocking only while a fetch is in flight. [watch] flips it to non-blocking and
 * registers it; [unwatch] restores blocking so the response can be written. [lock] serialises
 * channel access between the selector thread and the handler so the two never toggle the mode
 * concurrently.
 */
private class DisconnectWatcher {
    private val selector: Selector = Selector.open()
    private val running = AtomicBoolean(true)
    // Keys awaiting deregistration, mapped to a latch the selector thread counts down once it has
    // processed the key cancellation and the channel can be returned to blocking mode. This lets
    // a handler restore the channel to blocking synchronously without calling selectNow()/select()
    // on the selector concurrently with the selector thread (which hangs in the JDK).
    private val deregistering: MutableMap<SelectionKey, java.util.concurrent.CountDownLatch> =
        java.util.Collections.synchronizedMap(mutableMapOf())
    private val thread = Thread(::loop).apply {
        isDaemon = true
        name = "airtak-tile-disconnect"
        start()
    }

    /**
     * Registers [channel] for EOF detection. Returns the [SelectionKey], or null if the selector or
     * channel is already closed (in which case the caller should just serve the request normally).
     */
    fun watch(channel: SocketChannel, onDisconnect: () -> Unit): SelectionKey? {
        if (!selector.isOpen || !channel.isOpen) return null
        return try {
            channel.configureBlocking(false)
            val key = channel.register(selector, SelectionKey.OP_READ)
            key.attach(onDisconnect)
            selector.wakeup()
            key
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Cancels [key] and restores the channel as blocking so the handler can write its response.
     *
     * The channel can only be put back in blocking mode once the selector has deregistered it;
     * doing so on the selector thread is privileged. So we cancel the key, wake the selector, and
     * wait (bounded) for the selector thread to confirm the channel is deregistered. This never
     * calls select()/selectNow() from the handler on the selector concurrently with the selector
     * thread, which would otherwise hang.
     */
    fun unwatch(key: SelectionKey) {
        val channel = key.channel() as SocketChannel
        val latch = java.util.concurrent.CountDownLatch(1)
        deregistering[key] = latch
        if (key.isValid) key.cancel()
        runCatching { selector.wakeup() }
        try {
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // The selector thread normally removes + counts down the latch; drop the entry regardless
        // so a timed-out wait cannot leak the map entry.
        deregistering.remove(key)
        if (channel.isOpen && !channel.isBlocking) {
            runCatching { channel.configureBlocking(true) }
        }
    }

    /** Stops the selector thread. Safe to call when the watcher is idle. */
    fun close() {
        if (running.getAndSet(false)) {
            runCatching { selector.close() }
            runCatching { selector.wakeup() }
            // Release any handlers waiting on deregistration so they don't linger.
            val waiters = synchronized(deregistering) { deregistering.keys.toList() }
            waiters.forEach { deregistering[it]?.countDown() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loop() {
        while (running.get() && selector.isOpen) {
            val ready = runCatching { selector.select() }.getOrElse { return }
            if (ready == 0) continue
            val it = selector.selectedKeys().iterator()
            while (it.hasNext()) {
                val key = it.next()
                it.remove()
                if (!key.isValid) continue
                val channel = key.channel() as SocketChannel
                val onDisconnect = key.attachment() as? (() -> Unit) ?: continue
                val eof = try {
                    drainDetectEof(channel)
                } catch (e: java.nio.channels.IllegalBlockingModeException) {
                    // The handler re-configured the channel as blocking (unwatch). Not a
                    // disconnect; the handler is about to write and will handle the socket.
                    false
                } catch (e: Throwable) {
                    // Read error means the connection is gone.
                    true
                }
                if (eof) {
                    if (key.isValid) key.cancel()
                    runCatching { onDisconnect() }
                }
            }
            // The selector processes registrations/cancellations on each select(). Release any
            // handler waiting to deregister once the channel is actually deregistered (isValid()
            // goes false the instant cancel() is called, which is too early — the channel is not
            // deregistered until the selector has removed the key).
            synchronized(deregistering) {
                // isValid() flips false the instant cancel() is called, but the channel is not
                // deregistered until the selector removes the key; isRegistered is the real signal.
                val deregistered = deregistering.keys.filter { key ->
                    !(key.channel() as SocketChannel).isRegistered
                }
                deregistered.forEach { deregistering.remove(it)?.countDown() }
            }
        }
    }

    /** Reads until EOF (-1) or no more data (0). True only when the peer has gone away. */
    private fun drainDetectEof(channel: SocketChannel): Boolean {
        val buf = ByteBuffer.allocate(256)
        while (true) {
            val read = channel.read(buf)
            if (read < 0) return true
            if (read == 0) return false
            buf.clear()
        }
    }
}
