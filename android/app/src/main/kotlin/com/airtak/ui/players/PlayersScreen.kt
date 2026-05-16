// AirTAK Android — ui/players/PlayersScreen.kt
// Roadmap: 4.x placeholder so the nav destination is reachable in Phase 3.

package com.airtak.ui.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel

@Composable
fun PlayersScreen() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel("Roster")
        Text(
            "Activated in Phase 4",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        HudCard(Modifier.padding(top = 16.dp)) {
            Text(
                "Live mesh participants, last-seen timestamps, and per-node RSSI will appear here once Phase 4 ships.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
