// MASH Android — domain/model/GameState.kt
// Planning: §12.1–12.5 | Roadmap: 5.1–5.6

package com.mash.domain.model

enum class GamePhase { IDLE, LOBBY, ACTIVE, PAUSED, ENDED }
enum class GameMode  { DEATHMATCH, DOMINATION, CAPTURE_FLAG, ELIMINATION, CUSTOM }

data class GameState(
    val phase: GamePhase = GamePhase.IDLE,
    val mode: GameMode   = GameMode.DEATHMATCH,
    val timeRemainingSeconds: Int = 0,
    val teamScores: List<TeamScore> = emptyList(),
    val zones: List<ZoneMirror>    = emptyList(),
)

data class TeamScore(
    val teamIndex: Int,
    val teamName: String,
    val score: Int,
    val playerCount: Int,
)

data class ZoneMirror(
    val zoneId: Int,
    val zoneType: Int,
    val latE7: Int,
    val lonE7: Int,
    val radiusM: Int,
    val name: String,
    val controlledBy: Int,  // team index; -1 = contested
    val active: Boolean,
)
