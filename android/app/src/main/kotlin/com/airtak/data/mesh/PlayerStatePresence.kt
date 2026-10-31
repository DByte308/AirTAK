package com.airtak.data.mesh

import com.airtak.proto.PlayerStatePacket

/**
 * Set optional identity fields: empty clears the peer's value; null leaves the
 * builder unchanged. On a fresh builder, null omits the field (peer keeps its value).
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
