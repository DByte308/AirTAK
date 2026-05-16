// AirTAK Android — ui/theme/Type.kt
//
// Typography tuned for field use: high x-height, generous tracking on labels,
// monospace for tactical readouts (callsigns, coordinates, RSSI/SNR). All
// sizes are slightly larger than Material defaults to remain glanceable while
// the device is in a chest rig or held with gloves.

package com.airtak.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Display = FontFamily.SansSerif      // system sans serif (Roboto on most devices)
val Mono = FontFamily.Monospace                 // tactical readouts (callsigns, RSSI, etc.)

val AirTakTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Light,
        fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    // Wide-tracked uppercase used for HUD section labels.
    labelLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.sp
    ),
)

// Monospace text style for tactical readouts (RSSI, SNR, lat/lng, node IDs).
val TacticalReadout = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.5.sp,
)
