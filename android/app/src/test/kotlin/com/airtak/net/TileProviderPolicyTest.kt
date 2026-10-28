package com.airtak.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure logic, no Android, no network. Every case here is a real bypass attempt or a real
 * false-positive risk, not decoration.
 */
class TileProviderPolicyTest {

    private fun verdict(url: String?) = TileProviderPolicy.verdictForBulkDownload(url)

    private fun prohibited(url: String?) = verdict(url) is BulkDownloadVerdict.Prohibited

    @Test
    fun prohibitsCanonicalOsmTileHost() {
        assertTrue(
            prohibited("https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
            "the exact public tile host must be refused",
        )
    }

    @Test
    fun prohibitsLegacyLoadBalancedSubdomains() {
        listOf("a", "b", "c").forEach { sub ->
            assertTrue(
                prohibited("https://$sub.tile.openstreetmap.org/{z}/{x}/{y}.png"),
                "legacy $sub.tile.openstreetmap.org must be refused too",
            )
        }
    }

    @Test
    fun prohibitsArbitrarySubdomainDepth() {
        assertTrue(
            prohibited("https://tile.tile.openstreetmap.org/{z}/{x}/{y}.png"),
            "any real subdomain of the tile host must be refused",
        )
    }

    @Test
    fun prohibitsRegardlessOfCase() {
        assertTrue(prohibited("HTTPS://TILE.OpenStreetMap.ORG/{z}/{x}/{y}.png"))
    }

    @Test
    fun prohibitsTrailingDotForm() {
        // DNS treats the trailing dot as the root label, so this is the same server.
        assertTrue(prohibited("https://tile.openstreetmap.org./{z}/{x}/{y}.png"))
    }

    @Test
    fun prohibitsRegardlessOfPort() {
        // URI.host excludes the port, so a non-standard port cannot step around the guard.
        assertTrue(prohibited("http://tile.openstreetmap.org:8080/{z}/{x}/{y}.png"))
    }

    @Test
    fun doesNotOverMatchLookalikeSuffix() {
        // The classic over-match: a bare endsWith("tile.openstreetmap.org") would refuse this
        // unrelated host and break legitimate self-hosted servers.
        assertTrue(
            TileProviderPolicy.isAllowedForBulkDownload("https://tile.openstreetmap.org.hostile.example/{z}/{x}/{y}.png"),
            "a host that merely ends with the protected string must NOT be refused",
        )
    }

    @Test
    fun doesNotOverMatchLookalikePrefixOfRootDomain() {
        assertFalse(prohibited("https://openstreetmap.org.hostile.example/{z}/{x}/{y}.png"))
        assertFalse(prohibited("https://mytiles.example/{z}/{x}/{y}.png"))
    }

    @Test
    fun rejectsUserinfoAuthorityThatMimicsProtectedHost() {
        // http://tile.openstreetmap.org@hostile.example/ parses as host=hostile.example with
        // userinfo=tile.openstreetmap.org. It must be refused outright rather than treated as the
        // protected host or trusted as an ordinary host.
        assertTrue(verdict("http://tile.openstreetmap.org@hostile.example/{z}/{x}/{y}.png") is BulkDownloadVerdict.Invalid)
    }

    @Test
    fun allowsSelfHostedServerIncludingLan() {
        // A LAN tile server is the intended offline use case. There is deliberately no private
        // address filter here, so 127.0.0.1 and 192.168.x must stay allowed.
        listOf(
            "http://127.0.0.1:8080/tiles/{z}/{x}/{y}.png",
            "http://192.168.1.20/tiles/{z}/{x}/{y}.png",
            "http://tiles.lan.internal:8443/{z}/{x}/{y}.png",
            "https://tiles.mycompany.example/v1/{z}/{x}/{y}.png",
        ).forEach { url ->
            assertTrue(TileProviderPolicy.isAllowedForBulkDownload(url), "$url should be allowed")
        }
    }

    @Test
    fun failsClosedOnUnusableUrls() {
        listOf(
            null,
            "",
            "   ",
            "not a url at all",
            "tile.openstreetmap.org/{z}/{x}/{y}.png",  // no scheme
            "ftp://tiles.example/{z}/{x}/{y}.png",     // not http(s)
            "file:///sdcard/tiles/{z}/{x}/{y}.png",
            "http:///{z}/{x}/{y}.png",                 // no host
        ).forEach { url ->
            assertTrue(
                verdict(url) is BulkDownloadVerdict.Invalid,
                "must fail closed for ${url ?: "<null>"}, got ${verdict(url)}",
            )
        }
    }

    @Test
    fun templatePlaceholdersAreNotTreatedAsParseErrors() {
        // Regression. java.net.URI throws on the {z} placeholder, which made the guard report
        // "could not be parsed" for every real tile URL, including the OSM host it exists to
        // refuse. A template must parse, and it must still be classified correctly.
        assertTrue(TileProviderPolicy.isAllowedForBulkDownload("https://tiles.example/{z}/{x}/{y}.png"))
        assertTrue(prohibited("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
    }

    @Test
    fun rejectsAuthorityTricksThatWouldSilentlySkipComparison() {
        // A trailing space leaves a host that simply fails to equal the protected host, which
        // would read as "some unknown provider, fine to bulk download". Must be refused instead.
        assertTrue(
            verdict("https://tile.openstreetmap.org /{z}/{x}/{y}.png") is BulkDownloadVerdict.Invalid,
            "whitespace in the authority must be refused, not treated as an unknown host",
        )
        // Some clients treat a backslash as a path separator, so this is the protected host.
        assertTrue(prohibited("https://tile.openstreetmap.org\\{z}/{x}.png"))
    }

    @Test
    fun allowsBracketedIpv6SelfHostedServer() {
        assertTrue(TileProviderPolicy.isAllowedForBulkDownload("http://[::1]:8080/tiles/{z}/{x}/{y}.png"))
    }

    @Test
    fun rejectsMalformedAuthorityThatParsesDifferentlyElsewhere() {
        // None of these is structurally valid. Each is accepted by some HTTP stack and rejected by
        // others, so the guard must refuse rather than compute a host the client may not use.
        listOf(
            "https://lan.local:notaport/{z}/{x}/{y}.png",
            "https://lan.local:99999/{z}/{x}/{y}.png",
            "https://lan.local:0/{z}/{x}/{y}.png",
            "https://lan.local:8080junk/{z}/{x}/{y}.png",
            "https://[broken/{z}/{x}/{y}.png",
            "https://[::1/{z}/{x}/{y}.png",
            "https://%74ile.openstreetmap.org/{z}/{x}/{y}.png",   // percent-encodes to the host
            "https://tile%2eopenstreetmap.org/{z}/{x}/{y}.png",
            "https:///{z}/{x}/{y}.png",
        ).forEach { url ->
            assertTrue(
                verdict(url) is BulkDownloadVerdict.Invalid,
                "must refuse ${url}, got ${verdict(url)}",
            )
        }
    }

    @Test
    fun agreesWithKtorUrlParserOnEveryHostItAccepts() {
        // The differential that matters: if this guard and the Ktor client that actually issues the
        // request disagree about the host, the guard is decorative. For every URL the guard allows,
        // Ktor must see the same host. For every URL Ktor sees as the protected host, the guard
        // must not allow it.
        val corpus = listOf(
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org./{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org:8080/{z}/{x}/{y}.png",
            "https://TILE.OpenStreetMap.ORG/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org.hostile.example/{z}/{x}/{y}.png",
            "https://openstreetmap.org.hostile.example/{z}/{x}/{y}.png",
            "http://127.0.0.1:8080/tiles/{z}/{x}/{y}.png",
            "http://[::1]:8080/tiles/{z}/{x}/{y}.png",
            "http://tiles.lan.internal:8443/{z}/{x}/{y}.png",
            "https://mytiles.example/{z}/{x}/{y}.png",
        )
        val protectedHost = "tile.openstreetmap.org"
        corpus.forEach { url ->
            val ktorHost = runCatching { io.ktor.http.Url(url).host.lowercase().trimEnd('.') }
                .getOrNull()
            val v = verdict(url)
            if (ktorHost != null && v is BulkDownloadVerdict.Allowed) {
                assertEquals(ktorHost, v.host, "guard and Ktor disagree about host for $url")
            }
            if (ktorHost == protectedHost || ktorHost?.endsWith(".$protectedHost") == true) {
                assertTrue(v !is BulkDownloadVerdict.Allowed, "Ktor sees protected host but guard allowed: $url")
            }
        }
    }

    @Test
    fun prohibitedVerdictCarriesAnActionableReason() {
        val v = verdict("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        assertTrue(v is BulkDownloadVerdict.Prohibited)
        val reason = (v as BulkDownloadVerdict.Prohibited).reason
        assertTrue(reason.contains("bulk", ignoreCase = true), "reason should name the problem")
        assertTrue(reason.contains("tile server", ignoreCase = true), "reason should point at the fix")
        assertEquals("tile.openstreetmap.org", v.host)
    }
}
