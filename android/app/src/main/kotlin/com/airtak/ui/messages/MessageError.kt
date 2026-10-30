// AirTAK Android — ui/messages/MessageError.kt
//
// Issue #11: both message ViewModels used to publish a finished English sentence in their
// `error` state ("Too long: 240 B exceeds 237 B LoRa limit."). A ViewModel cannot call
// `stringResource`, so anything it composes itself is untranslatable by construction.
//
// The state now carries what happened, and this file is the only place that turns it into
// words.

package com.airtak.ui.messages

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.config.constants.MESH_MAX_TEXT_BYTES

/** A user-visible failure on the message screens, described rather than phrased. */
sealed interface MessageError {
    /** The draft is over the LoRa text limit. */
    data class TooLong(val attemptedBytes: Int) : MessageError
}

/** The resource the [MessageError] renders from. Kept next to the type so they cannot drift. */
@StringRes
fun MessageError.titleRes(): Int = when (this) {
    is MessageError.TooLong -> R.string.msg_too_long_bytes
}

@Composable
fun MessageError.asText(): String = when (this) {
    is MessageError.TooLong ->
        stringResource(R.string.msg_too_long_bytes, attemptedBytes, MESH_MAX_TEXT_BYTES)
}
