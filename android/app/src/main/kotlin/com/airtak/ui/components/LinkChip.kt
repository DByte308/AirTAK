// AirTAK Android — ui/components/LinkChip.kt
//
// Persistent link-status chip rendered on top of every screen. Doubles as the
// primary entry point for the pairing wizard: tapping a disconnected chip
// opens it.

package com.airtak.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airtak.data.ble.LinkState
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit
import com.airtak.ui.theme.StatusOffline

@Composable
fun LinkChip(state: LinkState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (dot, label) = describe(state)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(dot, Modifier.size(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun describe(state: LinkState): Pair<Color, String> = when (state) {
    is LinkState.Connected     -> StatusActive to "LINKED"
    is LinkState.Connecting    -> AccentCyan   to "LINKING"
    is LinkState.Reconnecting  -> AccentAmber  to "REACQUIRE"
    is LinkState.Scanning      -> AccentCyan   to "SCANNING"
    is LinkState.Pairing       -> AccentAmber  to "PAIR"
    is LinkState.Failed        -> StatusHit    to "LINK FAIL"
    LinkState.Disconnected     -> StatusOffline to "NO LINK"
}
