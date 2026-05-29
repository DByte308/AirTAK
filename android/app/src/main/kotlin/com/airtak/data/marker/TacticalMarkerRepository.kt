// AirTAK Android — data/marker/TacticalMarkerRepository.kt
// Roadmap: 5.3, 5.4, 5.5, 5.9
//
// Owns the local cache of TacticalMarker packets:
//   • subscribes to MeshRepository.packets and persists AIRTAK_TACTICAL
//   • exposes scope-filtered list flows for the UI
//   • drives outbound creation / update / delete (tombstone) via MeshRepository
//   • re-broadcasts locally-owned, undeleted markers on BLE reconnect (5.9)

package com.airtak.data.marker

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.TacticalMarkerDao
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.MeshRxPacket
import com.airtak.config.constants.PORTNUM_AIRTAK_ACK
import com.airtak.config.constants.PORTNUM_AIRTAK_MARKER_SNAPSHOT_REQUEST
import com.airtak.config.constants.PORTNUM_AIRTAK_SYNC_REQUEST
import com.airtak.config.constants.PORTNUM_AIRTAK_TACTICAL
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GameConfig
import com.airtak.proto.LatLng
import com.airtak.proto.MarkerType
import com.airtak.proto.MarkerSnapshotRequest
import com.airtak.proto.Scope
import com.airtak.proto.SyncRequest
import com.airtak.proto.TacticalMarker
import com.airtak.config.constants.MESH_SYNC_ID_TTL_MS
import com.airtak.config.constants.MESH_SYNC_TYPE_MARKERS
import com.airtak.config.constants.UI_MARKER_DESC_MAX_LEN
import com.airtak.config.constants.UI_MARKER_LABEL_MAX_LEN
import com.airtak.config.constants.UI_MARKER_MAX_POINTS
import com.airtak.config.constants.MARKER_ID_BYTES
import com.airtak.config.constants.MARKER_ID_SHIFT_1
import com.airtak.config.constants.MARKER_ID_SHIFT_2
import com.airtak.config.constants.MARKER_ID_SHIFT_3
import com.airtak.config.constants.MARKER_PURGE_INTERVAL_MS
import com.airtak.config.constants.MARKER_REBROADCAST_SPACING_MS
import com.airtak.config.constants.MARKER_TOMBSTONE_TTL_SEC
import com.airtak.config.constants.MESH_TACTICAL_SLOT_ALL
import com.airtak.config.constants.MS_PER_SECOND
import com.airtak.config.constants.UI_RGB_MASK
import com.airtak.config.constants.UINT32_MASK
import com.airtak.config.constants.UINT8_MASK
import com.airtak.proto.TeamControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TacticalMarkerRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val dao: TacticalMarkerDao,
    private val ble: BleRepository,
    private val deviceStatus: DeviceStatusRepository,
    private val configRepo: ConfigRepository,
    private val prefs: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    /** Own unit-hierarchy fields, used for scope filtering on receive. */
    private val _ownGame = MutableStateFlow(GameScopeIds())
    val ownGame: StateFlow<GameScopeIds> = _ownGame.asStateFlow()

    /** Maps Scope → channel slot the user has bound to that level (-1 = none). */
    private val _channelBindings = MutableStateFlow(ChannelBindings())

    /** requestId (from sendTacticalMarker) → markerId; cleared on AIRTAK_ACK. */
    private val pendingMarkerAcks = ConcurrentHashMap<Long, Long>()

    /** MRU cache of recently-handled sync_ids to prevent duplicate responses
     *  when a SYNC_REQUEST or MARKER_SNAPSHOT_REQUEST is relayed through
     *  multiple paths. Size 8, TTL 10 seconds, oldest evicted when full. */
    private val handledSyncIds = object : LinkedHashMap<Long, Long>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > 8 || (eldest != null && System.currentTimeMillis() > eldest.value)
    }

    val markers: Flow<List<TacticalMarkerEntity>> = dao.observeActive()

    /** Receiver-side scope filter (§5.5). */
    val visibleMarkers: Flow<List<TacticalMarkerEntity>> =
        combine(dao.observeActive(), _ownGame) { all, mine ->
            all.filter { marker -> matchesScope(marker, mine) }
        }

    init {
        // Persist incoming markers.
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_TACTICAL }
                .collect { handleIncoming(it) }
        }
        // Mark locally-sent markers as delivered when the firmware ACKs.
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_ACK }
                .collect { pkt ->
                    val reqId = pkt.requestId ?: return@collect
                    val markerId = pendingMarkerAcks.remove(reqId) ?: return@collect
                    dao.markDelivered(markerId)
                }
        }
        // Track own GameConfig so we can scope-filter inbound markers.
        scope.launch {
            configRepo.groupBytes.collect { map ->
                val bytes = map[ConfigGroup.GAME] ?: return@collect
                val game = runCatching { GameConfig.parseFrom(bytes) }.getOrNull() ?: return@collect
                _ownGame.value = GameScopeIds(
                    fireteamId = game.fireteamId,
                    squadId = game.squadId,
                    platoonId = game.platoonId,
                    companyId = game.companyId,
                )
            }
        }
        // Track unit-→-channel-slot bindings from AppPreferences (set by Settings → Player).
        scope.launch {
            prefs.observe().collect {
                _channelBindings.value = ChannelBindings(
                    fireteamSlot = prefs.unitChannelSlot("fireteam"),
                    squadSlot = prefs.unitChannelSlot("squad"),
                    platoonSlot = prefs.unitChannelSlot("platoon"),
                    companySlot = prefs.unitChannelSlot("company"),
                )
            }
        }
        // Re-broadcast locally-owned markers on BLE reconnect (§5.9).
        scope.launch {
            ble.linkState
                .map { it is LinkState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { rebroadcastOwned() }
        }
        // Respond to sync requests from late-joiners by re-broadcasting markers.
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_SYNC_REQUEST }
                .collect { handleSyncRequest(it) }
        }
        // Respond to targeted marker snapshot requests by re-broadcasting markers.
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_MARKER_SNAPSHOT_REQUEST }
                .collect { handleSnapshotRequest(it) }
        }
        // Periodically purge tombstones older than 24 h.
        scope.launch {
            while (true) {
                val cutoff = (System.currentTimeMillis() / MS_PER_SECOND) - MARKER_TOMBSTONE_TTL_SEC
                dao.purgeTombstones(cutoff)
                delay(MARKER_PURGE_INTERVAL_MS)
            }
        }
    }

    // ── Public API (used by UI) ──────────────────────────────────────────────

    data class GameScopeIds(
        val fireteamId: String = "",
        val squadId: String = "",
        val platoonId: String = "",
        val companyId: String = "",
    )

    data class ChannelBindings(
        val fireteamSlot: Int = -1,
        val squadSlot: Int = -1,
        val platoonSlot: Int = -1,
        val companySlot: Int = -1,
    )

    /** Create a new marker on this device and broadcast it. */
    suspend fun createMarker(
        type: MarkerType,
        label: String,
        description: String,
        colorRgb: Int,
        scopeKind: Scope,
        points: List<Pair<Double, Double>>,
        controllingTeam: TeamControl = TeamControl.TEAM_CONTROL_UNKNOWN,
        priority: Int = 0,
    ): TacticalMarkerEntity? {
        if (!validatePoints(type, points)) return null
        val ownNodeId = ownNodeIdOrZero()
        val nowSec = (System.currentTimeMillis() / MS_PER_SECOND).toInt()
        val markerId = generateMarkerId()
        val marker = TacticalMarker.newBuilder()
            .setMarkerId(markerId.toInt())
            .setType(type)
            .setLabel(label.take(UI_MARKER_LABEL_MAX_LEN))
            .setDescription(description.take(UI_MARKER_DESC_MAX_LEN))
            .setColorRgb(colorRgb)
            .setScope(scopeKind)
            .setCreatorNodeId(ownNodeId.toInt())
            .setCreatedAtUnix(nowSec)
            .setUpdatedAtUnix(nowSec)
            .setDeleted(false)
            .apply {
                points.forEach { (lat, lng) ->
                    addPoints(LatLng.newBuilder().setLat(lat).setLng(lng).build())
                }
            }
            .setControllingTeam(controllingTeam)
            .setPriority(priority)
            .build()
        val entity = toEntity(marker, ownedLocally = true, lastSeenMs = System.currentTimeMillis())
        dao.upsert(entity)
        val result = mesh.sendTacticalMarker(marker, channelSlotForScope(scopeKind))
        if (result is MeshRepository.SendResult.Ok) pendingMarkerAcks[result.requestId] = entity.markerId
        return entity
    }

    /**
     * Edit a locally-owned marker. Preserves the marker id, creator and original
     * point geometry but updates the label, description, color, scope, team and
     * priority fields and re-broadcasts so receivers replace their copy.
     *
     * Returns the updated entity, or null if the marker is unknown or not owned
     * locally.
     */
    suspend fun updateMarker(
        markerId: Long,
        label: String,
        description: String,
        colorRgb: Int,
        scopeKind: Scope,
        controllingTeam: TeamControl = TeamControl.TEAM_CONTROL_UNKNOWN,
        priority: Int = 0,
    ): TacticalMarkerEntity? {
        val existing = dao.get(markerId) ?: return null
        if (!existing.ownedLocally || existing.deleted) return null
        val nowSec = (System.currentTimeMillis() / MS_PER_SECOND).toInt()
        val type = MarkerType.forNumber(existing.typeValue) ?: MarkerType.MARKER_TYPE_POINT
        val builder = TacticalMarker.newBuilder()
            .setMarkerId(existing.markerId.toInt())
            .setType(type)
            .setLabel(label.take(UI_MARKER_LABEL_MAX_LEN))
            .setDescription(description.take(UI_MARKER_DESC_MAX_LEN))
            .setColorRgb(colorRgb and UI_RGB_MASK)
            .setScope(scopeKind)
            .setCreatorNodeId(existing.creatorNodeId.toInt())
            .setCreatedAtUnix(existing.createdAtUnix.toInt())
            .setUpdatedAtUnix(nowSec)
            .setDeleted(false)
            .setControllingTeam(controllingTeam)
            .setPriority(priority)
        existing.points().forEach { (lat, lng) ->
            builder.addPoints(LatLng.newBuilder().setLat(lat).setLng(lng).build())
        }
        val proto = builder.build()
        val entity = toEntity(proto, ownedLocally = true, lastSeenMs = System.currentTimeMillis())
        dao.upsert(entity)
        val result = mesh.sendTacticalMarker(proto, channelSlotForScope(scopeKind))
        if (result is MeshRepository.SendResult.Ok) pendingMarkerAcks[result.requestId] = entity.markerId
        return entity
    }

    /** Soft-delete: marks the marker as tombstoned and broadcasts the tombstone. */
    suspend fun deleteMarker(markerId: Long): Boolean {
        val existing = dao.get(markerId) ?: return false
        val nowSec = (System.currentTimeMillis() / MS_PER_SECOND).toInt()
        val tombstone = TacticalMarker.newBuilder()
            .setMarkerId(existing.markerId.toInt())
            .setType(MarkerType.forNumber(existing.typeValue) ?: MarkerType.MARKER_TYPE_POINT)
            .setLabel(existing.label)
            .setColorRgb(existing.colorRgb)
            .setScope(Scope.forNumber(existing.scopeValue) ?: Scope.SCOPE_ALL)
            .setCreatorNodeId(existing.creatorNodeId.toInt())
            .setCreatedAtUnix(existing.createdAtUnix.toInt())
            .setUpdatedAtUnix(nowSec)
            .setDeleted(true)
            .build()
        dao.upsert(
            existing.copy(
                deleted = true,
                updatedAtUnix = nowSec.toLong(),
                lastSeenMs = System.currentTimeMillis(),
            ),
        )
        mesh.sendTacticalMarker(tombstone, channelSlotForScope(tombstone.scope))
        return true
    }

    suspend fun get(markerId: Long): TacticalMarkerEntity? = dao.get(markerId)

    // ── Inbound handling ─────────────────────────────────────────────────────

    private suspend fun handleIncoming(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val marker = runCatching { TacticalMarker.parseFrom(payload) }.getOrNull() ?: return
        val markerId = marker.markerId.toLong() and UINT32_MASK
        if (markerId == 0L) return
        val existing = dao.get(markerId)
        // Drop out-of-order updates: only accept newer updatedAtUnix.
        if (existing != null && marker.updatedAtUnix.toLong() < existing.updatedAtUnix) return
        if (!validatePoints(marker.type, marker.pointsList.map { it.lat to it.lng })) return
        val ownedLocally = existing?.ownedLocally == true
        dao.upsert(toEntity(marker, ownedLocally, System.currentTimeMillis()))
    }

    private fun toEntity(
        marker: TacticalMarker,
        ownedLocally: Boolean,
        lastSeenMs: Long,
    ): TacticalMarkerEntity = TacticalMarkerEntity(
        markerId = marker.markerId.toLong() and UINT32_MASK,
        typeValue = marker.typeValue,
        label = marker.label,
        description = marker.description,
        colorRgb = marker.colorRgb,
        scopeValue = marker.scopeValue,
        creatorNodeId = marker.creatorNodeId.toLong() and UINT32_MASK,
        createdAtUnix = marker.createdAtUnix.toLong() and UINT32_MASK,
        updatedAtUnix = marker.updatedAtUnix.toLong() and UINT32_MASK,
        deleted = marker.deleted,
        pointsEncoded = TacticalMarkerEntity.encodePoints(
            marker.pointsList.map { it.lat to it.lng },
        ),
        controllingTeamValue = marker.controllingTeamValue,
        priority = marker.priority,
        lastSeenMs = lastSeenMs,
        ownedLocally = ownedLocally,
    )

    // ── Rebroadcast on reconnect ─────────────────────────────────────────────

    suspend fun rebroadcastOwned() {
        val owned = dao.getOwnedActive()
        for (entity in owned) {
            val proto = entityToProto(entity)
            val result = mesh.sendTacticalMarker(proto, channelSlotForScope(proto.scope))
            if (result is MeshRepository.SendResult.Ok) pendingMarkerAcks[result.requestId] = entity.markerId
            delay(MARKER_REBROADCAST_SPACING_MS)
        }
    }

    // ── Sync request handler ──────────────────────────────────────────────────

    private suspend fun handleSyncRequest(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val request = runCatching { SyncRequest.parseFrom(payload) }.getOrNull() ?: return
        val syncId = request.syncId.toLong() and UINT32_MASK
        if (syncId != 0L && isSyncIdHandled(syncId)) return
        val requesterId = request.requesterNodeId.toLong() and UINT32_MASK
        val ownId = deviceStatus.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: 0L
        if (requesterId == ownId) return

        if (request.requestedTypes and MESH_SYNC_TYPE_MARKERS != 0) {
            markSyncIdHandled(syncId)
            rebroadcastOwned()
        }
    }

    // ── Snapshot request handler ──────────────────────────────────────────────

    private suspend fun handleSnapshotRequest(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val request = runCatching { MarkerSnapshotRequest.parseFrom(payload) }.getOrNull() ?: return
        val syncId = request.syncId.toLong() and UINT32_MASK
        if (syncId != 0L && isSyncIdHandled(syncId)) return
        val targetId = request.targetNodeId.toLong() and UINT32_MASK
        val ownId = deviceStatus.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: 0L
        if (targetId != ownId) return
        markSyncIdHandled(syncId)
        rebroadcastOwned()
    }

    private fun isSyncIdHandled(syncId: Long): Boolean =
        synchronized(handledSyncIds) { handledSyncIds.containsKey(syncId) }

    private fun markSyncIdHandled(syncId: Long) {
        if (syncId == 0L) return
        synchronized(handledSyncIds) {
            handledSyncIds[syncId] = System.currentTimeMillis() + MESH_SYNC_ID_TTL_MS
        }
    }

    private fun entityToProto(entity: TacticalMarkerEntity): TacticalMarker {
        val builder = TacticalMarker.newBuilder()
            .setMarkerId(entity.markerId.toInt())
            .setType(MarkerType.forNumber(entity.typeValue) ?: MarkerType.MARKER_TYPE_POINT)
            .setLabel(entity.label)
            .setDescription(entity.description)
            .setColorRgb(entity.colorRgb)
            .setScope(Scope.forNumber(entity.scopeValue) ?: Scope.SCOPE_ALL)
            .setCreatorNodeId(entity.creatorNodeId.toInt())
            .setCreatedAtUnix(entity.createdAtUnix.toInt())
            .setUpdatedAtUnix(entity.updatedAtUnix.toInt())
            .setDeleted(entity.deleted)
            .setControllingTeam(TeamControl.forNumber(entity.controllingTeamValue) ?: TeamControl.TEAM_CONTROL_UNKNOWN)
            .setPriority(entity.priority)
        entity.points().forEach { (lat, lng) ->
            builder.addPoints(LatLng.newBuilder().setLat(lat).setLng(lng).build())
        }
        return builder.build()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun matchesScope(marker: TacticalMarkerEntity, mine: GameScopeIds): Boolean {
        // Always show markers the local device created, regardless of unit fields.
        if (marker.ownedLocally) return true
        val scope = Scope.forNumber(marker.scopeValue) ?: return true
        if (scope == Scope.SCOPE_ALL) return true
        // For scoped markers we need to find a node that belongs to that unit.
        // Since we currently don't track creator unit metadata in the entity,
        // we fall back to: marker is visible iff the receiver's matching unit
        // field is non-blank. (Senders restrict via channel slot; this filter
        // is a safety net against accidental cross-scope reception on slot 1.)
        return when (scope) {
            Scope.SCOPE_FIRE_TEAM -> mine.fireteamId.isNotBlank()
            Scope.SCOPE_SQUAD     -> mine.squadId.isNotBlank()
            Scope.SCOPE_PLATOON   -> mine.platoonId.isNotBlank()
            Scope.SCOPE_COMPANY   -> mine.companyId.isNotBlank()
            else -> true
        }
    }

    private fun channelSlotForScope(scope: Scope): Int? {
        val bindings = _channelBindings.value
        return when (scope) {
            Scope.SCOPE_ALL       -> MESH_TACTICAL_SLOT_ALL
            Scope.SCOPE_FIRE_TEAM -> bindings.fireteamSlot.takeIf { it >= 0 } ?: MESH_TACTICAL_SLOT_ALL
            Scope.SCOPE_SQUAD     -> bindings.squadSlot.takeIf    { it >= 0 } ?: MESH_TACTICAL_SLOT_ALL
            Scope.SCOPE_PLATOON   -> bindings.platoonSlot.takeIf  { it >= 0 } ?: MESH_TACTICAL_SLOT_ALL
            Scope.SCOPE_COMPANY   -> bindings.companySlot.takeIf  { it >= 0 } ?: MESH_TACTICAL_SLOT_ALL
            else -> MESH_TACTICAL_SLOT_ALL
        }
    }

    private fun validatePoints(type: MarkerType, points: List<Pair<Double, Double>>): Boolean {
        if (points.size > UI_MARKER_MAX_POINTS) return false
        return when (type) {
            MarkerType.MARKER_TYPE_POINT, MarkerType.MARKER_TYPE_NOTE, MarkerType.MARKER_TYPE_OBJECTIVE -> points.size == 1
            MarkerType.MARKER_TYPE_ROUTE -> points.size in 2..UI_MARKER_MAX_POINTS
            MarkerType.MARKER_TYPE_AREA  -> points.size in 3..UI_MARKER_MAX_POINTS
            else -> false
        }
    }

    private fun ownNodeIdOrZero(): Long =
        deviceStatus.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: 0L

    private fun generateMarkerId(): Long {
        val b = ByteArray(MARKER_ID_BYTES)
        random.nextBytes(b)
        return ((b[0].toLong() and UINT8_MASK.toLong()) shl MARKER_ID_SHIFT_3) or
            ((b[1].toLong() and UINT8_MASK.toLong()) shl MARKER_ID_SHIFT_2) or
            ((b[2].toLong() and UINT8_MASK.toLong()) shl MARKER_ID_SHIFT_1)  or
             (b[3].toLong() and UINT8_MASK.toLong())
    }

}
