package com.airtak.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

// Fetches one raster tile from the user-configured provider on behalf of the loopback proxy.
//
// Three things this is responsible for that the raw client is not: it takes a slot from the
// shared per-provider budget, it refuses redirects so a 3xx cannot move the fetch onto a host the
// policy already refused, and it re-checks the host of the URL actually used before returning any
// bytes. Redirects are already disabled on the client, so the re-check is defence in depth rather
// than the primary control.

/** Provider cache directives relevant to tile reuse. Only what the app actually acts on. */
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

class RemoteTileFetcher(
    private val budgets: RequestBudgets,
    /** Injected so tests drive a mock engine. Production passes the shared no-redirect client. */
    private val client: HttpClient = AirTakHttp.newClient(followRedirects = false),
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

        val url = buildTemplateUrl(trimmed, z, x, y)
            ?: return Result.Refused("Tile URL template is malformed.")

        return runCatching {
            val response = client.get(url)
            val usedHost = runCatching { response.call.request.url.host }
                .getOrNull()
                ?.lowercase()
                ?.trimEnd('.')
            if (usedHost != null && usedHost != host) {
                // Should be unreachable with redirects disabled. Kept because a silent host change
                // is exactly the failure this whole guard exists to prevent.
                return Result.Refused("Request ended at $usedHost instead of $host.")
            }

            when (response.status) {
                HttpStatusCode.NotFound -> Result.NotFound(host)
                HttpStatusCode.MovedPermanently, HttpStatusCode.Found,
                HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect,
                HttpStatusCode.PermanentRedirect,
                -> Result.Refused("Provider responded with a redirect, which is not followed.")

                else -> {
                    if (!response.status.isSuccess()) {
                        return Result.Failed("Provider returned ${response.status.value}.")
                    }
                    val bytes: ByteArray = response.body()
                    if (bytes.isEmpty()) {
                        return Result.Failed("Provider returned an empty tile body.")
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
        }.getOrElse { error ->
            // Cancellation must propagate untouched so proxy teardown actually stops fetches.
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.Failed("Tile fetch failed: ${error.message ?: error.javaClass.simpleName}")
        }
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
