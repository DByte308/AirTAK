// AirTAK Android — util/NodeNames.kt
//
// One rule for labelling a node, used by the map, the HUD, the roster and the
// message list.
//
// Before this existed, four call sites each picked a name differently:
//   MapViewModel.toMapNode      callsign -> shortName -> hex
//   MapViewModel own-position     callsign -> nodeName(=long name) -> "You"
//   AppShell HudBar               nodeName only, callsign ignored
//   GameRepository                callsign -> nodeName(=long name) -> "AirTAK"
// proto/airtak/ble.proto documents DeviceStatus.node_name as
// "DeviceConfig.long_name", so those sites disagreed about whether a player was
// called by their callsign or by their 39-byte device long name -- which is what
// upstream issue #7 reports ("mal callsign, mal langen namen").

package com.airtak.util

object NodeNames {

    /**
     * Precedence: callsign, then short name, then long name, then a short hex
     * node id. Whitespace-only counts as blank. Every field is optional so a
     * caller can pass null for whatever it does not have.
     */
    fun resolve(
        callsign: String?,
        shortName: String? = null,
        longName: String? = null,
        nodeId: Long? = null,
    ): String =
        firstNonBlank(callsign, shortName, longName)
            ?: nodeId?.let { fallbackHex(it) }
            ?: ""

    /** Same precedence, but returns null instead of a hex id when nothing is set. */
    fun resolveOrNull(
        callsign: String?,
        shortName: String? = null,
        longName: String? = null,
    ): String? = firstNonBlank(callsign, shortName, longName)

    /** Last-resort label so a node is never anonymous on screen. */
    fun fallbackHex(nodeId: Long): String =
        nodeId.toString(16).uppercase().takeLast(4)

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
