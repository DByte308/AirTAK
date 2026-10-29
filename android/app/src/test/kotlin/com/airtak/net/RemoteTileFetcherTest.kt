package com.airtak.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MockEngine client with the timeout phases left unset.
 *
 * Under `runTest` the coroutine clock is skipped forward whenever the coroutine is idle, so a real
 * deadline fires immediately and every policy test would report a timeout instead of the policy
 * outcome it is asserting. Timeouts are not untested for being inconvenient — they are proven
 * against a real stalled socket in RemoteTileFetcherTimeoutTest, and AirTakHttpTimeoutTest asserts
 * the shipped client carries the production bounds.
 */
private fun virtualTimeClient(
    engine: io.ktor.client.engine.HttpClientEngine,
    followRedirects: Boolean = false,
) = AirTakHttp.newClient(
    engine = engine,
    followRedirects = followRedirects,
    connectTimeoutMs = null,
    socketTimeoutMs = null,
    requestTimeoutMs = null,
)


class RemoteTileFetcherTest {

    private val budgets = RequestBudgets(tileSpacingMs = 0L)

    /** Records every URL the client actually attempted, so "refused before the socket" is provable. */
    private fun fetcher(): Pair<RemoteTileFetcher, MutableList<String>> {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url}"
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf())
        }
        return RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null) to seen
    }

    @Test
    fun missingProviderIsItsOwnOutcomeNotAFailure() = runTest {
        val (fetcher, seen) = fetcher()
        val r = fetcher.fetch(null, 3, 4, 5, maxZoom = 14)
        assertTrue(r is RemoteTileFetcher.Result.NoProvider, "got $r")
        assertTrue(seen.isEmpty(), "no provider must mean no request at all")

        assertTrue(fetcher.fetch("   ", 3, 4, 5, 14) is RemoteTileFetcher.Result.NoProvider)
    }

    @Test
    fun liveFetchToPublicOsmIsRefusedBeforeAnySocket() = runTest {
        val (fetcher, seen) = fetcher()
        val r = fetcher.fetch("https://tile.openstreetmap.org/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Refused, "OSM live must be refused, got $r")
        assertTrue(seen.isEmpty(), "refusal must happen before the request, not after it lands")
    }

    @Test
    fun malformedProviderIsRefusedNotTrusted() = runTest {
        val (fetcher, seen) = fetcher()
        listOf("not a url", "ftp://tiles.example/{z}/{x}/{y}.png", "https://tiles.example:notaport/{z}/{x}/{y}.png")
            .forEach { template ->
                assertTrue(
                    fetcher.fetch(template, 3, 4, 5, 14) is RemoteTileFetcher.Result.Refused,
                    "must refuse template $template",
                )
            }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun sendsDescriptiveUserAgentOnEveryRequest() = runTest {
        var ua: String? = null
        val engine = MockEngine { request ->
            ua = request.headers[HttpHeaders.UserAgent]
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf())
        }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertEquals(AirTakHttp.userAgent(), ua, "every tile request must identify the app")
        assertTrue(ua!!.startsWith("AirTAK/"), "UA must name the app")
        assertTrue(ua!!.contains("github.com/DByte308/AirTAK"), "UA must carry a real contact URL")
    }

    @Test
    fun substitutesCoordinatesPositionally() = runTest {
        val (fetcher, seen) = fetcher()
        fetcher.fetch("https://tiles.example/tiles/{z}/{x}/{y}.png", 12, 2049, 1361, 14)
        assertEquals(listOf("GET https://tiles.example/tiles/12/2049/1361.png"), seen)
    }

    @Test
    fun refusesTemplateThatCouldInjectSegments() = runTest {
        val (fetcher, seen) = fetcher()
        // A template with a stray placeholder would leave literal braces in the URL; one with a
        // crafted placeholder could inject path segments or a second authority.
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.{ext}", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Refused, "leftover placeholder must be refused, got $r")
        assertTrue(seen.isEmpty())
    }

    @Test
    fun parsesProviderCacheDirectivesRatherThanInventingThem() = runTest {
        val engine = MockEngine {
            respond(
                byteArrayOf(1),
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.CacheControl to listOf("public, max-age=3600, must-revalidate"),
                    HttpHeaders.ETag to listOf("\"abc123\""),
                    HttpHeaders.LastModified to listOf("Wed, 21 Oct 2015 07:28:00 GMT"),
                ),
            )
        }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Fetched, "got $r")
        val d = (r as RemoteTileFetcher.Result.Fetched).directives
        assertEquals(3600L, d.maxAgeSeconds)
        assertTrue(d.mustRevalidate)
        assertEquals("\"abc123\"", d.etag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", d.lastModified)
        assertEquals("tiles.example", r.providerHost)
    }

    @Test
    fun noStoreIsSurfacedAsPersistenceProhibited() = runTest {
        val engine = MockEngine {
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl, "no-store"))
        }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Fetched)
        assertTrue((r as RemoteTileFetcher.Result.Fetched).directives.persistenceProhibited)
    }

    @Test
    fun absentCacheHeadersYieldNoInventedFreshness() = runTest {
        val engine = MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, headersOf()) }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        val d = (r as RemoteTileFetcher.Result.Fetched).directives
        assertNull(d.maxAgeSeconds, "no max-age header must not become a local guess")
        assertNull(d.etag)
        assertTrue(!d.persistenceProhibited)
    }

    @Test
    fun missingTileIsDistinctFromATransportFailure() = runTest {
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound, headersOf()) }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.NotFound, "404 must be NotFound, got $r")
    }

    @Test
    fun redirectIsRefusedAndNeverFollowed() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.toString()
            respond(
                byteArrayOf(1, 2, 3, 4, 5),
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://redirect-target.example/3/4/5.png"),
            )
        }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Refused, "3xx must be refused, got $r")
        assertEquals(
            listOf("https://tiles.example/3/4/5.png"),
            seen,
            "exactly one request and the redirect target must never be reached",
        )
    }

    @Test
    fun productionSeamDisablesRedirectsWhileTheFactoryDefaultDoesNot() = runTest {
        // Guards the exact mistake this correction came from. The factory defaults to following
        // redirects, so a test seam that omits followRedirects = false silently exercises a client
        // that is not the one that ships, and a redirect test built on it proves nothing.
        fun redirectingEngine(seen: MutableList<String>) = MockEngine { request ->
            seen += request.url.toString()
            respond(
                ByteArray(0),
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://redirect-target.example/x"),
            )
        }

        val strict = mutableListOf<String>()
        RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = redirectingEngine(strict), followRedirects = false), totalTimeoutMs = null)
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertEquals(1, strict.size, "production seam must not follow, saw $strict")

        val loose = mutableListOf<String>()
        RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = redirectingEngine(loose)), totalTimeoutMs = null)
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(
            loose.size > 1,
            "factory default does follow redirects, which is why every seam must opt out explicitly",
        )
    }

    @Test
    fun bodyOverTheCeilingIsRejectedRatherThanBuffered() = runTest {
        // An arbitrarily large provider response must not be able to exhaust device memory.
        val engine = MockEngine { respond(ByteArray(4096), HttpStatusCode.OK, headersOf()) }
        val fetcher = RemoteTileFetcher(
            budgets,
            virtualTimeClient(engine, followRedirects = false),
            maxResponseBytes = 1024, totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Failed, "oversized body must be rejected, got $r")
    }

    // Not provable with MockEngine, recorded rather than faked: MockUtilsKt materialises the
    // response content (it exposes toByteArray / toByteReadPacket), so even respond(ByteReadChannel)
    // is fully drained by the mock before the client sees a status. That means the mock cannot show
    // whether the production path streams or buffers, and cannot show that an over-cap read cancels
    // the producer early. The cap itself is tested below, which proves the limit is enforced, but
    // "no bytes are buffered before the cap is applied" needs a real localhost HTTP server with a
    // slow, oversized body. That is a deliberate follow-up, not something to claim from a mock.

    @Test
    fun bodyUnderTheCeilingIsAccepted() = runTest {
        val engine = MockEngine { respond(ByteArray(4096), HttpStatusCode.OK, headersOf()) }
        val fetcher = RemoteTileFetcher(
            budgets,
            virtualTimeClient(engine, followRedirects = false),
            maxResponseBytes = 8192, totalTimeoutMs = null)
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Fetched, "in-size body must succeed, got $r")
        assertEquals(4096, (r as RemoteTileFetcher.Result.Fetched).bytes.size)
    }

    @Test
    fun serverErrorAndEmptyBodyAreFailuresWithoutBytes() = runTest {
        val engine500 = MockEngine { respond(ByteArray(0), HttpStatusCode.InternalServerError, headersOf()) }
        val engineEmpty = MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) }
        val r500 = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine500), totalTimeoutMs = null)
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        val rEmpty = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engineEmpty), totalTimeoutMs = null)
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r500 is RemoteTileFetcher.Result.Failed, "500 must be Failed, got $r500")
        assertTrue(rEmpty is RemoteTileFetcher.Result.Failed, "empty 200 must be Failed, got $rEmpty")
    }

    @Test
    fun fetchesToTheSameProviderShareOneBudget() = runTest {
        val clock = { testScheduler.currentTime }
        val shared = RequestBudgets(tileSpacingMs = 250L, clock = clock)
        val engine = MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, headersOf()) }
        val client = virtualTimeClient(engine, followRedirects = false)
        // Two independent fetcher instances, as the proxy and the bulk downloader would be.
        val proxySide = RemoteTileFetcher(shared, client, totalTimeoutMs = null)
        val bulkSide = RemoteTileFetcher(shared, client, totalTimeoutMs = null)
        val template = "https://tiles.example/{z}/{x}/{y}.png"

        proxySide.fetch(template, 12, 100, 100, 14)
        bulkSide.fetch(template, 12, 101, 100, 14)
        assertEquals(250L, testScheduler.currentTime, "second fetch to the same provider must wait")

        // A different provider has its own budget and must not be held up.
        val other = RemoteTileFetcher(shared, client, totalTimeoutMs = null).fetch("https://other.example/{z}/{x}/{y}.png", 12, 1, 1, 14)
        assertTrue(other is RemoteTileFetcher.Result.Fetched)
        assertEquals(250L, testScheduler.currentTime, "a different provider must not share the wait")
    }

    @Test
    fun cancellationPropagatesRatherThanBecomingAFailure() = runTest {
        val engine = MockEngine {
            delay(60_000)
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf())
        }
        val fetcher = RemoteTileFetcher(budgets, virtualTimeClient(engine, followRedirects = false), totalTimeoutMs = null)
        var caught: Throwable? = null
        val job = launch {
            try {
                fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
            } catch (c: CancellationException) {
                caught = c
                throw c
            }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(caught is CancellationException, "teardown must cancel, not swallow into Failed")
    }
}

class LiveFetchVerdictTest {

    @Test
    fun refusesPublicOsmForLiveUseWithItsOwnReason() {
        val v = TileProviderPolicy.verdictForLiveFetch("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        assertTrue(v is LiveFetchVerdict.Prohibited, "got $v")
        val reason = (v as LiveFetchVerdict.Prohibited).reason
        assertTrue(reason.contains("attribution", ignoreCase = true), "live refusal must state the real reason")
        assertTrue(reason.contains("verified", ignoreCase = true))
    }

    @Test
    fun refusesOsmSubdomainsForLiveUse() {
        listOf("a", "b", "c").forEach {
            assertTrue(
                TileProviderPolicy.verdictForLiveFetch("https://$it.tile.openstreetmap.org/{z}/{x}/{y}.png")
                    is LiveFetchVerdict.Prohibited,
            )
        }
    }

    @Test
    fun allowsSelfHostedLiveFetchIncludingLan() {
        listOf(
            "http://127.0.0.1:8080/tiles/{z}/{x}/{y}.png",
            "http://tiles.lan.internal/{z}/{x}/{y}.png",
            "https://tiles.mycompany.example/v1/{z}/{x}/{y}.png",
        ).forEach {
            val v = TileProviderPolicy.verdictForLiveFetch(it)
            assertTrue(v is LiveFetchVerdict.Allowed, "$it should be allowed, got $v")
        }
    }

    @Test
    fun doesNotOverMatchLookalikeForLiveUse() {
        val v = TileProviderPolicy.verdictForLiveFetch("https://tile.openstreetmap.org.hostile.example/{z}/{x}/{y}.png")
        assertTrue(v is LiveFetchVerdict.Allowed, "lookalike must not be blocked, got $v")
    }

    @Test
    fun failsClosedForLiveUse() {
        listOf(null, "", "nope", "ftp://tiles.example/{z}/{x}/{y}.png").forEach {
            assertTrue(
                TileProviderPolicy.verdictForLiveFetch(it) is LiveFetchVerdict.Invalid,
                "must fail closed for ${it ?: "<null>"}",
            )
        }
    }
}

/**
 * A diagnostic that never interpolated is worse than no diagnostic: the operator reads
 * "Provider returned ${response.status.value}." on screen, and it looks like a number was there.
 * Three of these messages shipped that way, so the assertion is on the actual value reaching the
 * text, plus a blanket check that no un-substituted placeholder survives in any failure reason.
 */
class RemoteTileFetcherDiagnosticsTest {

    private val budgets = RequestBudgets(tileSpacingMs = 0L)

    private fun fetcher(engine: io.ktor.client.engine.HttpClientEngine, cap: Int = 1024) =
        RemoteTileFetcher(
            budgets,
            virtualTimeClient(engine, followRedirects = false),
            maxResponseBytes = cap, totalTimeoutMs = null)

    /** `${'$'}foo` or `${'$'}{foo}` surviving means the template was never evaluated. */
    private fun assertNoUnsubstitutedPlaceholder(reason: String) {
        assertTrue(!reason.contains("\${"), "un-substituted expression in: $reason")
        assertTrue(
            !Regex("""\$[A-Za-z_][A-Za-z0-9_]*""").containsMatchIn(reason),
            "un-substituted variable reference in: $reason",
        )
    }

    private fun failed(result: RemoteTileFetcher.Result): String {
        assertTrue(result is RemoteTileFetcher.Result.Failed, "expected Failed, got $result")
        return (result as RemoteTileFetcher.Result.Failed).reason
    }

    @Test
    fun nonSuccessStatusReportsTheActualStatusCodeNotTheTemplate() = runTest {
        val engine = MockEngine {
            respond(ByteArray(0), HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val reason = failed(
            fetcher(engine).fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        )
        assertTrue(reason.contains("503"), "the status code must reach the operator: $reason")
        assertNoUnsubstitutedPlaceholder(reason)
    }

    @Test
    fun declaredSizeOverCapReportsBothTheDeclaredSizeAndTheCap() = runTest {
        val engine = MockEngine {
            respond(
                ByteArray(4),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength to listOf("999999")),
            )
        }
        val reason = failed(
            fetcher(engine, cap = 1024).fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        )
        assertTrue(reason.contains("999999"), "declared size must be named: $reason")
        assertTrue(reason.contains("1024"), "the cap that was exceeded must be named: $reason")
        assertNoUnsubstitutedPlaceholder(reason)
    }

    @Test
    fun bodyOverCapNamesTheCapItHit() = runTest {
        val engine = MockEngine {
            respond(ByteArray(4096), HttpStatusCode.OK, headersOf())
        }
        val reason = failed(
            fetcher(engine, cap = 64).fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        )
        assertTrue(reason.contains("64"), "the cap must be named: $reason")
        assertNoUnsubstitutedPlaceholder(reason)
    }

    @Test
    fun transportErrorCarriesTheUnderlyingMessage() = runTest {
        val engine = MockEngine { throw java.io.IOException("provider stalled mid-body") }
        val reason = failed(
            fetcher(engine).fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        )
        assertTrue(
            reason.contains("provider stalled mid-body"),
            "the underlying error text must survive into the reason: $reason",
        )
        assertNoUnsubstitutedPlaceholder(reason)
    }

    @Test
    fun transportErrorWithoutAMessageFallsBackToTheClassNameNotAnEmptyString() = runTest {
        val engine = MockEngine { throw java.io.IOException() }
        val reason = failed(
            fetcher(engine).fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        )
        assertTrue(
            reason.contains("IOException"),
            "a null message must fall back to the class name rather than print null: $reason",
        )
        assertNoUnsubstitutedPlaceholder(reason)
    }
}
