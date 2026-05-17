// AirTAK Android — ui/components/HudComponents.kt
//
// A small kit of reusable Compose primitives tuned for AirTAK's tactical /
// sci-fi visual language. Every primitive supports both light and dark themes
// by reading from MaterialTheme.colorScheme; only the AirTAK accents
// (StatusActive, StatusHit, AccentCyan etc.) are pinned across themes.

package com.airtak.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airtak.ui.theme.TacticalReadout

/**
 * A surface card with the AirTAK look: rounded corners, single-pixel cyan
 * accent across the top, and a soft container fill.
 */
@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Box(
            Modifier
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.9f), Color.Transparent),
                    ),
                ),
        )
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun TacticalText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, singleLine: Boolean = false) {
    if (singleLine) {
        Text(text = text, style = TacticalReadout, color = color, modifier = modifier, overflow = TextOverflow.Ellipsis, maxLines = 1)
    } else {
        Text(text = text, style = TacticalReadout, color = color, modifier = modifier)
    }
}

/**
 * A small status dot with optional inner ring; used by the link chip, GPS
 * indicator and similar status badges.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
}

/**
 * Metric tile — a label-over-value HUD pill. Useful in the link bar and
 * About screen.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = TacticalReadout,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Row of metric tiles with equal weight. */
@Composable
fun MetricRow(vararg tiles: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tiles.forEach { (l, v) -> MetricTile(l, v, Modifier.weight(1f)) }
    }
}
