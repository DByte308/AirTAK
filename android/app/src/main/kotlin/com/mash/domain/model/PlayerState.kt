// MASH Android — domain/model/PlayerState.kt
// Planning: §12.1, §12.6 | Roadmap: 5.3, 5.4

package com.mash.domain.model

enum class PlayerStatus { ACTIVE, HIT, RESPAWNING, ELIMINATED, SPECTATOR }

data class PlayerState(
    val nodeId: Long,
    val callsign: String,
    val teamIndex: Int,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
    val respawnSecondsRemaining: Int = 0,
    val lastUpdateMs: Long = 0L,
)
