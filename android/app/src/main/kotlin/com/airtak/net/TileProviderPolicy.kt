package com.airtak.net

// Request-policy guard for tile downloads.
//
// Why this exists: the app used to ship tile.openstreetmap.org as its hardcoded default and
// then download up to MAP_MAX_TILES_PER_DOWNLOAD tiles from it in a tight loop with no pacing
// and no User-Agent. That is bulk automated use of the OpenStreetMap Foundation's shared
// community tile server, which its usage policy prohibits regardless of how gently we pace it.
// Pacing makes a load lighter; it does not make a prohibited use permitted.
//
// Scope is deliberately narrow. This is one known host and its subdomains, not a generic
// provider-policy engine and not a private-address filter. Self-hosted servers, including ones
// on a LAN, are the intended use case and must keep working.
//
// Callers must re-check the final URL after any redirect. Automatic redirects are disabled for
// tile and geocoder requests precisely so a 3xx cannot move a bulk download onto a prohibited
// host after this check has passed.

import java.net.URI

/** Outcome of validating a URL for bulk/offline tile download. */
sealed interface BulkDownloadVerdict {
    /** URL is structurally valid, http(s), and not a prohibited host. */
    data class Allowed(val host: String) : BulkDownloadVerdict

    /** URL points at a host whose acceptable-use policy forbids bulk downloading. */
    data class Prohibited(val host: String, val reason: String) : BulkDownloadVerdict

    /** URL could not be parsed, or is not an http(s) URL we can reason about. */
    data class Invalid(val reason: String) : BulkDownloadVerdict
}

/** Outcome of validating a URL for ordinary live tile fetching, which is a different question. */
sealed interface LiveFetchVerdict {
    data class Allowed(val host: String) : LiveFetchVerdict
    data class Prohibited(val host: String, val reason: String) : LiveFetchVerdict
    data class Invalid(val reason: String) : LiveFetchVerdict
}

object TileProviderPolicy {

    /**
     * The public OSM tile host. Legacy load-balanced aliases (a./b./c.tile.openstreetmap.org)
     * are subdomains of it and are covered by the suffix rule below.
     */
    private const val OSM_TILE_HOST = "tile.openstreetmap.org"

    private const val OSM_LIVE_REASON =
        "Live tile requests to OpenStreetMap's public server are disabled until this app's " +
            "attribution and caching behaviour against that server has been verified. " +
            "Configure your own tile server or a provider whose terms you have checked."

    private const val OSM_USAGE_REASON =
        "OpenStreetMap's public tile server prohibits bulk and automated downloading. " +
            "Configure your own tile server or a provider whose terms permit offline use."

    /**
     * Validate [rawUrl] for bulk/offline download.
     *
     * Fails closed: an unparseable URL, a missing host, a non-http(s) scheme, or a URL carrying
     * userinfo is never treated as merely "unknown and therefore fine".
     */
    fun verdictForBulkDownload(rawUrl: String?): BulkDownloadVerdict {
        val trimmed = rawUrl?.trim()
        if (trimmed.isNullOrEmpty()) {
            return BulkDownloadVerdict.Invalid("No tile server URL configured.")
        }

        // java.net.URI cannot parse a tile template directly: it is strict about RFC 3986 and
        // throws on the {z}/{x}/{y} placeholders, so parsing the whole URL rejects every real tile
        // URL including the OSM host this guard exists to refuse. So the authority is sliced out
        // first and that authority alone is handed to URI as a synthetic URL with no placeholders
        // in it. That keeps real RFC validation of host and port rather than trusting a hand-rolled
        // parser to agree with whatever the HTTP stack does later.
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) {
            return BulkDownloadVerdict.Invalid("Tile server URL must use http or https.")
        }
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") {
            return BulkDownloadVerdict.Invalid("Tile server URL must use http or https.")
        }

        // A backslash is a path separator to some clients, so it terminates the authority here
        // too rather than becoming part of a hostname that then fails to compare equal.
        val authority = trimmed.substring(schemeSep + 3)
            .takeWhile { c -> c != '/' && c != '?' && c != '#' && c != '\\' }

        val host = when (val parsed = parseHost(scheme, authority)) {
            is HostParse.Ok -> parsed.host
            is HostParse.Reject -> return BulkDownloadVerdict.Invalid(parsed.reason)
        }

        // Exact host or a real subdomain of it. A port is irrelevant to the comparison because
        // URI.host excludes the port, so :8080 cannot bypass this. The leading-dot form is what
        // stops `tile.openstreetmap.org.hostile.example` from matching, which a bare endsWith
        // would wrongly prohibit.
        if (host == OSM_TILE_HOST || host.endsWith(".$OSM_TILE_HOST")) {
            return BulkDownloadVerdict.Prohibited(host, OSM_USAGE_REASON)
        }

        return BulkDownloadVerdict.Allowed(host)
    }

    private sealed interface HostParse {
        data class Ok(val host: String) : HostParse
        data class Reject(val reason: String) : HostParse
    }

    /**
     * Extract and validate the hostname from an authority slice.
     *
     * Each of these is rejected rather than guessed, because some HTTP stack parses it differently
     * and any divergence between this guard and the client that later issues the request is a
     * bypass: userinfo, percent-encoding that decodes to another host at request time, whitespace
     * or control characters, a backslash, a non-numeric or out-of-range port, junk after the port,
     * and a malformed IPv6 literal.
     */
    private fun parseHost(scheme: String, authority: String): HostParse {
        if (authority.isEmpty()) return HostParse.Reject("Tile server URL has no usable hostname.")
        // `tile.openstreetmap.org@hostile.example` means host=hostile.example with userinfo, and
        // userinfo has no legitimate use in a tile template.
        if (authority.contains('@')) {
            return HostParse.Reject("Tile server URL must not contain credentials.")
        }
        if (authority.contains('%')) {
            return HostParse.Reject("Tile server URL must not percent-encode the host.")
        }
        if (authority.any { it.isWhitespace() || it.code < 0x21 || it == '\\' }) {
            return HostParse.Reject("Tile server URL contains invalid characters.")
        }

        // Synthetic URL: no {z} placeholders, so strict RFC parsing is safe on this slice.
        val uri = runCatching { URI("$scheme://$authority/") }.getOrNull()
            ?: return HostParse.Reject("Tile server URL authority is malformed.")

        // URI accepts an out-of-range port rather than rejecting it, so check the range here.
        val port = uri.port
        if (port == 0 || port > 65535) {
            return HostParse.Reject("Tile server URL has an invalid port.")
        }

        val host = uri.host?.lowercase()?.trimEnd('.')
        if (host.isNullOrEmpty()) {
            return HostParse.Reject("Tile server URL has no usable hostname.")
        }
        return HostParse.Ok(host)
    }

    /**
     * Validate [rawUrl] for ordinary live tile fetching.
     *
     * Separate from bulk on purpose. Prohibition of bulk downloading is not the same question as
     * whether ordinary live use is acceptable, and conflating them would either over-block a
     * provider that permits live use or under-block one whose live terms we have not checked.
     *
     * The public OSM host is refused here for a different stated reason than in bulk: not because
     * live use is prohibited outright, but because this app does not yet verifiably meet that
     * server's attribution and caching requirements. Refusing is the honest state until it does.
     */
    fun verdictForLiveFetch(rawUrl: String?): LiveFetchVerdict {
        val trimmed = rawUrl?.trim()
        if (trimmed.isNullOrEmpty()) {
            return LiveFetchVerdict.Invalid("No tile server URL configured.")
        }
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) return LiveFetchVerdict.Invalid("Tile server URL must use http or https.")
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") {
            return LiveFetchVerdict.Invalid("Tile server URL must use http or https.")
        }
        val authority = trimmed.substring(schemeSep + 3)
            .takeWhile { c -> c != '/' && c != '?' && c != '#' && c != '\\' }
        val host = when (val parsed = parseHost(scheme, authority)) {
            is HostParse.Ok -> parsed.host
            is HostParse.Reject -> return LiveFetchVerdict.Invalid(parsed.reason)
        }
        if (host == OSM_TILE_HOST || host.endsWith(".$OSM_TILE_HOST")) {
            return LiveFetchVerdict.Prohibited(host, OSM_LIVE_REASON)
        }
        return LiveFetchVerdict.Allowed(host)
    }

    /**
     * Only [BulkDownloadVerdict.Allowed] is permitted. [BulkDownloadVerdict.Invalid] is refused
     * too: a URL this guard cannot reason about is not a URL to bulk download 15 000 tiles from.
     * There is deliberately no isProhibited() shorthand, because "not prohibited" and "permitted"
     * are different answers once Invalid exists. Match on the sealed verdict for messaging.
     */
    fun isAllowedForBulkDownload(rawUrl: String?): Boolean =
        verdictForBulkDownload(rawUrl) is BulkDownloadVerdict.Allowed
}
