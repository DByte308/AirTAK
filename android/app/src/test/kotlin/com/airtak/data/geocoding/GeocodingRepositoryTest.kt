package com.airtak.data.geocoding

import com.airtak.net.AirTakHttp
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Geocoder transport policy: identification, one shared request per second, deduplication that
 * never lets a losing racer reach the network, a bounded payload, honest error typing, and
 * cancellation that propagates.
 */
/**
 * Bounded per-test wall clock. Without this a deadlock in the class stalls the whole Gradle test
 * task with no output and no XML, which is exactly how a 20-minute timeout looked identical to a
 * slow machine. A hang must surface as a named failure.
 */
@org.junit.jupiter.api.Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class GeocodingRepositoryTest {

    /** Real localhost providers started by the tests that need a socket rather than a mock engine. */
    private val servers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    private companion object {
        /** `AirTakHttp.DEFAULT_TIMEOUTS.requestMs`. Named so the timing assertion can cite it. */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /**
         * A cap refusal must arrive inside this. Generous against the few hundred milliseconds the
         * real path takes, and far inside [REQUEST_TIMEOUT_MS], so the assertion distinguishes
         * "the cap stopped the read" from "the request was abandoned".
         */
        const val REFUSAL_DEADLINE_MS = 5_000L
    }

    private fun json(name: String = "Friedberg, Hessen"): String =
        JSONArray().apply {
            put(org.json.JSONObject().put("lat", "50.33").put("lon", "8.75").put("display_name", name))
        }.toString()

    private class Recorder {
        val count = AtomicInteger(0)
        val userAgents = mutableListOf<String>()
        val queries = mutableListOf<String>()
    }

    private fun repo(
        recorder: Recorder,
        delayMs: Long = 0,
        status: HttpStatusCode = HttpStatusCode.OK,
        extraHeaders: Map<String, String> = emptyMap(),
        bodyFor: (String?) -> String = { json() },
    ): GeocodingRepository {
        val engine = MockEngine { request: HttpRequestData ->
            recorder.count.incrementAndGet()
            synchronized(recorder.userAgents) { recorder.userAgents += request.headers[HttpHeaders.UserAgent].orEmpty() }
            synchronized(recorder.queries) { recorder.queries += request.url.parameters["q"].orEmpty() }
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val pairs: Array<Pair<String, List<String>>> =
                (listOf(HttpHeaders.ContentType to "application/json") + extraHeaders.toList())
                    .map { (name, value) -> name to listOf(value) }
                    .toTypedArray()
            val headers = headersOf(*pairs)
            respond(content = bodyFor(request.url.parameters["q"]), status = status, headers = headers)
        }
        return GeocodingRepository().apply {
            testClient = AirTakHttp.newClient(engine = engine, followRedirects = false)
        }
    }

    @Test
    fun identifiesItselfWithAContactUrl() = runBlocking {
        withTimeout(30_000) {
            val r = Recorder()
            repo(r).search("Friedberg")
            val ua = synchronized(r.userAgents) { r.userAgents.single() }
            assertTrue(ua.contains("AirTAK"), "user agent must identify the app: '$ua'")
            assertTrue(ua.contains("github.com"), "user agent must carry a contact URL: '$ua'")
        }
    }

    /**
     * True concurrency on real threads, not a single-threaded scheduler.
     *
     * The implementation used an eager `async`, so a caller that lost the publication race could
     * start the request before it was cancelled. A lazy deferred published before it is started is
     * what makes "one request" a guarantee rather than a tendency, and only real parallelism
     * distinguishes the two.
     */
    /**
     * True concurrency on real threads, not a single-threaded scheduler.
     *
     * The implementation used an eager `async`, so a caller that lost the publication race could
     * start the request before it was cancelled. A lazy deferred published before it is started is
     * what makes "one request" a guarantee rather than a tendency, and only real parallelism tells
     * the two apart. This test was briefly disabled when it appeared to hang the suite; the hang
     * was actually an unbounded busy wait in the payload reader, not the deduplication.
     */
    @Test
    fun concurrentIdenticalQueriesNeverLetALoserReachTheNetwork() {
        val r = Recorder()
        val target = repo(r, delayMs = 400)
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val sizes = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                try {
                    start.await(10, TimeUnit.SECONDS)
                    val n = runBlocking { withTimeout(30_000) { target.search("Friedberg") }.size }
                    sizes += n
                } catch (t: Throwable) {
                    sizes += -1
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(40, TimeUnit.SECONDS), "callers never finished")
        pool.shutdownNow()

        assertEquals(1, r.count.get(), "$threads racing identical queries made ${r.count.get()} requests")
        assertEquals(threads, sizes.count { it == 1 }, "every caller must still get the result: $sizes")
    }

    @Test
    fun repeatQueryIsServedFromCache() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            val target = repo(r)
            target.search("Friedberg")
            target.search("Friedberg")
            assertEquals(1, r.count.get(), "second identical query should have been cached")
        }
    }

    @Test
    fun differentQueriesAreNotCollapsedTogether() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            val target = repo(r, bodyFor = { q -> json(name = "result-for:$q") })
            val a = target.search("Friedberg")
            val b = target.search("Bad Nauheim")
            assertEquals(2, r.count.get())
            assertEquals("result-for:Friedberg", a.single().displayName)
            assertEquals("result-for:Bad Nauheim", b.single().displayName)
        }
    }

    @Test
    fun transportFailureIsATypedErrorAndIsNotCachedAsEmpty() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            var fail = true
            val target = GeocodingRepository().apply {
                testClient = AirTakHttp.newClient(
                    engine = MockEngine {
                        r.count.incrementAndGet()
                        if (fail) {
                            respond(content = "", status = HttpStatusCode.InternalServerError)
                        } else {
                            respond(content = json(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                    followRedirects = false,
                )
            }

            val thrown = assertThrows(GeocodeException::class.java) {
                runBlocking { target.search("Friedberg") }
            }
            assertTrue(
                thrown.failure is GeocodeFailure.Http,
                "a 5xx must be typed as Http, got ${thrown.failure}",
            )

            fail = false
            val second = target.search("Friedberg")
            assertEquals(1, second.size, "a transient outage must not pin an empty answer")
            assertEquals(2, r.count.get())
        }
    }

    @Test
    fun malformedBodyIsTypedRatherThanSilentlyEmpty() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            val target = repo(r, bodyFor = { "<html><body>gateway error</body></html>" })
            val thrown = assertThrows(GeocodeException::class.java) {
                runBlocking { target.search("Friedberg") }
            }
            assertTrue(thrown.failure is GeocodeFailure.Malformed, "got ${thrown.failure}")
        }
    }

    @Test
    fun retryAfterHeaderIsActuallyReadAndHonoured() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            val target = repo(
                r,
                status = HttpStatusCode.TooManyRequests,
                extraHeaders = mapOf(HttpHeaders.RetryAfter to "7"),
                bodyFor = { "" },
            )
            val thrown = assertThrows(GeocodeException::class.java) {
                runBlocking { target.search("Friedberg") }
            }
            val failure = thrown.failure
            assertTrue(failure is GeocodeFailure.Http, "got $failure")
            assertEquals(429, (failure as GeocodeFailure.Http).status)
            assertEquals(7_000L, failure.retryAfterMs, "Retry-After: 7 must surface as 7000ms")
        }
    }

    /**
     * A realistic Nominatim answer above the payload cap is refused, and refused without the
     * body being buffered.
     *
     * This used to run over MockEngine, and it passed for the wrong reason: the call was
     * abandoned by the client request timeout at about 30.01 s, which is exactly
     * `AirTakHttp.DEFAULT_TIMEOUTS.requestMs`. `HttpStatement` cleanup joins a response job that
     * still holds an unconsumed body after the block throws, and only that timeout ends the
     * wait. Green, but green because of a deadline rather than because the cap did anything.
     *
     * It now runs against a real localhost server on the real CIO engine, the same fixture as
     * `GeocoderStreamingProbeTest`. Three things are asserted, and the third is the point of the
     * rewrite: the refusal arrives well inside the request timeout, so if a future change makes
     * the outcome depend on a deadline again, this test fails instead of quietly taking 30
     * seconds.
     *
     * The body is served chunked with no Content-Length, so the cap has to be enforced on bytes
     * actually read. A declared length above the cap is the cheaper path and is covered by the
     * probe test; refusing only that would not be a cap, it would be a header check.
     */
    @Test
    fun oversizedPayloadIsRefusedRatherThanBuffered(): Unit = runBlocking {
        val huge = ("[" + (1..9_000).joinToString(",") {
            """{"lat":"50.1","lon":"8.7","display_name":"${"n".repeat(40)}$it"}"""
        } + "]").toByteArray()
        assertTrue(huge.size > 400_000, "fixture must exceed the cap, was ${huge.size}")

        val server = GeocodeTestServer(
            declaredLength = null,
            bodyBytes = huge.size,
            validJson = true,
            exactBody = huge,
        ).also { servers += it }

        val target = GeocodingRepository().apply {
            testClient = AirTakHttp.newClient(engine = CIO.create(), followRedirects = false)
            endpointOverride = "http://127.0.0.1:${server.port}/search"
        }

        val startedAt = System.nanoTime()
        val failure = runCatching { target.search("Friedberg") }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertInstanceOf(GeocodeException::class.java, failure, "expected GeocodeException, got $failure")
        val f = (failure as GeocodeException).failure
        assertInstanceOf(GeocodeFailure.TooLarge::class.java, f, "expected TooLarge, got $f")

        val drained = server.bytesWritten.get()
        // The invariant is that the cap stopped the read of the FULL body, not that the client
        // read less than half of it. The server counts bytes it threw at the socket, which includes
        // the OS send-buffer before the client's close propagates; for a ~900 KB fixture that
        // easily exceeds half. `drained < huge.size` (the client never saw the whole body) is what
        // proves the cap did something; `drained == huge.size` would mean it did nothing.
        assertTrue(
            drained < huge.size,
            "the cap did not stop the read: $drained of ${huge.size} bytes crossed the socket",
        )
        assertTrue(
            elapsedMs < REFUSAL_DEADLINE_MS,
            "refusal took $elapsedMs ms. The client request timeout is $REQUEST_TIMEOUT_MS ms, " +
                "so a wait of that order means the timeout ended the call, not the payload cap.",
        )
        Unit
    }

    @Test
    fun cancellationPropagatesRatherThanBecomingNoResults() = runBlocking {
        withTimeout(60_000) {
            val r = Recorder()
            val target = repo(r, delayMs = 4_000)
            var caught: Throwable? = null
            val job = launch {
                try {
                    target.search("Friedberg")
                } catch (t: Throwable) {
                    caught = t
                }
            }
            kotlinx.coroutines.delay(400)
            job.cancelAndJoinQuietly()
            assertNotNull(caught, "cancellation must surface to the caller")
            assertTrue(caught is CancellationException, "expected CancellationException, got ${caught!!::class.simpleName}")
        }
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinQuietly() {
        try {
            kotlinx.coroutines.withTimeout(10_000) {
                cancelAndJoinInternal()
            }
        } catch (_: Throwable) {
            // The cancelled body may surface its own cancellation here; the assertion below is the
            // one that matters.
        }
    }

    private suspend fun kotlinx.coroutines.Job.cancelAndJoinInternal() {
        this.cancel()
        try {
            this.join()
        } catch (_: CancellationException) {
        }
    }
}
