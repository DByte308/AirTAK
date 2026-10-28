package com.airtak.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileProxyHandlerTest {

    private fun route(line: String?, headers: List<String> = emptyList(), maxZoom: Int = 14) =
        TileProxyHandler.route(line, headers, maxZoom)

    @Test
    fun routesLocalTile() {
        val r = route("GET /tiles/3/4/5.png HTTP/1.1")
        assertEquals(TileRoute.Local(3, 4, 5, headOnly = false), r)
    }

    @Test
    fun routesRemoteTileWithoutNamingAnyHost() {
        // The path carries coordinates only. There is no form of this path that can specify a
        // target URL, which is what keeps the listener from becoming an open proxy.
        val r = route("GET /remote/10/536/352.png HTTP/1.1")
        assertEquals(TileRoute.Remote(10, 536, 352, headOnly = false), r)
    }

    @Test
    fun headIsAcceptedAndMarked() {
        val r = route("HEAD /tiles/3/4/5.png HTTP/1.1")
        assertEquals(TileRoute.Local(3, 4, 5, headOnly = true), r)
    }

    @Test
    fun rejectsEveryOtherMethod() {
        // The previous server discarded parts[0] and served these as GET.
        listOf("POST", "PUT", "DELETE", "OPTIONS", "CONNECT", "TRACE", "get").forEach { method ->
            val r = route("$method /tiles/3/4/5.png HTTP/1.1")
            if (method == "get") {
                assertTrue(r is TileRoute.Local, "lowercase method name must still be accepted")
            } else {
                assertTrue(r is TileRoute.Reject && r.status == 405, "$method must be refused 405, got $r")
            }
        }
    }

    @Test
    fun rejectsRangeRatherThanMisansweringIt() {
        val r = route("GET /tiles/3/4/5.png HTTP/1.1", listOf("Range", "Host"))
        assertTrue(r is TileRoute.Reject && r.status == 501, "Range must be refused, got $r")
    }

    @Test
    fun boundsHeaderVolume() {
        val many = List(TileProxyHandler.MAX_HEADER_LINES + 1) { "X-H$it" }
        val r = route("GET /tiles/3/4/5.png HTTP/1.1", many)
        assertTrue(r is TileRoute.Reject && r.status == 431, "too many headers must be refused, got $r")

        val big = listOf("X-Big" + "z".repeat(TileProxyHandler.MAX_HEADER_BYTES + 1))
        val r2 = route("GET /tiles/3/4/5.png HTTP/1.1", big)
        assertTrue(r2 is TileRoute.Reject && r2.status == 431, "oversized header must be refused, got $r2")
    }

    @Test
    fun refusesZoomThatWouldWrapTileArithmetic() {
        // The regression this exists for: (1 shl z) masks the shift by 31, so z >= 32 produced a
        // wrong row rather than an error.
        listOf(31, 32, 33, 63, 99, 4096).forEach { z ->
            val r = route("GET /tiles/$z/0/0.png HTTP/1.1", maxZoom = 30)
            assertTrue(r is TileRoute.Reject, "z=$z must be refused, got $r")
        }
    }

    @Test
    fun refusesZoomBeyondArchiveOrProviderCeiling() {
        val r = route("GET /tiles/15/0/0.png HTTP/1.1", maxZoom = 14)
        assertTrue(r is TileRoute.Reject && r.status == 400, "z above ceiling must be refused, got $r")
    }

    @Test
    fun refusesCoordinatesOutsideTheZoomGrid() {
        // z=3 has 8 columns. 8 is one past the last valid index.
        assertTrue(route("GET /tiles/3/8/0.png HTTP/1.1") is TileRoute.Reject)
        assertTrue(route("GET /tiles/3/0/8.png HTTP/1.1") is TileRoute.Reject)
        assertTrue(route("GET /tiles/3/7/7.png HTTP/1.1") is TileRoute.Local)
        // The same x is valid at a deeper zoom, so the check is genuinely per-zoom.
        assertTrue(route("GET /tiles/4/8/0.png HTTP/1.1", maxZoom = 14) is TileRoute.Local)
    }

    @Test
    fun refusesNonNumericAndMalformedCoordinates() {
        listOf(
            "GET /tiles/a/4/5.png HTTP/1.1",
            "GET /tiles/-1/4/5.png HTTP/1.1",
            "GET /tiles//4/5.png HTTP/1.1",
            "GET /tiles/3/4 HTTP/1.1",
            "GET /tiles/3/4/5.pbf HTTP/1.1",
            "GET /tiles/3/4/5.png.evil HTTP/1.1",
        ).forEach { line ->
            assertTrue(route(line) is TileRoute.Reject, "must refuse: $line")
        }
    }

    @Test
    fun refusesAnyAttemptToSmuggleATargetUrl() {
        listOf(
            "GET /remote/https://evil.example/1/2/3.png HTTP/1.1",
            "GET /remote/evil.example/1/2/3.png HTTP/1.1",
            "GET //remote/1/2/3.png HTTP/1.1",
            "GET /proxy?url=http://evil.example HTTP/1.1",
            "GET /tiles/1/2/3.png/../admin HTTP/1.1",
            "GET http://evil.example/tiles/1/2/3.png HTTP/1.1",
        ).forEach { line ->
            assertTrue(route(line) is TileRoute.Reject, "must refuse: $line")
        }
    }

    @Test
    fun stripsQueryButNothingElse() {
        // MapLibre appends ?v=<revision> to bust its cache, so the query must be tolerated.
        assertTrue(route("GET /tiles/3/4/5.png?v=1730000000 HTTP/1.1") is TileRoute.Local)
        // A path suffix is not a query and must not be tolerated.
        assertTrue(route("GET /tiles/3/4/5.png/admin HTTP/1.1") is TileRoute.Reject)
    }

    @Test
    fun refusesMalformedOrMissingRequestLine() {
        assertTrue(route(null) is TileRoute.Reject)
        assertTrue(route("") is TileRoute.Reject)
        assertTrue(route("GET") is TileRoute.Reject)
        assertTrue(route("GARBAGE") is TileRoute.Reject)
        val long = "GET /tiles/3/4/5.png HTTP/1.1 " + "a".repeat(TileProxyHandler.MAX_REQUEST_LINE_BYTES)
        val r = route(long)
        assertTrue(r is TileRoute.Reject && r.status == 414, "oversized line must be 414, got $r")
    }

    @Test
    fun maxZoomZeroStillServesTheSingleRootTile() {
        // z=0 has exactly one tile, 0/0. A ceiling of 0 must not reject it.
        assertTrue(route("GET /tiles/0/0/0.png HTTP/1.1", maxZoom = 0) is TileRoute.Local)
        assertTrue(route("GET /tiles/0/1/0.png HTTP/1.1", maxZoom = 0) is TileRoute.Reject)
    }
}

class RequestBudgetsTest {

    @Test
    fun liveAndBulkShareOneBudgetPerProvider() {
        // The whole point. Two callers that obtained their own pacer would each run at the
        // configured interval and sum to double the rate at one provider.
        val budgets = RequestBudgets()
        val fromProxy = budgets.tileBudgetFor("tiles.example")
        val fromDownloader = budgets.tileBudgetFor("tiles.example")
        assertSame(fromProxy, fromDownloader, "same provider must yield the same budget instance")
    }

    @Test
    fun normalisesHostSoCaseAndTrailingDotCannotSplitABudget() {
        val budgets = RequestBudgets()
        val a = budgets.tileBudgetFor("Tiles.Example")
        val b = budgets.tileBudgetFor("tiles.example.")
        val c = budgets.tileBudgetFor("tiles.example")
        assertSame(a, b)
        assertSame(b, c)
        assertEquals(setOf("tiles.example"), budgets.trackedTileHosts())
    }

    @Test
    fun distinctProvidersGetDistinctBudgets() {
        val budgets = RequestBudgets()
        val one = budgets.tileBudgetFor("a.example")
        val two = budgets.tileBudgetFor("b.example")
        assertFalse(one === two, "two providers must not share one budget")
        assertEquals(2, budgets.trackedTileHosts().size)
    }

    @Test
    fun geocoderBudgetIsSeparateFromTileBudgets() {
        val budgets = RequestBudgets()
        val tile = budgets.tileBudgetFor("nominatim.example")
        assertFalse(tile === budgets.geocoderBudget)
    }

    @Test
    fun sharedBudgetSerialisesTwoIndependentCallSites() = runTest {
        val clock = { testScheduler.currentTime }
        val budgets = RequestBudgets(tileSpacingMs = 250L, clock = clock)
        val starts = mutableListOf<Long>()
        kotlinx.coroutines.coroutineScope {
            // Two "call sites": one proxy handler, one bulk downloader, same provider.
            repeat(4) {
                launch {
                    budgets.tileBudgetFor("tiles.example").acquire()
                    starts += testScheduler.currentTime
                }
            }
        }
        assertEquals(listOf(0L, 250L, 500L, 750L), starts.sorted())
    }
}
