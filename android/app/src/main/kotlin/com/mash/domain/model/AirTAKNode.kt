// AirTAK Android — domain/model/AirTAKNode.kt
// Planning: §13.2, §11 | Roadmap: 2.2, 3.3

package com.airtak.domain.model

data class AirTAKNode(
    val nodeId: Long,
    val callsign: String,
    val teamIndex: Int,
    val squadIndex: Int,
    val latE7: Int?,        // latitude × 10^7; null when unknown
    val lonE7: Int?,        // longitude × 10^7; null when unknown
    val lastSeenMs: Long,
    val rssi: Float,
    val snr: Float,
    val hwModel: Int,       // HardwareModel proto enum ordinal
)
