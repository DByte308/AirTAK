package com.airtak.data.mesh

import com.airtak.data.db.RemoteNodeEntity
import com.airtak.proto.PlayerStatePacket

// ── PlayerStatePacket field numbers (game.proto) ─────────────────────────────
internal const val PLAYER_FIELD_CALLSIGN = 2
internal const val PLAYER_FIELD_FIRETEAM = 6
internal const val PLAYER_FIELD_SQUAD    = 7
internal const val PLAYER_FIELD_PLATOON  = 8
internal const val PLAYER_FIELD_COMPANY  = 9

/**
 * Merge identity fields by wire presence in [payload]: absent keeps, empty clears,
 * non-empty replaces. This also accepts legacy senders that omit empty strings.
 * Status and color always replace the stored values.
 */
internal fun mergePlayerState(
    existing: RemoteNodeEntity,
    player: PlayerStatePacket,
    payload: ByteArray,
    now: Long,
): RemoteNodeEntity = existing.copy(
    callsign = if (MeshWire.wireHasField(payload, PLAYER_FIELD_CALLSIGN)) player.callsign else existing.callsign,
    fireteamId = if (MeshWire.wireHasField(payload, PLAYER_FIELD_FIRETEAM)) player.fireteamId else existing.fireteamId,
    squadId = if (MeshWire.wireHasField(payload, PLAYER_FIELD_SQUAD)) player.squadId else existing.squadId,
    platoonId = if (MeshWire.wireHasField(payload, PLAYER_FIELD_PLATOON)) player.platoonId else existing.platoonId,
    companyId = if (MeshWire.wireHasField(payload, PLAYER_FIELD_COMPANY)) player.companyId else existing.companyId,
    statusValue = player.statusValue,
    colorRgb = player.colorRgb,
    lastPlayerMs = now,
    lastSeenMs = now,
)

/**
 * Merge NODEINFO names: null keeps the stored value; empty clears it.
 */
internal fun mergeNodeInfo(
    existing: RemoteNodeEntity,
    user: MeshtasticUser,
    now: Long,
): RemoteNodeEntity = existing.copy(
    shortName = user.shortName ?: existing.shortName,
    longName = user.longName ?: existing.longName,
    lastNodeInfoMs = now,
    lastSeenMs = now,
)
