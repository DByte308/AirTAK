// AirTAK Android — ui/theme/Color.kt
// Planning: §11.5 (visual cues), §11.8 (HIT button red, status badges)
//
// AirTAK uses a tactical / sci-fi palette. The dark theme is the default and
// intended primary experience: low-photon, glare-free in evening operations.
// The light theme is calibrated for direct-sunlight readability on a phone
// (high luminance backgrounds, high-contrast tactical chips).
//
// Accents are kept identical across themes so that team colors, status chips,
// and alerts read consistently. Only surface, on-surface, and outline shift.

package com.airtak.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand accent ──────────────────────────────────────────────────────────────
// Cyan/teal: HUD readouts, primary interactive surfaces, link-alive indicator.
val AccentCyan        = Color(0xFF00D4E0)
val AccentCyanMuted   = Color(0xFF0099A6)
val AccentCyanGlow    = Color(0xFF66ECF5)

// Amber: caution states (radio-restart warning, pending changes).
val AccentAmber       = Color(0xFFFFB300)
val AccentAmberMuted  = Color(0xFFB37C00)

// Status colors (mirrored across themes — must remain identifiable).
val StatusActive      = Color(0xFF00E676)   // ACTIVE — green
val StatusHit         = Color(0xFFFF4D5E)   // HIT — red
val StatusRespawning  = Color(0xFFFFB300)   // RESPAWNING — amber
val StatusEliminated  = Color(0xFF6E7681)   // ELIMINATED — neutral grey
val StatusOffline     = Color(0xFF424A53)   // OFFLINE — dim grey

// Team default palette (8 slots; admins override per game).
val TeamPalette = listOf(
    Color(0xFFE63946), // Red
    Color(0xFF1D9BF0), // Blue
    Color(0xFFFFB300), // Amber
    Color(0xFF00E676), // Green
    Color(0xFFB388FF), // Violet
    Color(0xFFFF7043), // Orange
    Color(0xFF80CBC4), // Teal
    Color(0xFFF06292), // Pink
)

// ── Dark theme surfaces ───────────────────────────────────────────────────────
val DarkBackground       = Color(0xFF0A0F14)   // deep slate-black; near-OLED-black
val DarkSurface          = Color(0xFF101820)   // raised card / panel
val DarkSurfaceElevated  = Color(0xFF15202B)   // dialog / sheet
val DarkSurfaceVariant   = Color(0xFF1B2A38)   // chip / inactive nav
val DarkOnBackground     = Color(0xFFE6F1F7)
val DarkOnSurface        = Color(0xFFCDD9E1)
val DarkOnSurfaceMuted   = Color(0xFF8B97A1)
val DarkOutline          = Color(0xFF1F2D3A)
val DarkOutlineStrong    = Color(0xFF2C3E4F)

// ── Light theme surfaces ──────────────────────────────────────────────────────
val LightBackground      = Color(0xFFF4F7FA)
val LightSurface         = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFFFFFFF)
val LightSurfaceVariant  = Color(0xFFE4EBF1)
val LightOnBackground    = Color(0xFF0A1620)
val LightOnSurface       = Color(0xFF15202B)
val LightOnSurfaceMuted  = Color(0xFF55636F)
val LightOutline         = Color(0xFFCBD5DF)
val LightOutlineStrong   = Color(0xFF94A3B0)

// Light theme primary is a darker teal for contrast on white.
val AccentCyanDark       = Color(0xFF006B75)
