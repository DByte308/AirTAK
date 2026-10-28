// AirTAK Android — data/prefs/AppPreferences.kt
//
// Lightweight DataStore-less preferences via SharedPreferences. Stores:
//   - selected theme mode
//   - tile server URL (configurable per §11.5; no hardcoded default URL shipped)
//   - bonded device address (last-known target for auto-reconnect)
//   - default map zoom
//
// Sensitive material (PSKs, BLE LTKs) is NOT stored here — PSKs are pulled
// from the device via CONFIG_RW on demand; bond keys live in the OS BLE stack.

package com.airtak.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.airtak.config.constants.APP_PREFS_FILE
import com.airtak.config.constants.KEY_BONDED_ADDR
import com.airtak.config.constants.KEY_LANGUAGE
import com.airtak.config.constants.KEY_MAP_ZOOM
import com.airtak.config.constants.KEY_THEME
import com.airtak.config.constants.KEY_TILE_URL
import com.airtak.config.constants.KEY_PREFIX_UNIT_CHANNEL
import com.airtak.config.constants.MAP_ZOOM_DEFAULT
import com.airtak.config.constants.UNIT_CHANNEL_SLOT_DEFAULT
import com.airtak.ui.i18n.AppLanguage
import com.airtak.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(APP_PREFS_FILE, Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.System.name) ?: ThemeMode.System.name)
    }.getOrDefault(ThemeMode.System)

    fun setThemeMode(mode: ThemeMode) { prefs.edit().putString(KEY_THEME, mode.name).apply() }

    /** App language override. [AppLanguage.System] follows the device setting. */
    fun language(): AppLanguage =
        AppLanguage.entries.firstOrNull {
            it.name == prefs.getString(KEY_LANGUAGE, AppLanguage.System.name)
        } ?: AppLanguage.System

    fun setLanguage(lang: AppLanguage) { prefs.edit().putString(KEY_LANGUAGE, lang.name).apply() }

    fun tileServerUrl(): String? = prefs.getString(KEY_TILE_URL, null)
    fun setTileServerUrl(url: String?) { prefs.edit().putString(KEY_TILE_URL, url).apply() }

    fun bondedAddress(): String? = prefs.getString(KEY_BONDED_ADDR, null)
    fun setBondedAddress(addr: String?) { prefs.edit().putString(KEY_BONDED_ADDR, addr).apply() }

    fun defaultMapZoom(): Double = prefs.getFloat(KEY_MAP_ZOOM, MAP_ZOOM_DEFAULT).toDouble()
    fun setDefaultMapZoom(z: Double) { prefs.edit().putFloat(KEY_MAP_ZOOM, z.toFloat()).apply() }

    fun unitChannelSlot(level: String): Int =
        prefs.getInt("${KEY_PREFIX_UNIT_CHANNEL}$level", UNIT_CHANNEL_SLOT_DEFAULT)

    fun setUnitChannelSlot(level: String, slot: Int) {
        prefs.edit().putInt("${KEY_PREFIX_UNIT_CHANNEL}$level", slot).apply()
    }

    fun observe(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
