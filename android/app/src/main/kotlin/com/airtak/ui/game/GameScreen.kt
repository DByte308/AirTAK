// AirTAK Android — ui/game/GameScreen.kt
// Roadmap: 5.x placeholder so the nav destination is reachable in Phase 3.

package com.airtak.ui.game

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
fun GameScreen() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionLabel("Mission")
        Text(
            "Activated in Phase 5",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        HudCard(Modifier.padding(top = 16.dp)) {
            Text(
                "Zones, objectives, hit-tracking and admin controls will appear here once Phase 5 ships.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
