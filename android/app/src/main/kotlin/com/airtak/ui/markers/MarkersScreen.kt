// AirTAK Android — ui/markers/MarkersScreen.kt
// Phase 5: list of tactical markers visible to this player. Repurposes the
// former "Game" tab. Tap a row to focus the marker on the Map tab; long-press
// to delete (owner only). The list is sorted by recency.

package com.airtak.ui.markers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.proto.MarkerType
import com.airtak.proto.Scope
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.theme.StatusActive

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkersScreen(
    viewModel: MarkersViewModel = hiltViewModel(),
    onLocateMarker: (Long) -> Unit,
) {
    val markers by viewModel.markers.collectAsState()
    var pendingDelete by remember { mutableStateOf<TacticalMarkerEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionLabel("Markers")
        Text(
            "${markers.size} visible",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (markers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No tactical markers in your channels yet.\nUse the Map tab to drop a point, route, or area.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(markers, key = { it.markerId }) { marker ->
                    MarkerRow(
                        marker = marker,
                        onClick = { onLocateMarker(marker.markerId) },
                        onLongClick = {
                            if (marker.ownedLocally) pendingDelete = marker
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { marker ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete marker?") },
            text = {
                Text("Broadcasts a tombstone so other players will also remove \"${marker.label.ifBlank { "(unnamed)" }}\".")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteMarker(marker.markerId)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerRow(
    marker: TacticalMarkerEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF000000L or (marker.colorRgb.toLong() and 0xFFFFFFL))),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    marker.label.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val type = runCatching { MarkerType.forNumber(marker.typeValue) }.getOrNull()
                val scope = runCatching { Scope.forNumber(marker.scopeValue) }.getOrNull()
                Text(
                    "${typeLabel(type)} • ${scopeLabel(scope)} • ${marker.points().size} pt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (marker.description.isNotBlank()) {
                    Text(
                        marker.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (marker.ownedLocally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (marker.delivered) Icons.Outlined.Done else Icons.Outlined.Schedule,
                            contentDescription = if (marker.delivered) "Delivered" else "Pending mesh delivery",
                            modifier = Modifier.size(13.dp),
                            tint = if (marker.delivered) StatusActive else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "You",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Router,
                            contentDescription = "Received via mesh",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "Node ${marker.creatorNodeId.toString(16).uppercase().take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: MarkerType?): String = when (type) {
    MarkerType.MARKER_TYPE_POINT -> "Point"
    MarkerType.MARKER_TYPE_NOTE -> "Note"
    MarkerType.MARKER_TYPE_OBJECTIVE -> "Objective"
    MarkerType.MARKER_TYPE_ROUTE -> "Route"
    MarkerType.MARKER_TYPE_AREA -> "Area"
    else -> "Marker"
}

private fun scopeLabel(scope: Scope?): String = when (scope) {
    Scope.SCOPE_ALL -> "All"
    Scope.SCOPE_COMPANY -> "Company"
    Scope.SCOPE_PLATOON -> "Platoon"
    Scope.SCOPE_SQUAD -> "Squad"
    Scope.SCOPE_FIRE_TEAM -> "Fireteam"
    else -> "All"
}
