// AirTAK Android — data/geocoding/GeocodingRepository.kt

package com.airtak.data.geocoding

import com.airtak.config.constants.NETWORK_NOMINATIM_URL
import com.airtak.net.AirTakHttp
import com.airtak.net.CappedBody
import com.airtak.net.parseRetryAfterMs
import com.airtak.net.RequestBudgets
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class GeocodingResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

/** Why a lookup failed. Distinct from "no results", which is a successful empty answer. */
sealed interface GeocodeFailure {
    data class Transport(val message: String) : GeocodeFailure
    data class Http(val status: Int, val retryAfterMs: Long?) : GeocodeFailure
    data object Malformed : GeocodeFailure
    data object TooLarge : GeocodeFailure
}

/** Propagated to the caller so an outage cannot be mistaken for an empty result set. */
class GeocodeException(val failure: GeocodeFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/**
 * Forward geocoding against a Nominatim-compatible endpoint.
 *
 * Nominatim is a geocoder, not a tile provider, so it gets its own request budget rather than
 * sharing the per-provider tile budget. The public instance's policy is one request per second per
 * client, not per call site, so the budget is shared across callers and keyed to the host, leaving a
 * self-hosted instance paced independently.
 */
@Singleton
class GeocodingRepository @Inject constructor() {
    private val budgets = RequestBudgets()

    /**
     * Test seam, deliberately not a second constructor: Dagger rejects two injected constructors.
     * Tests install a transport here; production leaves it null and gets the shared client, which
     * identifies the app with a contact URL and refuses redirects so a 3xx cannot move the query
     * onto a host that was never vetted.
     */
    @Volatile internal var testClient: HttpClient? = null

    /**
     * Endpoint override, internal and test-only.
     *
     * A localhost probe has to exercise the real request path, including URL construction and the
     * response handling around it, which is impossible if the URL is a compile-time constant. This
     * is a seam, not a setting: nothing in the app can point geocoding at an arbitrary host.
     */
    @Volatile internal var endpointOverride: String? = null

    private val sharedClient: HttpClient by lazy {
        AirTakHttp.newClient(followRedirects = false).config {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    private val client: HttpClient get() = testClient ?: sharedClient

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, CachedSearch>(4, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, Deferred<List<GeocodingResult>>>()

    private data class CachedSearch(val results: List<GeocodingResult>, val storedAtNanos: Long)

    /** Monotonic, so an NTP correction or a clock change cannot extend or freeze freshness. */
    private fun nowNanos(): Long = System.nanoTime()

    /**
     * Resolve [query].
     *
     * Failures propagate as [GeocodeException]. They used to be converted into an empty list, which
     * made an outage, malformed JSON and a genuine "no such place" indistinguishable to the user, and
     * hid a real bug from me for three red test runs.
     *
     * @throws GeocodeException on transport, HTTP, size or parse failure
     */
    suspend fun search(query: String, limit: Int = 5): List<GeocodingResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = "$trimmed|$limit"

        cached(key)?.let { return it }
        return deduplicated(key) { fetch(key, trimmed, limit) }
    }

    private suspend fun cached(key: String): List<GeocodingResult>? =
        cacheMutex.withLock {
            val entry = cache[key] ?: return@withLock null
            if (nowNanos() - entry.storedAtNanos > CACHE_TTL_NANOS) {
                cache.remove(key)
                null
            } else {
                entry.results
            }
        }

    /**
     * One upstream request per identical concurrent query.
     *
     * The deferred is created lazily and published before it is started. An eager `async` would
     * begin on the current dispatcher immediately, so a caller that lost the race could reach the
     * network before it was cancelled, which is the exact opposite of what deduplication is for.
     */
    private suspend fun deduplicated(
        key: String,
        load: suspend () -> List<GeocodingResult>,
    ): List<GeocodingResult> = coroutineScope {
        val candidate = async(start = CoroutineStart.LAZY) { load() }
        val winner = inFlight.putIfAbsent(key, candidate)
        if (winner != null) {
            // Never started, so cancelling it cannot have touched the network.
            candidate.cancel()
            return@coroutineScope winner.await()
        }
        try {
            candidate.await()
        } finally {
            inFlight.remove(key, candidate)
        }
    }

    private suspend fun fetch(key: String, query: String, limit: Int): List<GeocodingResult> =
        withContext(Dispatchers.IO) {
            budgets.geocoderBudget.acquire()
            val url = buildString {
                append(endpointOverride ?: NETWORK_NOMINATIM_URL)
                append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                append("&format=json&limit=").append(limit).append("&addressdetails=0")
            }

            val payload: ByteArray = try {
                client.prepareGet(url).execute { response -> handleResponse(response) }
            } catch (c: CancellationException) {
                budgets.geocoderBudget.penalize(RETRY_PENALTY_MS)
                throw c
            } catch (g: GeocodeException) {
                throw g
            } catch (t: Throwable) {
                budgets.geocoderBudget.penalize(RETRY_PENALTY_MS)
                throw GeocodeException(GeocodeFailure.Transport(t.message ?: "geocoder request failed"), t)
            }

            val results = try {
                val array = JSONArray(String(payload, Charsets.UTF_8))
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    GeocodingResult(
                        displayName = obj.getString("display_name"),
                        latitude = obj.getString("lat").toDouble(),
                        longitude = obj.getString("lon").toDouble(),
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw GeocodeException(GeocodeFailure.Malformed, t)
            }

            cacheMutex.withLock {
                cache[key] = CachedSearch(results, nowNanos())
                while (cache.size > MAX_CACHE_ENTRIES) {
                    val oldest = cache.keys.minByOrNull { cache.getValue(it).storedAtNanos } ?: break
                    cache.remove(oldest)
                }
            }
            results
        }

    /**
     * Read the status before the body, and cap the body.
     *
     * The previous code called `body<String>()` on whatever came back, so a large HTML error page
     * from a proxy would be buffered in full and then fail to parse as JSON. An error status is also
     * reported as an error rather than being handed to the JSON parser.
     */
    private suspend fun handleResponse(response: HttpResponse): ByteArray {
        val retryAfterMs = parseRetryAfterMs(response.headers[HttpHeaders.RetryAfter])
        if (response.status == HttpStatusCode.TooManyRequests ||
            response.status == HttpStatusCode.ServiceUnavailable ||
            response.status.value >= 500
        ) {
            // Respect the server's own pacing instruction instead of adding a fixed penalty and
            // pretending the header was read.
            budgets.geocoderBudget.penalize(retryAfterMs ?: RETRY_PENALTY_MS)
            throw GeocodeException(GeocodeFailure.Http(response.status.value, retryAfterMs))
        }
        if (!response.status.isSuccess()) {
            throw GeocodeException(GeocodeFailure.Http(response.status.value, null))
        }

        return when (val read = CappedBody.read(response, MAX_PAYLOAD_BYTES)) {
            is CappedBody.Result.Ok -> read.bytes
            CappedBody.Result.TooLarge -> throw GeocodeException(GeocodeFailure.TooLarge)
        }
    }

    /** Drop cached results. */
    suspend fun clearCache() = cacheMutex.withLock { cache.clear() }

    private companion object {
        const val CACHE_TTL_NANOS = 60L * 60L * 1_000_000_000L
        const val MAX_CACHE_ENTRIES = 64
        const val RETRY_PENALTY_MS = 1_000L
        const val REQUEST_TIMEOUT_MS = 15_000L

        /** Real geocoder answers are a few kilobytes; anything larger is an error page, not results. */
        const val MAX_PAYLOAD_BYTES = 256L * 1024L
    }
}
