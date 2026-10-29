package com.airtak.net

// Single place where every outbound HTTP client is built.
//
// Two reasons this exists:
//   1. Identification. The OSM tile usage policy and the Nominatim usage policy both require a
//      descriptive User-Agent that says which application is calling and how to reach its author.
//      All three previous clients were bare HttpClient(Android) and sent no User-Agent at all.
//   2. Redirect control. A bulk tile download that follows redirects can be moved onto a host
//      TileProviderPolicy has already refused, after the check ran. Tile and geocoder clients
//      therefore disable redirects; the caller sees a 3xx as a failure.
//
// Routing keeps redirects enabled on purpose. Changing its redirect or timeout behaviour is out
// of scope for this fix, which is why followRedirects is a parameter rather than a global.

import com.airtak.BuildConfig
import com.airtak.config.constants.NET_CONNECT_TIMEOUT_MS
import com.airtak.config.constants.NET_REQUEST_TIMEOUT_MS
import com.airtak.config.constants.NET_SOCKET_TIMEOUT_MS
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

object AirTakHttp {

    /**
     * The time bounds the production factory installs, named so a test can assert them.
     *
     * Ktor keeps the installed plugin's own properties private, so a test cannot read them back off
     * the client. Rather than reach for reflection, the values are declared once here and the
     * behaviour is proven separately against a real stalled socket: a configured phase aborts, and
     * the same client with the phases unset does not. Together those two show the configuration is
     * both declared and installed.
     */
    data class Timeouts(
        val connectMs: Long?,
        val socketMs: Long?,
        val requestMs: Long?,
    )

    val DEFAULT_TIMEOUTS = Timeouts(
        connectMs = NET_CONNECT_TIMEOUT_MS,
        socketMs = NET_SOCKET_TIMEOUT_MS,
        requestMs = NET_REQUEST_TIMEOUT_MS,
    )

    /** Real, existing contact address for the User-Agent. No invented email address. */
    const val CONTACT_URL = "https://github.com/DByte308/AirTAK"

    /**
     * Descriptive UA in the form the OSM usage policy asks for: app, version, and a URL a provider
     * can read to find out who we are and what this project is.
     */
    fun userAgent(appVersion: String? = BuildConfig.VERSION_NAME): String =
        "AirTAK/${appVersion?.takeIf { it.isNotBlank() } ?: "dev"} (+$CONTACT_URL)"

    /**
     * Production client. [followRedirects] defaults to true so existing callers keep their current
     * behaviour; pass false where a redirect could carry a request past a policy check.
     *
     * Every client built here carries explicit time bounds. Leaving them unset does not mean
     * unbounded — it means the engine's implicit defaults apply, which differ per engine, are not
     * stated anywhere in this source tree, and are long enough that a stalled provider reads as a
     * frozen map rather than as a failed request.
     */
    fun newClient(
        userAgent: String = userAgent(),
        followRedirects: Boolean = true,
        connectTimeoutMs: Long? = DEFAULT_TIMEOUTS.connectMs,
        socketTimeoutMs: Long? = DEFAULT_TIMEOUTS.socketMs,
        requestTimeoutMs: Long? = DEFAULT_TIMEOUTS.requestMs,
    ): HttpClient = HttpClient(Android) {
        configure(this, userAgent, followRedirects, connectTimeoutMs, socketTimeoutMs, requestTimeoutMs)
    }

    /**
     * Test seam: the same configuration over an injected engine.
     *
     * The configuration is shared with the production factory on purpose. A seam configured
     * differently would test a client that does not ship — which is how the redirect claim in this
     * slice was wrong once already.
     */
    fun newClient(
        userAgent: String = userAgent(),
        engine: HttpClientEngine,
        followRedirects: Boolean = true,
        connectTimeoutMs: Long? = DEFAULT_TIMEOUTS.connectMs,
        socketTimeoutMs: Long? = DEFAULT_TIMEOUTS.socketMs,
        requestTimeoutMs: Long? = DEFAULT_TIMEOUTS.requestMs,
    ): HttpClient = HttpClient(engine) {
        configure(this, userAgent, followRedirects, connectTimeoutMs, socketTimeoutMs, requestTimeoutMs)
    }

    private fun configure(
        client: HttpClientConfig<*>,
        userAgent: String,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        socketTimeoutMs: Long?,
        requestTimeoutMs: Long?,
    ) {
        client.followRedirects = followRedirects
        client.defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
        client.install(HttpTimeout) {
            // null means "leave this phase unset", which is what a virtual-time test needs: under
            // runTest the coroutine clock is skipped forward, so a real deadline fires instantly and
            // every policy test would report a timeout instead of the policy outcome it is testing.
            // Production passes non-null values, and AirTakHttpTimeoutTest asserts that.
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
        }
    }
}
