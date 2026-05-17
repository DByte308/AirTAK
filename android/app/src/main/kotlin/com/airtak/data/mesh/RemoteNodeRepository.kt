// AirTAK Android — data/mesh/RemoteNodeRepository.kt

package com.airtak.data.mesh

import com.airtak.data.db.RemoteNodeDao
import com.airtak.data.db.RemoteNodeEntity
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
                    Portnum.POSITION_APP -> handlePosition(packet)
                    Portnum.NODEINFO_APP -> handleNodeInfo(packet)
                    Portnum.AIRTAK_PLAYER -> handlePlayer(packet)
                    Portnum.TELEMETRY_APP -> handleTelemetry(packet)
                }
            }
        }
        scope.launch {
            dao.deleteOlderThan(System.currentTimeMillis() - NODE_RETENTION_MS)
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
        update(packet.srcNodeId) { existing, now ->
            existing.copy(
                shortName = user.shortName.orEmpty().ifBlank { existing.shortName },
                longName = user.longName.orEmpty().ifBlank { existing.longName },
                lastNodeInfoMs = now,
                lastSeenMs = now,
            )
        }
    }

    private suspend fun handlePlayer(packet: MeshRxPacket) {
        val payload = packet.payload ?: return
        val player = runCatching { PlayerStatePacket.parseFrom(payload) }.getOrNull() ?: return
        val nodeId = (if (player.nodeId != 0) player.nodeId.toLong() else packet.srcNodeId) and 0xFFFF_FFFFL
        update(nodeId) { existing, now ->
            existing.copy(
                callsign = player.callsign.ifBlank { existing.callsign },
                fireteamId = player.fireteamId.takeIf { it.isNotBlank() } ?: existing.fireteamId,
                squadId = player.squadId.takeIf { it.isNotBlank() } ?: existing.squadId,
                platoonId = player.platoonId.takeIf { it.isNotBlank() } ?: existing.platoonId,
                companyId = player.companyId.takeIf { it.isNotBlank() } ?: existing.companyId,
                statusValue = player.statusValue,
                colorRgb = player.colorRgb,
                lastPlayerMs = now,
                lastSeenMs = now,
            )
        }
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

    private companion object {
        const val NODE_RETENTION_MS = 24L * 60L * 60L * 1_000L
    }
}