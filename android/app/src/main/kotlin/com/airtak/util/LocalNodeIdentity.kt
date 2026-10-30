package com.airtak.util

import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceConfig
import com.airtak.proto.DeviceStatus
import com.airtak.proto.GameConfig

/** One decoded identity snapshot for the own-node map label, roster and HUD. */
data class LocalNodeIdentity(
    val game: GameConfig = GameConfig.getDefaultInstance(),
    val device: DeviceConfig = DeviceConfig.getDefaultInstance(),
) {
    fun displayName(status: DeviceStatus?, fallback: String = "AirTAK"): String =
        NodeNames.resolveOrNull(
            callsign = game.callsign,
            shortName = device.shortName,
            longName = device.longName.takeIf { it.isNotBlank() } ?: status?.nodeName,
        ) ?: fallback

    // PlayerState.callsign is the configured callsign, not a UI label. Sending
    // "You", "AirTAK", or a resolved long name would override peers' NODEINFO
    // fallback and can exceed this protocol field's 16-byte name budget.
    fun broadcastCallsign(): String = game.callsign.trim()

    companion object {
        fun from(groups: Map<ConfigGroup, ByteArray>): LocalNodeIdentity = LocalNodeIdentity(
            game = groups[ConfigGroup.GAME]
                ?.let { runCatching { GameConfig.parseFrom(it) }.getOrNull() }
                ?: GameConfig.getDefaultInstance(),
            device = groups[ConfigGroup.DEVICE]
                ?.let { runCatching { DeviceConfig.parseFrom(it) }.getOrNull() }
                ?: DeviceConfig.getDefaultInstance(),
        )
    }
}
