// AirTAK Android — data/mesh/RemoteNodeRepository.kt

package com.airtak.data.mesh

import com.airtak.data.db.RemoteNodeDao
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.config.constants.MESH_NODE_RETENTION_MS
import com.airtak.config.constants.PORTNUM_AIRTAK_PLAYER
import com.airtak.config.constants.PORTNUM_NODEINFO_APP
import com.airtak.config.constants.PORTNUM_POSITION_APP
import com.airtak.config.constants.PORTNUM_TELEMETRY_APP
import com.airtak.config.constants.UINT32_MASK
import com.airtak.proto.PlayerStatePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteNodeRepository @Inject constructor(
    private val dao: RemoteNodeDao,
    mesh: MeshRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val nodes: Flow<List<RemoteNodeEntity>> = dao.observeAll()

    init {
        scope.launch {
            mesh.packets.collect { packet ->
                when (packet.portnum) {
                    PORTNUM_POSITION_APP -> handlePosition(packet)
                    PORTNUM_NODEINFO_APP -> handleNodeInfo(packet)
                    PORTNUM_AIRTAK_PLAYER -> handlePlayer(packet)
                    PORTNUM_TELEMETRY_APP -> handleTelemetry(packet)
                }
            }
        }
        scope.launch {
            dao.deleteOlderThan(System.currentTimeMillis() - MESH_NODE_RETENTION_MS)
        }
    }

    private suspend fun handlePosition(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val position = MeshWire.decodePosition(payload) ?: return
        update(packet.srcNodeId) { existing, now ->
            existing.copy(
                latitude = position.latitude,
                longitude = position.longitude,
                altitudeM = position.altitudeM,
                precisionBits = position.precisionBits,
                positionTimeUnix = position.timeUnix,
                lastPositionMs = now,
                lastSeenMs = now,
            )
        }
    }

    private suspend fun handleNodeInfo(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val user = MeshWire.decodeNodeInfo(payload) ?: return
        update(packet.srcNodeId) { existing, now -> mergeNodeInfo(existing, user, now) }
    }

    private suspend fun handlePlayer(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val player = runCatching { PlayerStatePacket.parseFrom(payload) }.getOrNull() ?: return
        val nodeId = (if (player.nodeId != 0) player.nodeId.toLong() else packet.srcNodeId) and UINT32_MASK
        update(nodeId) { existing, now -> mergePlayerState(existing, player, payload, now) }
    }

    private suspend fun handleTelemetry(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val battery = MeshWire.decodeTelemetryBattery(payload) ?: return
        update(packet.srcNodeId) { existing, now ->
            existing.copy(
                batteryPercent = battery,
                lastSeenMs = now,
            )
        }
    }

    private suspend fun update(
        nodeId: Long,
        block: (RemoteNodeEntity, Long) -> RemoteNodeEntity,
    ) {
        if (nodeId == 0L) return
        val now = System.currentTimeMillis()
        val existing = dao.get(nodeId) ?: RemoteNodeEntity(nodeId = nodeId, lastSeenMs = now)
        dao.upsert(block(existing, now))
    }

    }
