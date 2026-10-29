// AirTAK Android — config/constants/NetConstants.kt
//
// Outbound HTTP time bounds.
//
// These exist because "no timeout configured" is not the same as "unbounded" in a way anyone can
// read: the engine has implicit defaults, so a stalled provider does eventually fail — after
// roughly a minute per phase, with nothing in the source saying which minute, and with different
// numbers on different engines. A tile request that hangs for a minute holds a request budget slot
// for a provider and leaves the map showing nothing with no explanation.
//
// The bounds are declared here so the behaviour is one number per phase, stated once, and so a
// test can assert against the same value production uses.

package com.airtak.config.constants

// ── Tile and provider HTTP (§ network compliance) ───────

/** Time to establish the TCP + TLS connection before the attempt is abandoned. */
const val NET_CONNECT_TIMEOUT_MS = 10_000L

/**
 * Time waiting for data between packets. This is the phase a stalled provider occupies: the
 * connection succeeds, headers arrive or do not, and then nothing. Without an explicit value the
 * engine's socket default applies, which is long enough to look like a frozen map.
 */
const val NET_SOCKET_TIMEOUT_MS = 10_000L

/** Whole-request bound enforced by Ktor's own timeout plugin, covering connect + all reads. */
const val NET_REQUEST_TIMEOUT_MS = 15_000L

/**
 * Outer bound around one tile fetch, slightly above [NET_REQUEST_TIMEOUT_MS] so the plugin's own
 * timeout normally fires first and produces its specific error. This one is the backstop for a
 * phase the plugin does not cover — a response that starts trickling and never finishes inside
 * the caller's own window.
 */
const val NET_TILE_FETCH_TOTAL_TIMEOUT_MS = 20_000L
