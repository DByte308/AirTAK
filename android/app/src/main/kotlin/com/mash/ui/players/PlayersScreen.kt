// MASH Android — ui/players/PlayersScreen.kt
// Planning: §11.7, §12.1 | Roadmap: 3.3, 5.2
//
// Displays all known players grouped by team.
// Each player chip shows callsign, status (ACTIVE/HIT/RESPAWNING/ELIMINATED), and online indicator.
// Admin controls: set respawn timer, eliminate player (admin PSK required).
// Filters: show only online players.

package com.mash.ui.players

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun PlayersScreen(viewModel: PlayersViewModel = hiltViewModel()) {
    // TODO Phase 3.3: Team grouping with LazyColumn sections
    // TODO Phase 5.2: Status chips per player
    // TODO Phase 5.4: Admin control sheet (bottom sheet, requires admin PSK confirmation)
}
