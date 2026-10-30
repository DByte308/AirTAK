package com.airtak.data.geocoding

import com.airtak.net.AirTakHttp
import io.ktor.client.engine.cio.CIO
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * A geocoder producer that stalls mid-body must be cancellable, and cancelling must not leave the
 * client waiting for a body that will never arrive.
 *
 * Kept in its own file: as a fourth method in GeocoderStreamingProbeTest JUnit never discovered
 * it, and a test that silently does not run is worse than one that fails.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class GeocoderStallProbeTest {

    @Test
    fun stalledBodyIsCancellableAndReleasesTheConnection(): Unit = runBlocking {
        withTimeout(60_000) {
            val total = 4 * 1024 * 1024
            val ready = CountDownLatch(1)
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            val port = server.localPort
            val thread = Thread {
                try {
                    ready.countDown()
                    val socket = server.accept()
                    socket.getInputStream().drainHeaders()
                    val out = BufferedOutputStream(socket.getOutputStream())
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    out.flush()
                    // Send a little, then stop producing without closing.
                    var sent = 0
                    while (sent < 32 * 1024) {
                        val n = minOf(8 * 1024, total - sent)
                        out.write(Integer.toHexString(n).toByteArray())
                        out.write("\r\n".toByteArray())
                        out.write(ByteArray(n) { 'x'.code.toByte() })
                        out.write("\r\n".toByteArray())
                        out.flush()
                        sent += n
                    }
                    Thread.sleep(60_000)
                } catch (_: Throwable) {
                    // The client hanging up is the expected outcome.
                } finally {
                    ready.countDown()
                    runCatching { server.close() }
                }
            }
            thread.isDaemon = true
            thread.start()
            ready.await(5, TimeUnit.SECONDS)

            val target = GeocodingRepository().apply {
                testClient = AirTakHttp.newClient(engine = CIO.create(), followRedirects = false)
                endpointOverride = "http://127.0.0.1:$port/search"
            }

            var caught: Throwable? = null
            val job = launch {
                try {
                    target.search("Friedberg")
                } catch (t: Throwable) {
                    caught = t
                }
            }
            delay(2_500)
            job.cancel()
            val deadline = System.currentTimeMillis() + 10_000
            while (caught == null && System.currentTimeMillis() < deadline) {
                delay(50)
            }
            runCatching { server.close() }
            // Assigned rather than left as the last expression: assertInstanceOf returns a value,
            // and a @Test function whose inferred return type is not Unit is silently skipped by
            // JUnit 5. That is how this test appeared to pass while never running.
            val failure: Throwable? = assertInstanceOf(
                CancellationException::class.java,
                caught,
                "cancelling a stalled geocoder read must surface as cancellation, got $caught",
            )
            Unit
        }
    }

    private fun InputStream.drainHeaders() {
        var state = 0
        while (true) {
            val b = read()
            if (b == -1) return
            state = when {
                b == '\r'.code -> if (state == 2) 3 else 1
                b == '\n'.code && (state == 1 || state == 3) -> 4
                b == '\n'.code -> 0
                else -> 0
            }
            if (state == 4) return
        }
    }
}
