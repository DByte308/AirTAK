package com.airtak.data.geocoding

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A localhost HTTP server that answers one request with a JSON body of a chosen shape, so the
 * geocoder's payload cap can be tested against bytes that really cross a socket.
 *
 * MockEngine cannot answer the questions that matter here: it materialises response content
 * before the client sees a status, so it cannot show a declared length being rejected before the
 * body is read, cannot show a chunked body being abandoned mid-stream, and cannot show a stalled
 * producer being disconnected. Those are the properties the cap exists to provide.
 *
 * Shared by [GeocoderStreamingProbeTest] and [GeocodingRepositoryTest]. It used to be a private
 * class inside the probe test, which left the repository test with MockEngine as its only option
 * and a fixture that finished only because a timeout abandoned it. One fixture, two users, no
 * drift.
 *
 * Binds 127.0.0.1 only, and is [AutoCloseable] so a test can hand it to a teardown list.
 */
internal class GeocodeTestServer(
    /** null means chunked with no declared length. */
    private val declaredLength: Int?,
    private val bodyBytes: Int,
    private val validJson: Boolean,
    private val chunkBytes: Int = 16 * 1024,
    private val stallAfterBytes: Int? = null,
    /** When set, this exact payload is streamed instead of synthetic padding. */
    private val exactBody: ByteArray? = null,
) : AutoCloseable {
    val bytesWritten = AtomicLong(0)
    val wroteEverything = AtomicBoolean(false)
    val producerStoppedEarly = AtomicBoolean(false)
    val requestPath = AtomicString()
    private val ready = CountDownLatch(1)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    init {
        val t = Thread {
            try {
                ready.countDown()
                val socket = server.accept()
                requestPath.value = socket.getInputStream().drainRequestLine()
                val out = BufferedOutputStream(socket.getOutputStream())
                val head = if (declaredLength != null) {
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: $declaredLength\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                }
                out.write(head.toByteArray())
                out.flush()

                var sent = 0
                while (sent < bodyBytes) {
                    if (stallAfterBytes != null && sent >= stallAfterBytes) {
                        // Hold the connection open with nothing more to send.
                        Thread.sleep(60_000)
                    }
                    val n = minOf(chunkBytes, bodyBytes - sent)
                    val payload = exactBody?.copyOfRange(sent, minOf(sent + n, bodyBytes))
                        ?: payloadFor(validJson, n)
                    if (declaredLength != null) {
                        out.write(payload)
                    } else {
                        out.write(Integer.toHexString(payload.size).toByteArray())
                        out.write("\r\n".toByteArray())
                        out.write(payload)
                        out.write("\r\n".toByteArray())
                    }
                    out.flush()
                    sent += n
                    bytesWritten.set(sent.toLong())
                    Thread.sleep(2)
                }
                if (declaredLength == null) {
                    out.write("0\r\n\r\n".toByteArray())
                    out.flush()
                }
                wroteEverything.set(true)
            } catch (t: Throwable) {
                producerStoppedEarly.set(true)
            } finally {
                ready.countDown()
                runCatching { server.close() }
            }
        }
        t.isDaemon = true
        t.start()
        ready.await(5, TimeUnit.SECONDS)
    }

    /** Tiny chunk of valid JSON padding, so a small body parses and a large one is size-refused. */
    private fun payloadFor(valid: Boolean, n: Int): ByteArray =
        if (valid) ByteArray(n) { '.'.code.toByte() } else ByteArray(n) { 'x'.code.toByte() }

    override fun close() {
        runCatching { server.close() }
    }

    /** Consumes the request line and headers, returning the request line for assertions. */
    private fun InputStream.drainRequestLine(): String {
        val line = StringBuilder()
        var state = 0
        while (true) {
            val b = read()
            if (b == -1) return line.toString()
            if (state < 4) line.append(b.toChar())
            state = when {
                b == '\r'.code -> if (state == 2) 3 else 1
                b == '\n'.code && (state == 1 || state == 3) -> 4
                b == '\n'.code -> 0
                else -> 0
            }
            if (state == 4) return line.toString()
        }
    }

    /** Tiny mutable holder so the accept thread can record the request line without locking. */
    internal class AtomicString {
        @Volatile var value: String = ""
    }
}
