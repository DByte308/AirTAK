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
        context.getSharedPreferences("airtak.prefs", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.System.name) ?: ThemeMode.System.name)
    }.getOrDefault(ThemeMode.System)

    fun setThemeMode(mode: ThemeMode) { prefs.edit().putString(KEY_THEME, mode.name).apply() }

    fun tileServerUrl(): String? = prefs.getString(KEY_TILE_URL, null)
    fun setTileServerUrl(url: String?) { prefs.edit().putString(KEY_TILE_URL, url).apply() }

    fun bondedAddress(): String? = prefs.getString(KEY_BONDED_ADDR, null)
    fun setBondedAddress(addr: String?) { prefs.edit().putString(KEY_BONDED_ADDR, addr).apply() }

    fun defaultMapZoom(): Double = prefs.getFloat(KEY_MAP_ZOOM, 13f).toDouble()
    fun setDefaultMapZoom(z: Double) { prefs.edit().putFloat(KEY_MAP_ZOOM, z.toFloat()).apply() }

    fun unitChannelSlot(level: String): Int =
        prefs.getInt("unit_channel_$level", -1)

    fun setUnitChannelSlot(level: String, slot: Int) {
        prefs.edit().putInt("unit_channel_$level", slot).apply()
    }

    fun observe(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_TILE_URL = "tile_url"
        const val KEY_BONDED_ADDR = "bonded_addr"
        const val KEY_MAP_ZOOM = "map_zoom"
    }
}
