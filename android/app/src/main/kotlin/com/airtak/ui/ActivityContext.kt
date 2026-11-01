package com.airtak.ui

import android.content.Context
import android.content.ContextWrapper

/** Compose may expose a themed wrapper rather than the activity itself. */
internal fun Context.findMainActivity(): MainActivity? {
    var current = this
    val seen = mutableSetOf<Context>()
    while (seen.add(current)) {
        if (current is MainActivity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}
