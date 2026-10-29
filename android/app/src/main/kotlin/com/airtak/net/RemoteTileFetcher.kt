package com.airtak.net

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.airtak.config.constants.NET_TILE_FETCH_TOTAL_TIMEOUT_MS
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

// Fetches one raster tile from the user-configured provider on behalf of the loopback proxy.
//
// Three things this is responsible for that the raw client is not: it takes a slot from the
// shared per-provider budget, it refuses redirects so a 3xx cannot move the fetch onto a host the
// policy already refused, and it re-checks the host of the URL actually used before returning any
// bytes. Redirects are already disabled on the client, so the re-check is defence in depth rather
// than the primary control.

/**
 * Provider cache directives relevant to tile reuse. Only what the app actually acts on.
 *
 * Deliberately limited, and not a full RFC 9111 parser. It splits Cache-Control on commas and
 * matches a small set of tokens, so a quoted directive value containing a comma, for example
 * `no-cache="set-cookie, x"`, is not interpreted correctly. Full directive parsing belongs to
 * Ktor's HttpCache when cache reuse is wired up; this type exists to carry the few facts the
 * fetch path needs, not to claim compliance.
 */
data class ProviderCacheDirectives(
    val noStore: Boolean,
    val noCache: Boolean,
    val mustRevalidate: Boolean,
    val maxAgeSeconds: Long?,
    val etag: String?,
    val lastModified: String?,
) {
    /** A provider that forbids persistence cannot be cached or served offline at all. */
    val persistenceProhibited: Boolean get() = noStore

    companion object {
        val EMPTY = ProviderCacheDirectives(false, false, false, null, null, null)

        fun parse(cacheControl: String?, etag: String?, lastModified: String?): ProviderCacheDirectives {
            val directives = cacheControl?.lowercase()?.split(',')?.map { it.trim() }.orEmpty()
            val maxAge = directives
                .firstOrNull { it.startsWith("max-age=") }
                ?.removePrefix("max-age=")
                ?.toLongOrNull()
            return ProviderCacheDirectives(
                noStore = directives.contains("no-store"),
                noCache = directives.contains("no-cache"),
                mustRevalidate = directives.contains("must-revalidate"),
                maxAgeSeconds = maxAge,
                etag = etag?.takeIf { it.isNotBlank() },
                lastModified = lastModified?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Ceiling on a single tile response. A provider, or anything answering for one, must not be able
 * to exhaust the device's memory by returning an arbitrarily large body: reading it straight into
 * a ByteArray would do exactly that. Content-Length is rejected early when present, and the read
 * is capped regardless so a lying or absent header does not help.
 */
const val MAX_TILE_RESPONSE_BYTES = 512 * 1024

class RemoteTileFetcher(
    private val budgets: RequestBudgets,
    /**
     * Injected so tests drive a mock engine. Tests must pass followRedirects = false exactly as
     * production does; the factory defaults to true, so omitting it silently tests a different
     * client than the one that ships.
     */
    private val client: HttpClient = AirTakHttp.newClient(followRedirects = false),
    private val maxResponseBytes: Int = MAX_TILE_RESPONSE_BYTES,
    /**
     * Outer bound on one fetch. The client's own timeout plugin covers connect and per-phase reads;
     * this is the backstop for a response that starts and then trickles, and it is what keeps a
     * stalled provider from holding a shared per-provider budget slot indefinitely.
     */
    private val totalTimeoutMs: Long? = NET_TILE_FETCH_TOTAL_TIMEOUT_MS,
) {

    sealed interface Result {
        data class Fetched(
            val bytes: ByteArray,
            val providerHost: String,
            val directives: ProviderCacheDirectives,
        ) : Result {
            override fun equals(other: Any?): Boolean =
                other is Fetched && other.providerHost == providerHost &&
                    other.directives == directives && other.bytes.contentEquals(bytes)

            override fun hashCode(): Int =
                bytes.contentHashCode() * 31 + providerHost.hashCode()
        }

        /** No provider configured. The map must show an unavailable state, not a silent gap. */
        data class NoProvider(val reason: String) : Result

        /** Policy refused this provider for live use. */
        data class Refused(val reason: String) : Result

        /** Provider answered that the tile does not exist. Distinct from a transport failure. */
        data class NotFound(val providerHost: String) : Result

        /** Transport, redirect or status failure. Never returns partial bytes. */
        data class Failed(val reason: String) : Result
    }

    /**
     * @param template user-configured `{z}/{x}/{y}` template, resolved by the caller
     * @param maxZoom ceiling already validated by the router
     */
    suspend fun fetch(template: String?, z: Int, x: Int, y: Int, maxZoom: Int): Result {
        val trimmed = template?.trim()
        if (trimmed.isNullOrEmpty()) {
            return Result.NoProvider("No tile server configured in Settings > App.")
        }

        val verdict = TileProviderPolicy.verdictForLiveFetch(trimmed)
        val host = when (verdict) {
            is LiveFetchVerdict.Allowed -> verdict.host
            is LiveFetchVerdict.Prohibited -> return Result.Refused(verdict.reason)
            is LiveFetchVerdict.Invalid -> return Result.Refused(verdict.reason)
        }

        // Shared with bulk downloads against the same provider, deliberately not a private budget.
        budgets.tileBudgetFor(host).acquire()

        if (maxZoom < 0 || z < 0 || z > maxZoom) {
            // The router validates this already, but fetch() is public and takes the coordinates
            // itself, so nothing structurally stops a caller from asking for z=30 against a
            // maxZoom of 12. Re-checking here costs one comparison and makes the method safe on
            // its own rather than safe only when reached through one particular caller.
            return Result.Refused("Zoom $z is outside the configured ceiling 0..$maxZoom.")
        }

        val url = buildTemplateUrl(trimmed, z, x, y)
            ?: return Result.Refused("Tile URL template is malformed.")

        // prepareGet().execute { } rather than get(). get() saves the response, which buffers the
        // whole body before the caller sees a status, so a cap checked afterwards would be applied
        // to memory that has already been spent. execute { } streams and disposes the response on
        // every exit path, including the early returns below, so an over-cap or refused body is
        // cancelled rather than drained.
        val timeoutMs = totalTimeoutMs
        return runCatching {
            bounded(timeoutMs) {
                client.prepareGet(url).execute { response ->
                    val usedHost = runCatching { response.call.request.url.host }
                        .getOrNull()
                        ?.lowercase()
                        ?.trimEnd('.')
                    if (usedHost != null && usedHost != host) {
                        // Should be unreachable with redirects disabled. Kept because a silent host
                        // change is exactly the failure this guard exists to prevent.
                        return@execute Result.Refused("Request ended at $usedHost instead of $host.")
                    }

                    when (response.status) {
                        HttpStatusCode.NotFound -> return@execute Result.NotFound(host)
                        HttpStatusCode.MovedPermanently, HttpStatusCode.Found,
                        HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect,
                        HttpStatusCode.PermanentRedirect,
                        -> return@execute Result.Refused("Provider responded with a redirect, which is not followed.")

                        else -> {
                            if (!response.status.isSuccess()) {
                                return@execute Result.Failed("Provider returned ${response.status.value}.")
                            }
                            val declared = response.contentLength()
                            if (declared != null && declared > maxResponseBytes) {
                                return@execute Result.Failed(
                                    "Provider declared $declared bytes, over the $maxResponseBytes tile limit.",
                                )
                            }
                            val bytes = readBounded(response, maxResponseBytes)
                                ?: return@execute Result.Failed(
                                    "Provider body exceeded the $maxResponseBytes tile limit.",
                                )
                            if (bytes.isEmpty()) {
                                return@execute Result.Failed("Provider returned an empty tile body.")
                            }
                            Result.Fetched(
                                bytes = bytes,
                                providerHost = host,
                                directives = ProviderCacheDirectives.parse(
                                    cacheControl = response.headers[HttpHeaders.CacheControl],
                                    etag = response.headers[HttpHeaders.ETag],
                                    lastModified = response.headers[HttpHeaders.LastModified],
                                ),
                            )
                        }
                    }
                }
            }
        }.getOrElse { error ->
            // TimeoutCancellationException IS a CancellationException, so it has to be matched
            // first. Letting it fall into the branch below would turn "the provider stalled" into a
            // cancellation that tears down the caller's scope — the opposite of bounding the wait.
            if (error is TimeoutCancellationException) {
                return Result.Failed(
                    "Tile fetch from $host timed out after ${timeoutMs}ms.",
                )
            }
            // Real cancellation must propagate untouched so proxy teardown actually stops fetches.
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.Failed("Tile fetch failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Runs [block] under [withTimeout] when [timeoutMs] is set, and unbounded when it is null.
     *
     * The null case exists for virtual-time tests only: under `runTest` the coroutine clock is
     * skipped forward, so a real deadline fires immediately and every policy test would report a
     * timeout instead of the policy outcome it is actually asserting. Production always passes a
     * bound, and `AirTakHttpTimeoutTest` asserts that the shipped client carries one.
     */
    private suspend fun <T> bounded(timeoutMs: Long?, block: suspend () -> T): T =
        if (timeoutMs != null) withTimeout(timeoutMs) { block() } else block()

    /**
     * Read at most [maxBytes] from the response, returning null as soon as it is exceeded rather
     * than buffering the whole body first. The Content-Length check in the caller is only an early
     * exit; this is the actual bound, so a lying or missing header cannot defeat it.
     */
    private suspend fun readBounded(
        response: io.ktor.client.statement.HttpResponse,
        maxBytes: Int,
    ): ByteArray? {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream(16 * 1024)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) {
                if (channel.isClosedForRead) break
                channel.awaitContent()
                continue
            }
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Substitute the placeholders positionally rather than by regex, so a template cannot smuggle
     * extra path segments or a second authority through a crafted `{z}` value.
     */
    private fun buildTemplateUrl(template: String, z: Int, x: Int, y: Int): String? {
        val substituted = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        if (substituted.contains('{') || substituted.contains('}')) return null
        if (substituted.any { it.isWhitespace() || it.code < 0x21 }) return null
        return substituted
    }
}
