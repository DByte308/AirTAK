package com.airtak.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airtak.R

/**
 * Why the loopback tile listener is not serving, in a form that is safe to put on screen.
 *
 * The reason string originates as an exception message, so it can contain a bind address, a
 * provider host, or text copied out of a stack frame. None of that belongs in a UI string: it is
 * noise to the person holding the phone and it can leak infrastructure detail into a screenshot
 * they might send to someone. The full reason stays in the diagnostic log.
 *
 * Returns null only for a null input, so a caller can distinguish "no problem" from "a problem
 * whose detail sanitised away".
 */
internal fun tileServerProblemDetail(reason: String?): String? {
    if (reason == null) return null
    val flattened = reason
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace('\t', ' ')
        .replace('\u0000', ' ')
        .replace(URL_LIKE, "[address]")
        .replace(HOST_PORT, "[address]")
        .replace(STACK_FRAME, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (flattened.isEmpty() || flattened == "[address]") return null
    return if (flattened.length <= MAX_DETAIL_CHARS) {
        flattened
    } else {
        flattened.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
    }
}

private const val MAX_DETAIL_CHARS = 120

/** Anything that looks like a scheme-qualified URL, including a redacted userinfo section. */
private val URL_LIKE = Regex("(?i)\\b[a-z][a-z0-9+.\\-]{1,15}://\\S*")

/**
 * A `\tat com.foo.Bar.baz(Bar.kt:12)` frame. An exception message that carried a trace should not
 * put one on screen; the headline plus the first line of the reason is the whole useful payload.
 */
private val STACK_FRAME = Regex("(?i)\\bat\\s+[\\w$.]+\\([^)]*\\)")

/** `host:port` or IPv4:port, which is what a bind failure actually tends to name. */
private val HOST_PORT = Regex("(?i)\\b([a-z0-9\\-.]+\\.[a-z]{2,}|\\d{1,3}(\\.\\d{1,3}){3}|\\[?[0-9a-f:]{4,45}?\\]?):\\d{1,5}\\b")

/**
 * A bounded notice that tiles are not being served.
 *
 * Deliberately not a dialog and deliberately without a retry control. `TileRepository` exposes no
 * safe public restart — `ensureServer()` is private and `shutdown()` tears the listener down — so a
 * retry button here would have to invent a lifecycle path that has not been reviewed. The message
 * states the condition; the next `ensureServer()` from normal operation is what retries.
 */
@Composable
fun TileServerProblemBanner(
    reason: String?,
    modifier: Modifier = Modifier,
) {
    if (reason == null) return
    val detail = tileServerProblemDetail(reason)
    // Placement is the caller's: `align` only exists inside the parent BoxScope, so the composable
    // that hosts this one passes its own aligned modifier in.
    Box(
        modifier = modifier
            .padding(top = 8.dp, start = 16.dp, end = 16.dp)
            .widthIn(max = 360.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.map_tile_server_problem),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
