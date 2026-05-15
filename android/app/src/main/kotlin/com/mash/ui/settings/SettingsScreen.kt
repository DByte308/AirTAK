// MASH Android — ui/settings/SettingsScreen.kt
// Planning: §11.9, §14.2 | Roadmap: 3.6, 2.8
//
// Displays all 8 config sections as expandable cards:
//   Device, LoRa, Channels, Position, Power, Game, Security, Audio (Phase 7).
// Uses staged apply: "Apply" writes to device with commit=false for validation first.
// Radio-restart indicator shown when LORA or CHANNELS groups are modified.

package com.mash.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    // TODO Phase 3.6: 8 expandable config section cards (LazyColumn)
    // TODO Phase 2.8: "Apply" button with staged commit flow
    // TODO Phase 2.8: Radio restart progress indicator
}
