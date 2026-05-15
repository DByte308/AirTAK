// AirTAK Android — ui/game/GameScreen.kt
// Planning: §11.8, §12.1–12.6 | Roadmap: 5.1–5.6
//
// Displays:
//   - Large HIT button (triggers long-press intent, §12.6)
//   - Respawn countdown timer (large text, counts down)
//   - Game phase indicator (LOBBY / ACTIVE / PAUSED / ENDED)
//   - Admin panel: start/pause/end game, set game mode, manage zones (Phase 5.6)

package com.airtak.ui.game

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun GameScreen(viewModel: GameViewModel = hiltViewModel()) {
    // TODO Phase 5.3: HIT button with long-press gesture detector
    // TODO Phase 5.3: Respawn countdown with cancel button
    // TODO Phase 5.1: Game phase header
    // TODO Phase 5.6: Admin panel (only visible if device is game master)
}
