// AirTAK Android — ui/settings/SettingsScreen.kt
// Planning: §11.9, §14.2 | Roadmap: 3.6

package com.airtak.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Connection status card ─────────────────────────────────────────
        ConnectionStatusCard(connected = uiState.isConnected)

        // ── Error snackbar ─────────────────────────────────────────────────
        uiState.errorMessage?.let { err ->
            Snackbar(action = { TextButton(onClick = viewModel::dismissError) { Text("OK") } }) {
                Text(err)
            }
        }

        if (uiState.isApplying) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // TODO Phase 3.6: expandable config section cards per group (Device, LoRa, Channels…)
        Text(
            text  = "Config management coming in Phase 3.6.",
            color = MaterialTheme.colorScheme.outline,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ConnectionStatusCard(connected: Boolean) {
    val containerColor = if (connected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer

    Surface(color = containerColor, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = if (connected) "Connected to AirTAK device" else "Not connected",
                color = if (connected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
            )
            val indicatorColor = if (connected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
            Badge(containerColor = indicatorColor) { }
        }
    }
}
