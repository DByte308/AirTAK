// AirTAK Android — data/mesh/PlayerStatePresence.kt
// #24 explicit-presence helper for building a PlayerStatePacket.
//
// proto3 `optional` name/unit fields: setting a field marks it PRESENT, even if
// the value is empty, so it serialises as "present with empty value". This
// extension centralises the rule so callers don't accidentally call the field
// setter for a heartbeat (which would wrongly clear a peer's stored name).
//
//   - non-null value -> field PRESENT (empty => receiver clears to the NODEINFO
//     short/long-name fallback; non-empty => receiver sets it).
//   - null           -> field ABSENT (receiver keeps its stored value).
//
// Use non-null on a state transition (set/clear) and null on a name-preserving
// heartbeat.

package com.airtak.data.mesh

import com.airtak.proto.PlayerStatePacket

/**
 * Apply the #24 explicit-presence rule to a [PlayerStatePacket.Builder]. See the
 * file comment for the null/non-null semantics.
 */
fun PlayerStatePacket.Builder.applyNamePresence(
    callsign: String?,
    fireteam: String?,
    squad: String?,
    platoon: String?,
    company: String?,
): PlayerStatePacket.Builder {
    if (callsign != null) setCallsign(callsign)
    if (fireteam != null) setFireteamId(fireteam)
    if (squad != null) setSquadId(squad)
    if (platoon != null) setPlatoonId(platoon)
    if (company != null) setCompanyId(company)
    return this
}
