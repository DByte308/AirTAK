// AirTAK Android — ui/players/PlayersViewModel.kt
// v1.6: Team-based grouping replaced with unit hierarchy grouping.
// Admin roles and force-status/reassign-team removed (decentralized, no admin).

package com.airtak.ui.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.data.game.GameRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import com.airtak.data.moderation.ModerationRepository
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GameConfig
import com.airtak.proto.ModerationAction
import com.airtak.proto.PlayerStatePacket
import com.airtak.proto.PlayerStatus
import com.airtak.proto.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerRow(
        val nodeId: Long,
        val callsign: String,
        val fireteamId: String,
        val squadId: String,
        val platoonId: String,
        val companyId: String,
        val colorRgb: Int,
        val status: PlayerStatus,
        val lastSeenMs: Long,
        val lastPlayerMs: Long?,
        val batteryPercent: Int?,
        val isSelf: Boolean,
        val online: Boolean,
)

data class UnitGroup(
        val label: String,
        val hierarchyLevel: String,
        val players: List<PlayerRow>,
        val children: List<UnitGroup> = emptyList(),
)

data class HierarchyRoot(
        val groups: List<UnitGroup>,
        val ungrouped: List<PlayerRow>,
)

data class PlayersUiState(
        val showOnlineOnly: Boolean = false,
        val ownStatus: PlayerStatus = PlayerStatus.PLAYER_STATUS_ACTIVE,
        val hierarchy: HierarchyRoot = HierarchyRoot(emptyList(), emptyList()),
        val onlineCount: Int = 0,
        val totalCount: Int = 0,
        val query: String = "",
)

@HiltViewModel
class PlayersViewModel
@Inject
constructor(
        private val mesh: MeshRepository,
        private val config: ConfigRepository,
        statusRepo: DeviceStatusRepository,
        remoteNodes: RemoteNodeRepository,
        gameRepository: GameRepository,
        private val moderation: ModerationRepository,
) : ViewModel() {
    private val showOnlineOnly = MutableStateFlow(false)
    private val ownStatus = MutableStateFlow(PlayerStatus.PLAYER_STATUS_ACTIVE)
    private val _query = MutableStateFlow("")
    private val status = statusRepo.status
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private data class Source(
            val nodes: List<RemoteNodeEntity>,
            val deviceStatus: com.airtak.proto.DeviceStatus?,
            val gameConfig: GameConfig,
    )

    init {
        viewModelScope.launch { config.read(ConfigGroup.GAME) }
    }

    private val gameConfig =
            config.groupBytes.map { map ->
                map[ConfigGroup.GAME]?.let { runCatching { GameConfig.parseFrom(it) }.getOrNull() }
                        ?: GameConfig.getDefaultInstance()
            }

    private val source =
            combine(remoteNodes.nodes, status, gameConfig) { nodes, deviceStatus, cfg ->
                Source(nodes, deviceStatus, cfg)
            }

    val uiState: StateFlow<PlayersUiState> =
            combine(
                            combine(source, showOnlineOnly, ownStatus, clock) {
                                    source,
                                    onlineOnly,
                                    own,
                                    now ->
                                val ownNodeId =
                                        source.deviceStatus?.nodeId?.toLong()?.and(0xFFFF_FFFFL)
                                val deviceStatus = source.deviceStatus
                                val remoteRows =
                                        source.nodes.filter { it.nodeId != ownNodeId }.map {
                                            it.toPlayerRow(now, false)
                                        }
                                val ownRow =
                                        if (deviceStatus != null) {
                                            val selfNodeId =
                                                    deviceStatus.nodeId.toLong() and 0xFFFF_FFFFL
                                            PlayerRow(
                                                    nodeId = selfNodeId,
                                                    callsign =
                                                            source.gameConfig.callsign.ifBlank {
                                                                deviceStatus.nodeName.ifBlank {
                                                                    "You"
                                                                }
                                                            },
                                                    fireteamId = source.gameConfig.fireteamId,
                                                    squadId = source.gameConfig.squadId,
                                                    platoonId = source.gameConfig.platoonId,
                                                    companyId = source.gameConfig.companyId,
                                                    colorRgb = source.gameConfig.playerColorRgb,
                                                    status = own,
                                                    lastSeenMs = now,
                                                    lastPlayerMs = now,
                                                    batteryPercent =
                                                            (deviceStatus.batteryLevel * 100)
                                                                    .toInt()
                                                                    .coerceIn(0, 100),
                                                    isSelf = true,
                                                    online = true,
                                            )
                                        } else null
                                val rows =
                                        (listOfNotNull(ownRow) + remoteRows).filter {
                                            !onlineOnly || it.online
                                        }
                                val hierarchy = groupByHierarchy(rows)
                                PlayersUiState(
                                        showOnlineOnly = onlineOnly,
                                        ownStatus = own,
                                        hierarchy = hierarchy,
                                        onlineCount =
                                                (listOfNotNull(ownRow) + remoteRows).count {
                                                    it.online
                                                },
                                        totalCount = (listOfNotNull(ownRow) + remoteRows).size,
                                )
                            },
                            _query,
                    ) { state, q ->
                        if (q.isBlank()) state.copy(query = q)
                        else
                                state.copy(
                                        query = q,
                                        hierarchy =
                                                state.hierarchy.copy(
                                                        groups =
                                                                state.hierarchy.groups.mapNotNull {
                                                                        grp ->
                                                                    filterHierarchy(grp, q)
                                                                },
                                                        ungrouped =
                                                                state.hierarchy.ungrouped.filter {
                                                                        player ->
                                                                    player.callsign.contains(
                                                                            q,
                                                                            ignoreCase = true
                                                                    ) ||
                                                                            player.companyId
                                                                                    .contains(
                                                                                            q,
                                                                                            ignoreCase =
                                                                                                    true
                                                                                    ) ||
                                                                            player.platoonId
                                                                                    .contains(
                                                                                            q,
                                                                                            ignoreCase =
                                                                                                    true
                                                                                    ) ||
                                                                            player.squadId.contains(
                                                                                    q,
                                                                                    ignoreCase =
                                                                                            true
                                                                            ) ||
                                                                            player.fireteamId
                                                                                    .contains(
                                                                                            q,
                                                                                            ignoreCase =
                                                                                                    true
                                                                                    )
                                                                },
                                                ),
                                )
                    }
                    .stateIn(
                            viewModelScope,
                            SharingStarted.WhileSubscribed(5_000),
                            PlayersUiState()
                    )

    fun setShowOnlineOnly(value: Boolean) {
        showOnlineOnly.value = value
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setOwnStatus(status: PlayerStatus) {
        val transition = ownStatus.value != status
        ownStatus.value = status
        broadcastOwnStatus(status, transition)
    }

    fun refreshOwnState() = broadcastOwnStatus(ownStatus.value, false)

    fun requestPosition(nodeId: Long) {
        mesh.requestPosition(nodeId, statusNodeId()?.toLong()?.and(0xFFFF_FFFFL) ?: 0L)
    }

    fun requestMarkerSnapshot(nodeId: Long) {
        mesh.requestMarkerSnapshot(nodeId)
    }

    val activeBans: StateFlow<Set<Pair<Long, Int>>> = moderation.activeBans

    fun submitVoteKick(targetNodeId: Long, scope: Scope) {
        viewModelScope.launch {
            moderation.submitVote(targetNodeId, scope, ModerationAction.MODERATION_ACTION_VOTE_BAN)
        }
    }

    fun submitUnban(targetNodeId: Long, scope: Scope) {
        viewModelScope.launch {
            moderation.submitVote(targetNodeId, scope, ModerationAction.MODERATION_ACTION_UNBAN)
        }
    }

    private fun broadcastOwnStatus(statusValue: PlayerStatus, transition: Boolean) {
        val currentStatus = status.value ?: return
        val cfg = runCatching { uiState.value }.getOrNull()
        val allPlayers =
                (cfg?.hierarchy?.groups?.flatMap { allPlayers(it) }
                        ?: emptyList()) + (cfg?.hierarchy?.ungrouped ?: emptyList())
        val row = allPlayers.firstOrNull { it.isSelf }
        val callsign =
                row?.callsign?.takeIf { it.isNotBlank() }
                        ?: currentStatus.nodeName.ifBlank { "AirTAK" }
        val packet =
                PlayerStatePacket.newBuilder()
                        .setNodeId(currentStatus.nodeId)
                        .setCallsign(callsign)
                        .setStatus(statusValue)
                        .setColorRgb(row?.colorRgb ?: 0xFFFFFF)
                        .setIsStateTransition(transition)
                        .setFireteamId(row?.fireteamId ?: "")
                        .setSquadId(row?.squadId ?: "")
                        .setPlatoonId(row?.platoonId ?: "")
                        .setCompanyId(row?.companyId ?: "")
                        .build()
        mesh.sendPlayerState(packet)
    }

    private fun statusNodeId(): Int? = status.value?.nodeId

    private fun RemoteNodeEntity.toPlayerRow(
            now: Long,
            isSelf: Boolean,
    ): PlayerRow {
        val online = lastPlayerMs != null && now - lastPlayerMs <= PLAYER_STALE_MS
        val displayName =
                callsign.ifBlank {
                    shortName.ifBlank { nodeId.toString(16).uppercase().takeLast(4) }
                }
        return PlayerRow(
                nodeId = nodeId,
                callsign = displayName,
                fireteamId = fireteamId,
                squadId = squadId,
                platoonId = platoonId,
                companyId = companyId,
                colorRgb = colorRgb.takeIf { it != 0 } ?: defaultPlayerColor(nodeId),
                status =
                        if (online)
                                PlayerStatus.forNumber(statusValue)
                                        ?: PlayerStatus.PLAYER_STATUS_ACTIVE
                        else PlayerStatus.UNRECOGNIZED,
                lastSeenMs = lastSeenMs,
                lastPlayerMs = lastPlayerMs,
                batteryPercent = batteryPercent,
                isSelf = isSelf,
                online = online,
        )
    }

    private fun defaultPlayerColor(nodeId: Long): Int {
        return DEFAULT_COLORS[(nodeId % DEFAULT_COLORS.size).toInt().coerceAtLeast(0)]
    }

    private companion object {
        const val PLAYER_STALE_MS = 5 * 60 * 1_000L
        val DEFAULT_COLORS =
                intArrayOf(
                        0xE63946,
                        0x1D9BF0,
                        0xFFB300,
                        0x00E676,
                        0xB388FF,
                        0xFF7043,
                        0x80CBC4,
                        0xF06292,
                )
    }
}

private fun allPlayers(group: UnitGroup): List<PlayerRow> =
        group.players + group.children.flatMap { allPlayers(it) }

private fun filterHierarchy(group: UnitGroup, q: String): UnitGroup? {
    val filteredChildren = group.children.mapNotNull { filterHierarchy(it, q) }
    val filteredPlayers =
            group.players.filter { player ->
                player.callsign.contains(q, ignoreCase = true) ||
                        group.label.contains(q, ignoreCase = true)
            }
    if (filteredChildren.isEmpty() && filteredPlayers.isEmpty()) return null
    return group.copy(children = filteredChildren, players = filteredPlayers)
}

private fun groupByHierarchy(rows: List<PlayerRow>): HierarchyRoot {
    val sorted = rows.sortedBy { it.callsign.lowercase() }
    val ungrouped = mutableListOf<PlayerRow>()

    // Build nested grouping: company → platoon → squad → fireteam → players
    // Use a simple linked map tree keyed by unit ID strings
    data class Node(
        val label: String,
        val level: String,
        val children: LinkedHashMap<String, Node> = linkedMapOf(),
        val players: MutableList<PlayerRow> = mutableListOf(),
    )

    val root = Node(label = "", level = "", children = linkedMapOf())

    for (row in sorted) {
        val co = row.companyId
        val pl = row.platoonId
        val sq = row.squadId
        val ft = row.fireteamId

        if (co.isBlank() && pl.isBlank() && sq.isBlank() && ft.isBlank()) {
            ungrouped.add(row)
            continue
        }

        val companyKey = co.ifBlank { "—" }
        val platoonKey = pl.ifBlank { "—" }
        val squadKey = sq.ifBlank { "—" }
        val fireteamKey = ft.ifBlank { "—" }

        val companyNode = root.children.getOrPut(companyKey) { Node(companyKey, "company") }
        val platoonNode = companyNode.children.getOrPut(platoonKey) { Node(platoonKey, "platoon") }
        val squadNode = platoonNode.children.getOrPut(squadKey) { Node(squadKey, "squad") }
        val fireteamNode = squadNode.children.getOrPut(fireteamKey) { Node(fireteamKey, "fireteam") }
        fireteamNode.players.add(row)
    }

    fun toUnitGroup(node: Node): UnitGroup {
        val children = node.children.values.map { toUnitGroup(it) }.sortedBy { it.label }
        // Collapse a node with a single "—" child that has no players
        val effectiveChildren: List<UnitGroup>
        val effectivePlayers: List<PlayerRow>
        if (node.level == "company" && children.size == 1 && children[0].label == "—"
            && children[0].children.size == 1 && children[0].children.all { it.players.isEmpty() }) {
            // Collapse through — platoon → — squad → fireteams
            effectiveChildren = children[0].children.flatMap { it.children }
            effectivePlayers = emptyList()
        } else if (node.level == "platoon" && children.size == 1 && children[0].label == "—"
            && children[0].players.isEmpty()) {
            // Collapse through — squad → fireteams
            effectiveChildren = children[0].children
            effectivePlayers = emptyList()
        } else {
            effectiveChildren = children
            effectivePlayers = node.players
        }
        return UnitGroup(
            label = node.label,
            hierarchyLevel = node.level,
            players = effectivePlayers.sortedBy { it.callsign.lowercase() },
            children = effectiveChildren,
        )
    }

    val groups = root.children.values.map { toUnitGroup(it) }.sortedBy { it.label }
    return HierarchyRoot(groups = groups, ungrouped = ungrouped)
}
