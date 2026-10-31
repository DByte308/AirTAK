// AirTAK Android — data/mesh/RemoteNodeMerge.kt
// Pure, unit-testable merge logic for remote-node identity fields (#24).
//
// proto3 scalar fields have IMPLICIT presence: an OMITTED value and an
// EXPLICITLY EMPTY value are indistinguishable after parse (both decode to the
// default ""). PlayerStatePacket.callsign + the unit-membership strings are in
// this category, so RemoteNodeRepository was keeping the previously stored value
// on empty input — hiding the NODEINFO short/long-name fallback after an owner
// cleared their callsign.
//
// Contract (#24): the sender uses proto3 `optional` so that setting a field to
// an empty string is encoded on the wire as "present with empty value". This
// receiver reads the presence bit from the RAW wire bytes (robust to old and new
// senders alike):
//   - field ABSENT            -> keep the existing stored value;
//   - field PRESENT, empty    -> clear to "" (name resolution then falls back);
//   - field PRESENT, non-empty-> set the value.
// Short/long name (NODEINFO) already carry presence through the nullable decode
// (null = absent), so they are merged with a null-coalescing rule.

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
 * Merge an incoming [PlayerStatePacket] into [existing], honouring the #24
 * explicit-presence rule for name/unit fields. Non-name non-presence fields
 * (status, color) are always overwritten. [payload] is the raw packet wire bytes
 * used to detect field presence.
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
 * Merge NODEINFO [user] identity into [existing]. `MeshtasticUser.shortName/
 * longName` are nullable: null = field absent (keep stored), "" = present-empty
 * (clear), non-empty = set.
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
