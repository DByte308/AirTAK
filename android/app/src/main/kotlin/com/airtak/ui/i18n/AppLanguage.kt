// AirTAK Android — ui/i18n/AppLanguage.kt
//
// App language selection.
//
// MainActivity is a plain ComponentActivity, not an AppCompatActivity, so
// AppCompatDelegate.setApplicationLocales() is not available (and the AndroidX
// per-app-locale backport would drag in appcompat anyway). On API < 33 there is
// no platform per-app locale API at all, so we apply the locale ourselves by
// wrapping the base context in attachBaseContext and recreating on change.

package com.airtak.ui.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

import com.airtak.config.constants.APP_PREFS_FILE
import com.airtak.config.constants.KEY_LANGUAGE

enum class AppLanguage(val tag: String?, val nativeName: String) {
    /** Follow the system / device language. */
    System(null, "System standard"),
    En("en", "English"),
    De("de", "Deutsch"),
}

/**
 * The language currently persisted, or [AppLanguage.System].
 *
 * Reads SharedPreferences directly rather than the injected AppPreferences:
 * attachBaseContext runs before Hilt injects the activity, so the singleton is
 * not available that early.
 */
fun Context.appLanguage(): AppLanguage {
    val raw = runCatching {
        getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguage.System.name)
    }.getOrNull()
    return AppLanguage.entries.firstOrNull { it.name == raw } ?: AppLanguage.System
}

/**
 * Return a context whose resources resolve in the user's chosen language.
 *
 * Returns the receiver untouched when following the system language, so the
 * common case costs nothing.
 */
fun Context.withAppLanguage(): Context {
    val tag = appLanguage().tag ?: return this
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(config)
}
