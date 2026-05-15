// MASH Android — ui/messages/MessagesScreen.kt
// Planning: §11.6 | Roadmap: 3.2
//
// Displays:
//   - Channel tabs (one per active slot that has messages)
//   - Scrollable message list (LazyColumn) showing sender + message text
//   - Compose field at bottom with byte counter (max 228 bytes per §8.3)
//   - Send via SendMessageUseCase → BLE MESH_TX

package com.mash.ui.messages

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MessagesScreen(viewModel: MessagesViewModel = hiltViewModel()) {
    // TODO Phase 3.2: Channel tab row
    // TODO Phase 3.2: LazyColumn with message items
    // TODO Phase 3.2: OutlinedTextField with character counter and Send button
}
