package com.airtak.net

import java.security.MessageDigest

/**
 * Identity for a tile provider *stream*, not merely a host.
 *
 * The host alone is not an identity. One host routinely serves several unrelated styles, and each
 * of these is a different provider stream that must not share an offline archive:
 *   https://tiles.example.com/styleA/{z}/{x}/{y}.png
 *   https://tiles.example.com/styleB/{z}/{x}/{y}.png
 *   https://tiles.example.com/v1/{z}/{x}/{y}.png?key=ACCOUNT_B
 *   https://tiles.example.com:8443/{z}/{x}/{y}.png
 * Keying an archive on the host alone makes the per-tile skip check, which keys on z/x/y, treat one
 * style's tile as already downloaded for another style, so the archive silently alternates between
 * two unrelated renderings of the same coordinates.
 *
 * Normalisation therefore folds only what is genuinely case-insensitive, scheme and host, and keeps
 * port, path and query intact because any of them can carry the selection. Dropping the query to
 * make a tidier key is exactly the bug this exists to prevent.
 */
object TileIdentity {

    /** Normalised template, or null when the URL has no usable host. */
    fun normalize(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = trimmed.substring(schemeSep + 3)
        if (rest.isEmpty()) return null
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd == -1) rest else rest.substring(0, authorityEnd)
        val tail = if (authorityEnd == -1) "" else rest.substring(authorityEnd)
        // Host part of the authority lowercases; a port is digits and needs no folding. Userinfo
        // is rejected by the policy, so treating anything before the last '@' as userinfo here only
        // affects an input that will be refused before it is ever fetched.
        val at = authority.lastIndexOf('@')
        val hostPort = if (at == -1) authority else authority.substring(at + 1)
        val prefix = if (at == -1) "" else authority.substring(0, at + 1)
        return "$scheme://$prefix${hostPort.lowercase()}$tail"
    }

    /**
     * Short stable hash of the normalised template, safe to put in a filename or stored metadata.
     *
     * The template itself is not stored: it can carry an API key in its query, and an archive is
     * user data that gets copied, backed up and sometimes screenshotted. The hash identifies the
     * stream without publishing the credential.
     */
    fun identityKey(rawUrl: String): String? {
        val normalized = normalize(rawUrl) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Provenance for archive metadata: host and port only.
     *
     * The path is deliberately excluded. A path is not reliably public information either, and a
     * credential sitting in one is a normal shape for a tile URL:
     *   https://t.example.com/token/SECRET_ACCOUNT/{z}/{x}/{y}.png
     * Keeping the path because it "looks like a route" would write that secret into an archive that
     * gets copied, backed up and screenshotted. Host and port still answer the question a user
     * actually has, which is which server an offline area came from.
     */
    fun provenanceLabel(rawUrl: String): String {
        val normalized = normalize(rawUrl) ?: return "unknown"
        val schemeSep = normalized.indexOf("://")
        val rest = normalized.substring(schemeSep + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd == -1) rest else rest.substring(0, authorityEnd)
        val at = authority.lastIndexOf('@')
        // Userinfo is refused by the policy before any fetch, but never let it reach stored
        // metadata either if some earlier stage is loosened.
        return (if (at == -1) authority else authority.substring(at + 1)).ifEmpty { "unknown" }
    }
}
