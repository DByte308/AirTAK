package com.airtak.ui.map

import com.airtak.R
import com.airtak.data.geocoding.GeocodeFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The geocoder failure → resource mapping.
 *
 * `asText()` needs a Composition, so what is testable on the JVM is the mapping from a typed
 * failure to the resource it renders from. That is the half that can go wrong silently: a new
 * `GeocodeFailure` case would otherwise fall through to a generic message, and an
 * `Http(retryAfterMs = …)` that lost its branch would tell the user "HTTP 429" instead of how
 * long to wait.
 */
class GeocodeErrorTextTest {

    @Test
    fun httpWithoutRetryAfterNamesTheStatus(): Unit =
        assertEquals(R.string.geocode_err_http, GeocodeFailure.Http(status = 503, retryAfterMs = null).messageRes())

    @Test
    fun httpWithRetryAfterPromisesAWaitInstead(): Unit =
        assertEquals(
            R.string.geocode_err_http_busy,
            GeocodeFailure.Http(status = 429, retryAfterMs = 7_000L).messageRes(),
        )

    @Test
    fun zeroRetryAfterStillUsesTheWaitMessage(): Unit =
        assertEquals(
            R.string.geocode_err_http_busy,
            GeocodeFailure.Http(status = 429, retryAfterMs = 0L).messageRes(),
            "0 is a real instruction (retry now), not an absent one",
        )

    @Test
    fun malformedAndTooLargeAreDistinct() {
        assertEquals(R.string.geocode_err_malformed, GeocodeFailure.Malformed.messageRes())
        assertEquals(R.string.geocode_err_too_large, GeocodeFailure.TooLarge.messageRes())
    }

    @Test
    fun transportKeepsItsOwnMessage(): Unit = assertEquals(
        R.string.geocode_err_transport,
        GeocodeFailure.Transport("connection refused").messageRes(),
    )
}
