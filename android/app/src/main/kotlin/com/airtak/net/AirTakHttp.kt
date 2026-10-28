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
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

object AirTakHttp {

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
     */
    fun newClient(
        userAgent: String = userAgent(),
        followRedirects: Boolean = true,
    ): HttpClient = HttpClient(Android) {
        this.followRedirects = followRedirects
        defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
    }

    /** Test seam: same configuration over an injected engine (MockEngine in the unit tests). */
    fun newClient(
        userAgent: String,
        engine: HttpClientEngine,
        followRedirects: Boolean = true,
    ): HttpClient = HttpClient(engine) {
        this.followRedirects = followRedirects
        defaultRequest { headers.append(HttpHeaders.UserAgent, userAgent) }
    }
}
