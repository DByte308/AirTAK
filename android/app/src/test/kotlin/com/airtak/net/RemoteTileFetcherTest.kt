package com.airtak.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteTileFetcherTest {

    private val budgets = RequestBudgets(tileSpacingMs = 0L)

    /** Records every URL the client actually attempted, so "refused before the socket" is provable. */
    private fun fetcher(): Pair<RemoteTileFetcher, MutableList<String>> {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url}"
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf())
        }
        return RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine)) to seen
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
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
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
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
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
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.Fetched)
        assertTrue((r as RemoteTileFetcher.Result.Fetched).directives.persistenceProhibited)
    }

    @Test
    fun absentCacheHeadersYieldNoInventedFreshness() = runTest {
        val engine = MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, headersOf()) }
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        val d = (r as RemoteTileFetcher.Result.Fetched).directives
        assertNull(d.maxAgeSeconds, "no max-age header must not become a local guess")
        assertNull(d.etag)
        assertTrue(!d.persistenceProhibited)
    }

    @Test
    fun missingTileIsDistinctFromATransportFailure() = runTest {
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound, headersOf()) }
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r is RemoteTileFetcher.Result.NotFound, "404 must be NotFound, got $r")
    }

    @Test
    fun redirectChainNeverYieldsTileBytes() = runTest {
        // What this can and cannot prove, stated rather than glossed.
        //
        // MockEngine 2.3.9 follows redirects inside the engine and exposes no switch to turn that
        // off (MockEngineConfig has requestHandlers and reuseHandlers only; maxSendCount arrived in
        // a later Ktor), so it cannot represent the production guarantee that the Android engine is
        // configured with followRedirects = false. Engine-level redirect suppression is therefore
        // an API 25 device verification item, not a JVM one, and is recorded as such.
        //
        // What is verifiable here is the guarantee that actually protects the caller: a redirecting
        // provider must never hand back tile bytes, whatever the engine does underneath.
        //
        // Observed under MockEngine: the engine follows the Location itself and re-requests it until
        // its internal send limit trips, so the redirect target does appear in the recorded URLs.
        // That is a property of the mock, not of the shipped client. The Location is therefore a
        // reserved .example host so that no test can ever encode a real third-party address, even
        // though MockEngine opens no socket.
        val engine = MockEngine {
            respond(
                byteArrayOf(1, 2, 3, 4, 5),
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://redirect-target.example/3/4/5.png"),
            )
        }
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
        val r = fetcher.fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(
            r !is RemoteTileFetcher.Result.Fetched,
            "a redirecting provider must never produce a tile, got $r",
        )
    }

    @Test
    fun serverErrorAndEmptyBodyAreFailuresWithoutBytes() = runTest {
        val engine500 = MockEngine { respond(ByteArray(0), HttpStatusCode.InternalServerError, headersOf()) }
        val engineEmpty = MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) }
        val r500 = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine500))
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        val rEmpty = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engineEmpty))
            .fetch("https://tiles.example/{z}/{x}/{y}.png", 3, 4, 5, 14)
        assertTrue(r500 is RemoteTileFetcher.Result.Failed, "500 must be Failed, got $r500")
        assertTrue(rEmpty is RemoteTileFetcher.Result.Failed, "empty 200 must be Failed, got $rEmpty")
    }

    @Test
    fun fetchesToTheSameProviderShareOneBudget() = runTest {
        val clock = { testScheduler.currentTime }
        val shared = RequestBudgets(tileSpacingMs = 250L, clock = clock)
        val engine = MockEngine { respond(byteArrayOf(1), HttpStatusCode.OK, headersOf()) }
        val client = AirTakHttp.newClient(engine = engine)
        // Two independent fetcher instances, as the proxy and the bulk downloader would be.
        val proxySide = RemoteTileFetcher(shared, client)
        val bulkSide = RemoteTileFetcher(shared, client)
        val template = "https://tiles.example/{z}/{x}/{y}.png"

        proxySide.fetch(template, 12, 100, 100, 14)
        bulkSide.fetch(template, 12, 101, 100, 14)
        assertEquals(250L, testScheduler.currentTime, "second fetch to the same provider must wait")

        // A different provider has its own budget and must not be held up.
        val other = RemoteTileFetcher(shared, client).fetch("https://other.example/{z}/{x}/{y}.png", 12, 1, 1, 14)
        assertTrue(other is RemoteTileFetcher.Result.Fetched)
        assertEquals(250L, testScheduler.currentTime, "a different provider must not share the wait")
    }

    @Test
    fun cancellationPropagatesRatherThanBecomingAFailure() = runTest {
        val engine = MockEngine {
            delay(60_000)
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf())
        }
        val fetcher = RemoteTileFetcher(budgets, AirTakHttp.newClient(engine = engine))
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
