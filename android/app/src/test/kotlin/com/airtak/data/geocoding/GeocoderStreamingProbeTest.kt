package com.airtak.data.geocoding

import com.airtak.net.AirTakHttp
import io.ktor.client.engine.cio.CIO
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Geocoder request path against a real localhost HTTP server over a real HTTP engine.
 *
 * MockEngine cannot answer the questions that matter here. It materialises response content before
 * the client sees a status, so it cannot show a declared length being rejected before the body is
 * read, cannot show a chunked body being abandoned mid-stream, and cannot show a stalled producer
 * being disconnected. Those are the properties the payload cap exists to provide.
 *
 * These tests call the real [GeocodingRepository.search] through its internal endpoint seam, so URL
 * construction, the budget, the status handling and the capped reader are all on the path under
 * test. Everything binds 127.0.0.1. Every test is bounded so a regression fails instead of hanging.
 *
 * Scope note: this covers the JVM CIO engine. It does not cover the Android engine on a device, and
 * passing here is not evidence for that tier.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class GeocoderStreamingProbeTest {

    private val servers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    private fun repoFor(port: Int): GeocodingRepository = GeocodingRepository().apply {
        testClient = AirTakHttp.newClient(engine = CIO.create(), followRedirects = false)
        endpointOverride = "http://127.0.0.1:$port/search"
    }

    /** Real geocoder answer, small enough to be read in full and actually parsed. */
    @Test
    fun smallValidBodyIsParsedThroughTheRealPath(): Unit = runBlocking {
        withTimeout(60_000) {
            val body = """[{"lat":"50.33","lon":"8.75","display_name":"Friedberg, Hessen"}]"""
                .toByteArray()
            val srv = GeocodeTestServer(
                declaredLength = body.size,
                bodyBytes = body.size,
                validJson = true,
                exactBody = body,
            ).also { servers += it }

            val results = repoFor(srv.port).search("Friedberg")
            assertEquals(1, results.size, "a small declared body must parse through the real path")
            assertEquals("Friedberg, Hessen", results.single().displayName)
            assertEquals(50.33, results.single().latitude, 1e-6)
            assertEquals(body.size.toLong(), srv.bytesWritten.get())
            Unit
        }
    }

    /** A declared length above the cap must be refused before the body is read. */
    @Test
    fun declaredLengthAboveCapIsRefusedBeforeReadingTheBody(): Unit = runBlocking {
        withTimeout(60_000) {
            val declared = 4 * 1024 * 1024
            val srv = GeocodeTestServer(declaredLength = declared, bodyBytes = declared, validJson = false)
                .also { servers += it }
            val failure = runCatching { repoFor(srv.port).search("Friedberg") }.exceptionOrNull()
            assertInstanceOf(GeocodeException::class.java, failure, "expected GeocodeException, got $failure")
            val f = (failure as GeocodeException).failure
            assertInstanceOf(GeocodeFailure.TooLarge::class.java, f, "expected TooLarge, got $f")

            val written = srv.bytesWritten.get()
            assertTrue(
                written < declared / 2,
                "client drained $written of a $declared byte body despite a declared length above the cap",
            )
            assertFalse(srv.wroteEverything.get(), "server completed, so nothing stopped it")
            Unit
        }
    }

    /** No Content-Length, chunked, oversized: the cap must still stop the read. */
    @Test
    fun chunkedOversizeWithoutContentLengthIsRefused(): Unit = runBlocking {
        withTimeout(60_000) {
            val total = 6 * 1024 * 1024
            val srv = GeocodeTestServer(declaredLength = null, bodyBytes = total, validJson = false)
                .also { servers += it }
            val failure = runCatching { repoFor(srv.port).search("Friedberg") }.exceptionOrNull()
            assertInstanceOf(GeocodeException::class.java, failure, "expected GeocodeException, got $failure")
            val f = (failure as GeocodeException).failure
            assertInstanceOf(GeocodeFailure.TooLarge::class.java, f, "expected TooLarge, got $f")

            val written = srv.bytesWritten.get()
            assertTrue(written < total / 2, "client drained $written of a $total byte chunked body")
            assertFalse(srv.wroteEverything.get(), "chunked body ran to completion, nothing stopped it")
            Unit
        }
    }
}
