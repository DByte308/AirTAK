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

/** Outcome of validating a URL for bulk/offline tile download. */
sealed interface BulkDownloadVerdict {
    /** URL is structurally valid, http(s), and not a prohibited host. */
    data class Allowed(val host: String) : BulkDownloadVerdict

    /** URL points at a host whose acceptable-use policy forbids bulk downloading. */
    data class Prohibited(val host: String, val reason: String) : BulkDownloadVerdict

    /** URL could not be parsed, or is not an http(s) URL we can reason about. */
    data class Invalid(val reason: String) : BulkDownloadVerdict
}

object TileProviderPolicy {

    /**
     * The public OSM tile host. Legacy load-balanced aliases (a./b./c.tile.openstreetmap.org)
     * are subdomains of it and are covered by the suffix rule below.
     */
    private const val OSM_TILE_HOST = "tile.openstreetmap.org"

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

        // Deliberately not java.net.URI: that parser is strict about RFC 3986 characters and
        // throws on the {z}/{x}/{y} placeholders every tile template contains, so it rejected
        // every real tile URL, including the OSM one this guard exists to refuse. Only the
        // authority is needed here, so the authority is read directly.
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
        if (authority.isEmpty()) {
            return BulkDownloadVerdict.Invalid("Tile server URL has no usable hostname.")
        }
        // Whitespace or control characters would leave a host that silently fails the comparison
        // below, so refuse instead of letting it look like an ordinary unknown host.
        if (authority.any { it.isWhitespace() || it.code < 0x21 }) {
            return BulkDownloadVerdict.Invalid("Tile server URL contains invalid characters.")
        }

        // `http://tile.openstreetmap.org@hostile.example/` means host=hostile.example with
        // userinfo=tile.openstreetmap.org. Accepting it would let an authority that merely looks
        // like a permitted host slip past the guard, and userinfo has no legitimate use in a tile
        // template, so reject rather than try to interpret it.
        if (authority.contains('@')) {
            return BulkDownloadVerdict.Invalid("Tile server URL must not contain credentials.")
        }

        val host = stripPort(authority).lowercase().trimEnd('.')
        if (host.isEmpty()) {
            return BulkDownloadVerdict.Invalid("Tile server URL has no usable hostname.")
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

    /**
     * Drop the port so `:8080` cannot step around the comparison. Bracketed IPv6 literals keep
     * their brackets, since their address contains colons that are not a port separator.
     */
    private fun stripPort(authority: String): String =
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) authority else authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':', authority)
        }

    /** Convenience for call sites that only need yes/no. Invalid counts as not permitted. */
    fun isProhibitedForBulkDownload(rawUrl: String?): Boolean =
        verdictForBulkDownload(rawUrl) is BulkDownloadVerdict.Prohibited

    /** True when the URL is usable at all for bulk download. */
    fun isAllowedForBulkDownload(rawUrl: String?): Boolean =
        verdictForBulkDownload(rawUrl) is BulkDownloadVerdict.Allowed
}
