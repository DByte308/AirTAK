// AirTAK Android — ui/players/PlayersScreen.kt
// v1.6: Team grouping replaced with unit hierarchy. Admin controls removed.

package com.airtak.ui.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.proto.PlayerStatus
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.MetricTile
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusEliminated
import com.airtak.ui.theme.StatusHit
import com.airtak.ui.theme.StatusOffline
import com.airtak.ui.theme.StatusRespawning

@Composable
fun PlayersScreen(
    viewModel: PlayersViewModel = hiltViewModel(),
    onMessagePlayer: (Long) -> Unit = {},
    onLocatePlayer: (Long) -> Unit = {},
) {
    val ui by viewModel.uiState.collectAsState()
    val bans by viewModel.activeBans.collectAsState()
    var pendingKick by remember { mutableStateOf<PlayerRow?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("ONLINE", "${ui.onlineCount}/${ui.totalCount}", Modifier.weight(1f))
                MetricTile("FILTER", if (ui.showOnlineOnly) "ONLINE" else "ALL", Modifier.weight(1f))
            }
        }
        item {
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search callsign or unit…", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item {
            OwnStatusPanel(
                selected = ui.ownStatus,
                onSelect = viewModel::setOwnStatus,
                onRefresh = viewModel::refreshOwnState,
                onlineOnly = ui.showOnlineOnly,
                onOnlineOnly = viewModel::setShowOnlineOnly,
            )
        }
        // Collapsible hierarchy: track which groups are expanded
        val collapsedKeys = remember { mutableStateMapOf<String, Boolean>() }
        fun groupKey(group: UnitGroup): String = "${group.hierarchyLevel}:${group.label}"

        fun flatten(group: UnitGroup, depth: Int): List<Any> {
            val key = groupKey(group)
            val isCollapsed = collapsedKeys[key] == true
            val result = mutableListOf<Any>(group to depth)
            if (!isCollapsed) {
                group.children.forEach { child -> result.addAll(flatten(child, depth + 1)) }
                group.players.forEach { player ->
                    val banned = bans.any { (target, _) -> target == player.nodeId } && !player.isSelf
                    if (!banned) result.add(player)
                }
            }
            return result
        }

        val flat = remember(ui.hierarchy, bans, collapsedKeys) {
            val list = mutableListOf<Any>()
            ui.hierarchy.groups.forEach { list.addAll(flatten(it, 0)) }
            ui.hierarchy.ungrouped.forEach { player ->
                val banned = bans.any { (target, _) -> target == player.nodeId } && !player.isSelf
                if (!banned) list.add(player)
            }
            list
        }

        items(flat.size, key = {
            val item = flat[it]
            when (item) {
                is Pair<*, *> -> "hdr-${groupKey(item.first as UnitGroup)}"
                is PlayerRow -> "p-${item.nodeId}"
                else -> "item-$it"
            }
        }) { idx ->
            when (val item = flat[idx]) {
                is Pair<*, *> -> {
                    val grp = item.first as UnitGroup
                    val depth = item.second as Int
                    val key = groupKey(grp)
                    val isCollapsed = collapsedKeys[key] == true
                    val totalCount = grp.players.size + grp.children.sumOf { countAll(it) }
                    val canExpand = grp.children.isNotEmpty() || grp.players.isNotEmpty()
                    HierarchyHeader(
                        group = grp,
                        depth = depth,
                        totalCount = totalCount,
                        canExpand = canExpand,
                        expanded = !isCollapsed,
                        onToggle = {
                            collapsedKeys[key] = !isCollapsed
                        },
                    )
                }
                is PlayerRow -> {
                    PlayerCard(
                        player = item,
                        onRequestPosition = { viewModel.requestPosition(item.nodeId) },
                        onRequestMarkers = { viewModel.requestMarkerSnapshot(item.nodeId) },
                        onMessage = { onMessagePlayer(item.nodeId) },
                        onLocate = { onLocatePlayer(item.nodeId) },
                        onVoteKick = {{ pendingKick = item }},
                    )
                }
            }
        }
        if (flat.isEmpty()) {
            item {
                HudCard(Modifier.fillMaxWidth()) {
                    Text(
                        "No mesh players heard yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    pendingKick?.let { target ->
        VoteKickDialog(
            target = target,
            onDismiss = { pendingKick = null },
            onConfirm = { scope ->
                viewModel.submitVoteKick(target.nodeId, scope)
                pendingKick = null
            },
        )
    }
}

@Composable
private fun VoteKickDialog(
    target: PlayerRow,
    onDismiss: () -> Unit,
    onConfirm: (com.airtak.proto.Scope) -> Unit,
) {
    val scopes = listOf(
        com.airtak.proto.Scope.SCOPE_FIRE_TEAM to "Fireteam",
        com.airtak.proto.Scope.SCOPE_SQUAD to "Squad",
        com.airtak.proto.Scope.SCOPE_PLATOON to "Platoon",
        com.airtak.proto.Scope.SCOPE_COMPANY to "Company",
        com.airtak.proto.Scope.SCOPE_ALL to "All players",
    )
    var selected by remember { mutableStateOf(com.airtak.proto.Scope.SCOPE_FIRE_TEAM) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vote-kick ${target.callsign}?") },
        text = {
            Column {
                Text(
                    "When 3 distinct players cast a vote-kick at the same scope, the target is hidden from your local UI for 24 h. This affects display only — the mesh keeps relaying their packets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                scopes.forEach { (s, label) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == s,
                            onClick = { selected = s },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onConfirm(selected) }) { Text("Vote kick") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OwnStatusPanel(
    selected: PlayerStatus,
    onSelect: (PlayerStatus) -> Unit,
    onRefresh: () -> Unit,
    onlineOnly: Boolean,
    onOnlineOnly: (Boolean) -> Unit,
) {
    HudCard(Modifier.fillMaxWidth(), accent = statusColor(selected)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Own status")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    PlayerStatus.PLAYER_STATUS_ACTIVE,
                    PlayerStatus.PLAYER_STATUS_INACTIVE,
                    PlayerStatus.PLAYER_STATUS_HIT,
                    PlayerStatus.PLAYER_STATUS_RESPAWNING,
                    PlayerStatus.PLAYER_STATUS_ELIMINATED,
                ).forEach { status ->
                    val active = selected == status
                    if (active) {
                        Button(
                            onClick = { onSelect(status) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            ButtonLabel(status.shortButtonLabel())
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(status) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            ButtonLabel(status.shortButtonLabel())
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { ButtonLabel("Broadcast") }
                if (onlineOnly) {
                    Button(
                        onClick = { onOnlineOnly(false) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { ButtonLabel("Show all") }
                } else {
                    OutlinedButton(
                        onClick = { onOnlineOnly(true) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { ButtonLabel("Online only") }
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun UnitGroupHeader(group: UnitGroup) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            group.label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            group.hierarchyLevel.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val totalCount = group.players.size + group.children.sumOf { countAll(it) }
        Spacer(Modifier.width(8.dp))
        Text(
            totalCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun countAll(group: UnitGroup): Int = group.players.size + group.children.sumOf { countAll(it) }

@Composable
private fun HierarchyHeader(
    group: UnitGroup,
    depth: Int,
    totalCount: Int,
    canExpand: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canExpand) { onToggle() }
            .padding(vertical = 4.dp, horizontal = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canExpand) {
            Text(
                if (expanded) "▼" else "▶",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            group.label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                group.hierarchyLevel.takeIf { it.isNotBlank() } ?: "—",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            totalCount.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlayerCard(
    player: PlayerRow,
    onRequestPosition: () -> Unit,
    onRequestMarkers: () -> Unit,
    onMessage: () -> Unit,
    onLocate: () -> Unit,
    onVoteKick: (() -> Unit)? = null,
) {
    val status = if (player.online) player.status else PlayerStatus.UNRECOGNIZED
    HudCard(Modifier.fillMaxWidth(), accent = playerColor(player.colorRgb)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(playerColor(player.colorRgb), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        player.callsign.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        hierarchyLabel(player),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    BatteryIndicator(player.batteryPercent)
                    Text(
                        ageText(player.lastPlayerMs ?: player.lastSeenMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Message")
                    }
                    OutlinedButton(onClick = onLocate, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Locate")
                    }
                    if (!player.isSelf) {
                        OutlinedButton(onClick = onRequestPosition, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ping")
                        }
                    }
                    if (!player.isSelf) {
                        OutlinedButton(onClick = onRequestMarkers, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Get markers")
                        }
                    }
                }
                if (onVoteKick != null) {
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.TextButton(
                        onClick = onVoteKick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Vote-kick…", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: PlayerStatus) {
    val color = statusColor(status)
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            status.shortLabel(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BatteryIndicator(percent: Int?) {
    if (percent == null) {
        Text("BAT —", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { percent.coerceIn(0, 100) / 100f },
            modifier = Modifier.width(42.dp),
            color = if (percent <= 20) StatusHit else StatusActive,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text("$percent%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun hierarchyLabel(player: PlayerRow): String {
    val parts = mutableListOf<String>()
    if (player.companyId.isNotBlank()) parts.add(player.companyId)
    if (player.platoonId.isNotBlank()) parts.add(player.platoonId)
    if (player.squadId.isNotBlank()) parts.add(player.squadId)
    if (player.fireteamId.isNotBlank()) parts.add(player.fireteamId)
    return parts.joinToString(" / ").ifBlank { "No unit" }
}

private fun ageText(timestampMs: Long?): String {
    if (timestampMs == null) return "—"
    val ageSec = ((System.currentTimeMillis() - timestampMs) / 1_000L).coerceAtLeast(0)
    return when {
        ageSec < 60 -> "${ageSec}s"
        ageSec < 3600 -> "${ageSec / 60}m"
        else -> "${ageSec / 3600}h"
    }
}

private fun PlayerStatus.shortLabel(): String = when (this) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> "ACTIVE"
    PlayerStatus.PLAYER_STATUS_INACTIVE -> "INACT"
    PlayerStatus.PLAYER_STATUS_HIT -> "HIT"
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> "RESPAWN"
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> "ELIM"
    else -> "OFFLINE"
}

private fun PlayerStatus.shortButtonLabel(): String = when (this) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> "Active"
    PlayerStatus.PLAYER_STATUS_INACTIVE -> "Inact"
    PlayerStatus.PLAYER_STATUS_HIT -> "Hit"
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> "Resp"
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> "Elim"
    else -> shortLabel()
}

private fun statusColor(status: PlayerStatus): Color = when (status) {
    PlayerStatus.PLAYER_STATUS_ACTIVE -> StatusActive
    PlayerStatus.PLAYER_STATUS_INACTIVE -> StatusOffline
    PlayerStatus.PLAYER_STATUS_HIT -> StatusHit
    PlayerStatus.PLAYER_STATUS_RESPAWNING -> StatusRespawning
    PlayerStatus.PLAYER_STATUS_ELIMINATED -> StatusEliminated
    else -> StatusOffline
}

private fun playerColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))
