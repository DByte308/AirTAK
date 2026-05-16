// AirTAK Android — data/ble/BleLink.kt
//
// Public sealed types describing the BLE link state machine (§11.10), the set
// of receivable characteristics, and the inbound packet envelope. These types
// have no Android dependencies and are friendly to unit tests.

package com.airtak.data.ble

sealed interface LinkState {
    /** No active link; idle (e.g. on cold boot before scanning). */
    data object Disconnected : LinkState

    /** Scanning for advertisements (initial pairing flow only). */
    data object Scanning : LinkState

    /** Waiting for the user to pair (system-driven, e.g. numeric comparison dialog). */
    data class Pairing(val nodeName: String?) : LinkState

    /** GATT connect / service discovery / MTU negotiation in progress. */
    data class Connecting(val address: String) : LinkState

    /** Link is up; can read/write/notify. */
    data class Connected(val address: String, val mtu: Int) : LinkState

    /** Connection lost; backing off before next attempt. */
    data class Reconnecting(val address: String, val nextDelayMs: Long) : LinkState

    /** Permanent failure surfaced to UI; user must retry. */
    data class Failed(val reason: String) : LinkState
}

/** Discovered AirTAK device — surfaced to the pairing wizard. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/** Inbound payload from any notify-capable AirTAK characteristic. */
enum class InboundChannel { ConfigRw, MeshRx, DeviceStatus, Admin }

data class InboundFrame(
    val channel: InboundChannel,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is InboundFrame && other.channel == channel && other.payload.contentEquals(payload)
    override fun hashCode(): Int = 31 * channel.hashCode() + payload.contentHashCode()
}
