// AirTAK Android — ui/theme/Theme.kt
//
// Material 3 themes for AirTAK. Theme selection is a user setting (System /
// Dark / Light) read from AppPreferences. We deliberately ignore Material You
// dynamic color: field UX must be predictable across phones and the team /
// status palette must not be re-tinted by a user's wallpaper.

package com.airtak.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode { System, Dark, Light }

private val DarkScheme = darkColorScheme(
    primary            = AccentCyan,
    onPrimary          = Color(0xFF002429),
    primaryContainer   = AccentCyanMuted,
    onPrimaryContainer = Color(0xFFD8FBFF),
    secondary          = AccentAmber,
    onSecondary        = Color(0xFF2A1B00),
    secondaryContainer = AccentAmberMuted,
    onSecondaryContainer = Color(0xFFFFE7B3),
    tertiary           = StatusActive,
    error              = StatusHit,
    background         = DarkBackground,
    onBackground       = DarkOnBackground,
    surface            = DarkSurface,
    onSurface          = DarkOnSurface,
    surfaceVariant     = DarkSurfaceVariant,
    onSurfaceVariant   = DarkOnSurfaceMuted,
    surfaceContainer   = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceElevated,
    outline            = DarkOutline,
    outlineVariant     = DarkOutlineStrong,
    scrim              = Color(0xCC000000),
)

private val LightScheme = lightColorScheme(
    primary            = AccentCyanDark,
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFB8F5FB),
    onPrimaryContainer = Color(0xFF002429),
    secondary          = Color(0xFF8B6300),
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE7B3),
    onSecondaryContainer = Color(0xFF2A1B00),
    tertiary           = Color(0xFF006D3A),
    error              = Color(0xFFB3203A),
    background         = LightBackground,
    onBackground       = LightOnBackground,
    surface            = LightSurface,
    onSurface          = LightOnSurface,
    surfaceVariant     = LightSurfaceVariant,
    onSurfaceVariant   = LightOnSurfaceMuted,
    surfaceContainer   = LightSurfaceElevated,
    surfaceContainerHigh = LightSurfaceElevated,
    outline            = LightOutline,
    outlineVariant     = LightOutlineStrong,
)

@Composable
fun AirTakTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark   -> true
        ThemeMode.Light  -> false
    }
    val scheme = if (dark) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AirTakTypography,
        content = content,
    )
}
