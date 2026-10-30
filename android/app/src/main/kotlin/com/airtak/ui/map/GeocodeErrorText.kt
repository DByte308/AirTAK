// AirTAK Android — ui/map/GeocodeErrorText.kt
//
// Turns a typed geocoder failure into the text the search dialog shows.
//
// It lives here rather than inside GeocodingRepository because the repository is a data
// layer class with no access to resources, and inside MapViewModel for the same reason:
// a ViewModel that builds its own English sentence produces an error that can never be
// translated (issue #11). The repository reports *what happened*; this file is the only
// place that decides how it reads.

package com.airtak.ui.map

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.data.geocoding.GeocodeFailure

/** The resource a failure renders from, plus whether it carries an argument. */
@StringRes
fun GeocodeFailure.messageRes(): Int = when (this) {
    is GeocodeFailure.Http ->
        if (retryAfterMs != null) R.string.geocode_err_http_busy else R.string.geocode_err_http
    GeocodeFailure.Malformed -> R.string.geocode_err_malformed
    GeocodeFailure.TooLarge -> R.string.geocode_err_too_large
    is GeocodeFailure.Transport -> R.string.geocode_err_transport
}

@Composable
fun GeocodeFailure.asText(): String = when (this) {
    is GeocodeFailure.Http ->
        if (retryAfterMs != null) {
            // Rounded up so a 300 ms Retry-After does not read as "try again in 0 s".
            stringResource(R.string.geocode_err_http_busy, ((retryAfterMs + 999L) / 1_000L))
        } else {
            stringResource(R.string.geocode_err_http, status)
        }
    GeocodeFailure.Malformed -> stringResource(R.string.geocode_err_malformed)
    GeocodeFailure.TooLarge -> stringResource(R.string.geocode_err_too_large)
    is GeocodeFailure.Transport -> stringResource(R.string.geocode_err_transport, message)
}
