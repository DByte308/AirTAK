// AirTAK Android — ui/markers/MarkersScreen.kt
// Phase 5: filterable, searchable list of tactical markers with type icons,
// creator names, timestamps, swipe-to-delete, and objective team coloring.

package com.airtak.ui.markers

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.proto.MarkerType
import com.airtak.proto.Scope
import com.airtak.proto.TeamControl
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.theme.StatusActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarkersScreen(
    viewModel: MarkersViewModel = hiltViewModel(),
    onLocateMarker: (Long) -> Unit,
) {
    val markers by viewModel.markers.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val remoteNodes by viewModel.remoteNodeIds.collectAsState()
    var pendingDelete by remember { mutableStateOf<TacticalMarkerEntity?>(null) }
    var showSnapshotPicker by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Markers")
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showSnapshotPicker = true }) {
                    Icon(Icons.Outlined.AddLocationAlt, contentDescription = "Request marker snapshot")
                }
                DropdownMenu(
                    expanded = showSnapshotPicker,
                    onDismissRequest = { showSnapshotPicker = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All players (broadcast)") },
                        onClick = {
                            showSnapshotPicker = false
                            viewModel.syncNow()
                        },
                    )
                    remoteNodes.forEach { node ->
                        DropdownMenuItem(
                            text = { Text("Request from ${node.callsign.ifBlank { node.shortName }}") },
                            onClick = {
                                showSnapshotPicker = false
                                viewModel.requestMarkerSnapshot(node.nodeId)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.syncNow() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Sync markers and messages")
            }
        }

        // Search and filter row
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search markers…", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotBlank()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Outlined.Cancel, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )
            Box {
                IconButton(onClick = { showFilterMenu = true }) {
                    Icon(
                        Icons.Outlined.FilterList,
                        contentDescription = "Filter",
                        tint = if (filter.typeFilter != null || filter.scopeFilter != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showFilterMenu,
                    onDismissRequest = { showFilterMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Clear all filters") },
                        leadingIcon = { Icon(Icons.Outlined.Cancel, contentDescription = null) },
                        onClick = {
                            viewModel.clearFilters()
                            showFilterMenu = false
                        },
                    )
                }
            }
        }

        // Active filter chips row
        val activeType = filter.typeFilter
        val activeScope = filter.scopeFilter
        if (activeType != null || activeScope != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (activeType != null) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.setTypeFilter(null) },
                        label = { Text("Type: ${typeLabel(activeType)}") },
                    )
                }
                if (activeScope != null) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.setScopeFilter(null) },
                        label = { Text("Scope: ${scopeLabel(activeScope)}") },
                    )
                }
            }
        }

        // Type filter chips
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MarkerType.values().filter { it != MarkerType.UNRECOGNIZED }.forEach { type ->
                FilterChip(
                    selected = filter.typeFilter == type,
                    onClick = { viewModel.setTypeFilter(if (filter.typeFilter == type) null else type) },
                    label = { Text(typeLabel(type), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            typeIcon(type),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                )
            }
        }

        // Scope filter chips
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Scope.values().filter { it != Scope.UNRECOGNIZED }.forEach { scope ->
                FilterChip(
                    selected = filter.scopeFilter == scope,
                    onClick = { viewModel.setScopeFilter(if (filter.scopeFilter == scope) null else scope) },
                    label = { Text(scopeLabel(scope), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text(
            "${markers.size} visible",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        if (markers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val msg = if (filter.query.isNotBlank() || activeType != null || activeScope != null)
                    "No markers match your filters."
                else
                    "No tactical markers in your channels yet.\nUse the Map tab to drop a point, route, or area."
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(markers, key = { it.markerId }) { marker ->
                    if (marker.ownedLocally) {
                        SwipeableMarkerRow(
                            marker = marker,
                            creatorName = viewModel.creatorName(marker),
                            onClick = { onLocateMarker(marker.markerId) },
                            onLongClick = { if (marker.ownedLocally) pendingDelete = marker },
                            onSwipeDelete = {
                                if (marker.ownedLocally) pendingDelete = marker
                            },
                        )
                    } else {
                        MarkerRow(
                            marker = marker,
                            creatorName = viewModel.creatorName(marker),
                            onClick = { onLocateMarker(marker.markerId) },
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMarkerRow(
    marker: TacticalMarkerEntity,
    creatorName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete()
                false // Don't actually dismiss; show dialog instead
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = MaterialTheme.colorScheme.errorContainer,
                label = "swipe-delete-color",
            )
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        MarkerRow(
            marker = marker,
            creatorName = creatorName,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerRow(
    marker: TacticalMarkerEntity,
    creatorName: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val type = runCatching { MarkerType.forNumber(marker.typeValue) }.getOrNull()
    val scope = runCatching { Scope.forNumber(marker.scopeValue) }.getOrNull()
    val team = runCatching { TeamControl.forNumber(marker.controllingTeamValue) }.getOrNull()
    val isObjective = type == MarkerType.MARKER_TYPE_OBJECTIVE

    val iconColor = when {
        isObjective && team != null -> teamColor(team)
        else -> Color(0xFF000000L or (marker.colorRgb.toLong() and 0xFFFFFFL))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (onLongClick != null) it.combinedClickable(onClick = onClick, onLongClick = onLongClick) else it.combinedClickable(onClick = onClick) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = typeIcon(type),
                contentDescription = typeLabel(type),
                modifier = Modifier.size(20.dp),
                tint = iconColor,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    marker.label.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        typeLabel(type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ScopeBadge(scope)
                    Text(
                        "${marker.points().size} pt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (marker.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        marker.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (marker.ownedLocally) {
                        Icon(
                            imageVector = if (marker.delivered) Icons.Outlined.Done else Icons.Outlined.Schedule,
                            contentDescription = if (marker.delivered) "Delivered" else "Pending mesh delivery",
                            modifier = Modifier.size(13.dp),
                            tint = if (marker.delivered) StatusActive else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Router,
                            contentDescription = "Received via mesh",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        creatorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (marker.ownedLocally) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(80.dp),
                    )
                }
                // Last-modified timestamp
                Text(
                    formatTimestamp(marker.updatedAtUnix),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                if (isObjective && team != null) {
                    Surface(
                        color = teamColor(team).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            teamLabel(team),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = teamColor(team),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeBadge(scope: Scope?) {
    if (scope == null || scope == Scope.SCOPE_ALL || scope == Scope.UNRECOGNIZED) return
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            scopeLabel(scope).take(3).uppercase(),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun typeIcon(type: MarkerType?): ImageVector = when (type) {
    MarkerType.MARKER_TYPE_POINT -> Icons.Outlined.Place
    MarkerType.MARKER_TYPE_NOTE -> Icons.Outlined.Note
    MarkerType.MARKER_TYPE_OBJECTIVE -> Icons.Outlined.Flag
    MarkerType.MARKER_TYPE_ROUTE -> Icons.Outlined.Route
    MarkerType.MARKER_TYPE_AREA -> Icons.Outlined.SelectAll
    else -> Icons.Outlined.MyLocation
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
    Scope.SCOPE_ALL -> "All players"
    Scope.SCOPE_COMPANY -> "Company"
    Scope.SCOPE_PLATOON -> "Platoon"
    Scope.SCOPE_SQUAD -> "Squad"
    Scope.SCOPE_FIRE_TEAM -> "Fireteam"
    else -> "All players"
}

private fun teamColor(team: TeamControl): Color = when (team) {
    TeamControl.TEAM_CONTROL_OWN -> Color(0xFF00E676)
    TeamControl.TEAM_CONTROL_OPPOSING -> Color(0xFFFF4D5E)
    TeamControl.TEAM_CONTROL_NEUTRAL -> Color(0xFFFFB300)
    else -> Color(0xFF6E7681)
}

private fun teamLabel(team: TeamControl): String = when (team) {
    TeamControl.TEAM_CONTROL_OWN -> "Own"
    TeamControl.TEAM_CONTROL_OPPOSING -> "Opposing"
    TeamControl.TEAM_CONTROL_NEUTRAL -> "Neutral"
    else -> "Unknown"
}

private fun formatTimestamp(unixSeconds: Long): String {
    if (unixSeconds <= 0L) return "—"
    val now = System.currentTimeMillis() / 1000L
    val age = (now - unixSeconds).coerceAtLeast(0)
    return when {
        age < 60 -> "${age}s ago"
        age < 3600 -> "${age / 60}m ago"
        age < 86400 -> "${age / 3600}h ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(unixSeconds * 1000))
    }
}
