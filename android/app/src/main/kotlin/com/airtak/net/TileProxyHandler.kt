package com.airtak.net

// Router and request gate for the loopback tile server.
//
// Deliberately a pure function of request line plus header names, with no socket and no HTTP
// client, so the whole policy surface is testable directly while the production server is still
// what the socket-level tests connect to.
//
// This is not a general RFC proxy and must not become one. It serves raster tiles from a local
// archive and, when the user has configured a provider, raster tiles for that provider. The
// provider is resolved from configuration by the caller; nothing in a request may name a target
// URL, because that would turn a loopback listener into an open proxy for whatever host the
// device can reach.

/** Outcome of routing one loopback tile request. */
sealed interface TileRoute {
    /** Serve from the local MBTiles archive. */
    data class Local(val z: Int, val x: Int, val y: Int, val headOnly: Boolean) : TileRoute

    /** Fetch from the configured provider. The caller resolves the provider, never the request. */
    data class Remote(val z: Int, val x: Int, val y: Int, val headOnly: Boolean) : TileRoute

    /** Refuse with [status]. [reason] is for logs, not echoed to the client. */
    data class Reject(val status: Int, val reason: String) : TileRoute
}

object TileProxyHandler {

    /** Above these the request is refused rather than buffered. Bounds a hostile or buggy client. */
    const val MAX_REQUEST_LINE_BYTES = 4096
    const val MAX_HEADER_LINES = 32
    const val MAX_HEADER_BYTES = 8192

    /**
     * Hard ceiling independent of any provider. Tile arithmetic uses 1L shl z, and z above 30
     * starts pushing 2^z toward the range where an Int would wrap. The previous server computed
     * (1 shl z) - 1 - y with an Int, and shl masks the shift by 31, so z >= 32 silently produced a
     * wrong row instead of an error.
     */
    const val MAX_SUPPORTED_ZOOM = 30

    private val LOCAL_PATH = Regex("^/tiles/(\\d+)/(\\d+)/(\\d+)\\.png$")
    private val REMOTE_PATH = Regex("^/remote/(\\d+)/(\\d+)/(\\d+)\\.png$")

    /**
     * @param requestLine first line of the HTTP request, or null if none arrived
     * @param headerNames names only, since only presence matters here; values are not retained
     * @param maxZoom highest zoom worth serving, from the local archive or the provider config
     */
    fun route(requestLine: String?, headerNames: List<String>, maxZoom: Int): TileRoute {
        val line = requestLine?.trim()
        if (line.isNullOrEmpty()) {
            return TileRoute.Reject(400, "Missing request line.")
        }
        if (line.length > MAX_REQUEST_LINE_BYTES) {
            return TileRoute.Reject(414, "Request line too long.")
        }

        val parts = line.split(' ')
        if (parts.size < 2) {
            return TileRoute.Reject(400, "Malformed request line.")
        }

        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") {
            // Serving a POST as a GET, which the previous server did because it discarded the
            // method entirely, makes the listener behave like a general proxy.
            return TileRoute.Reject(405, "Only GET and HEAD are supported.")
        }

        if (headerNames.size > MAX_HEADER_LINES) {
            return TileRoute.Reject(431, "Too many header lines.")
        }
        if (headerNames.any { it.length > MAX_HEADER_BYTES }) {
            return TileRoute.Reject(431, "Header line too long.")
        }
        if (headerNames.any { it.equals("range", ignoreCase = true) }) {
            // A partial fetch is not implemented. Answering a Range request with a whole tile
            // claims to have satisfied a request it did not, so refuse instead.
            return TileRoute.Reject(501, "Byte ranges are not supported.")
        }

        val path = parts[1].substringBefore('?').substringBefore('#')
        val headOnly = method == "HEAD"

        val matcher = when {
            path.startsWith("/tiles/") -> LOCAL_PATH.matchEntire(path)?.let { TileKind.Local to it }
            path.startsWith("/remote/") -> REMOTE_PATH.matchEntire(path)?.let { TileKind.Remote to it }
            else -> null
        } ?: return TileRoute.Reject(400, "Unrecognised tile path.")

        val (kind, match) = matcher
        val z = match.groupValues[1].toLongOrNull()
        val x = match.groupValues[2].toLongOrNull()
        val y = match.groupValues[3].toLongOrNull()
        if (z == null || x == null || y == null) {
            return TileRoute.Reject(400, "Tile coordinates are not numeric.")
        }

        val ceiling = minOf(maxZoom.toLong(), MAX_SUPPORTED_ZOOM.toLong())
        if (z < 0 || z > ceiling) {
            return TileRoute.Reject(400, "Zoom $z outside 0..$ceiling.")
        }
        // Validated against 2^z as a Long before any shift into an Int.
        val span = 1L shl z.toInt()
        if (x < 0 || x >= span || y < 0 || y >= span) {
            return TileRoute.Reject(400, "Tile $x,$y outside 0..${span - 1} at zoom $z.")
        }

        val zi = z.toInt()
        val xi = x.toInt()
        val yi = y.toInt()
        return when (kind) {
            TileKind.Local -> TileRoute.Local(zi, xi, yi, headOnly)
            TileKind.Remote -> TileRoute.Remote(zi, xi, yi, headOnly)
        }
    }

    private enum class TileKind { Local, Remote }
}
