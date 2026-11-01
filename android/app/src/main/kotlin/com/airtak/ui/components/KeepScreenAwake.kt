package com.airtak.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** A single owner restores the previous view flag on completion, error or disposal. */
internal class ScreenAwakeLease(read: () -> Boolean, private val write: (Boolean) -> Unit) : AutoCloseable {
    private val previous = read()
    private var closed = false

    init { write(true) }

    override fun close() {
        if (!closed) {
            closed = true
            write(previous)
        }
    }
}

/** Android only honors keepScreenOn while this app is foreground; no wake lock is acquired. */
@Composable
internal fun KeepScreenAwake(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val lease = if (enabled) ScreenAwakeLease({ view.keepScreenOn }, { view.keepScreenOn = it }) else null
        onDispose { lease?.close() }
    }
}
