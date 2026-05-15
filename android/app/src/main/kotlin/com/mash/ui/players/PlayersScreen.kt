// AirTAK Android — ui/players/PlayersScreen.kt
// Planning: §11.7 | Roadmap: 3.3

package com.airtak.ui.players

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.db.NodeEntity

@Composable
fun PlayersScreen(viewModel: PlayersViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.nodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No nodes heard yet", color = MaterialTheme.colorScheme.outline)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(uiState.nodes, key = { it.nodeId }) { node ->
            NodeItem(
                node       = node,
                selected   = node.nodeId == uiState.selectedNodeId,
                onSelected = { viewModel.onNodeSelected(node.nodeId) },
            )
        }
    }
}

@Composable
private fun NodeItem(
    node: NodeEntity,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Surface(
        color    = containerColor,
        shape    = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = node.callsign, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text  = "RSSI ${node.rssi.toInt()} dBm · SNR ${"%.1f".format(node.snr)} dB",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text     = if (node.latE7 != null) "GPS ✓" else "GPS ?",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
