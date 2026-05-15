// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/Portnums.h — Port number enumeration (Meshtastic + MASH)
// Planning: §8.4 | Roadmap: T.1, T.2

#pragma once

#include <cstdint>

namespace mash {

// Standard Meshtastic port numbers (used on channel 0, interop with stock Meshtastic).
// See §8.4 for the full table with hop_limit and reliability class per type.
enum class Portnum : uint32_t {
    UNKNOWN_APP      = 0,
    TEXT_MESSAGE_APP = 1,
    POSITION_APP     = 3,
    NODEINFO_APP     = 4,
    TELEMETRY_APP    = 67,
    TRACEROUTE_APP   = 71,
    NEIGHBORINFO_APP = 73,

    // ── MASH private range (256–511) ─────────────────────────────────────────
    // Relayed by Meshtastic nodes but not decryptable without the game channel PSK.

    MASH_PLAYER           = 256,  // PlayerStatePacket; hop_limit=3; ACKNOWLEDGED on transition
    MASH_GAME             = 257,  // GameStatePacket; hop_limit=7; ACKNOWLEDGED
    MASH_ZONE             = 258,  // ZonePacket; hop_limit=7; ACKNOWLEDGED
    MASH_OBJECTIVE        = 259,  // ObjectivePacket; hop_limit=5; ACKNOWLEDGED
    MASH_ADMIN            = 260,  // AdminPacket; hop_limit=7; ACKNOWLEDGED
    MASH_ACK              = 261,  // MashAck; hop_limit=hop_start of original; required for §8.9
    MASH_POSITION_REQUEST = 262,  // PositionRequestPacket; hop_limit=5; BEST_EFFORT
    MASH_AUDIO            = 263,  // AudioPacket; hop_limit=1; BEST_EFFORT; MUST NOT relay
};

// Returns true if portnum is in the MASH private range.
inline bool isMashPortnum(Portnum p) {
    return static_cast<uint32_t>(p) >= 256 && static_cast<uint32_t>(p) <= 511;
}

// Returns true if portnum should be delivered with ACKNOWLEDGED reliability (§8.9).
// MASH_PLAYER is handled specially: ACKNOWLEDGED only when is_state_transition=true.
inline bool isAcknowledgedPortnum(Portnum p) {
    switch (p) {
        case Portnum::MASH_GAME:
        case Portnum::MASH_ZONE:
        case Portnum::MASH_OBJECTIVE:
        case Portnum::MASH_ADMIN:
            return true;
        default:
            return false;
    }
}

} // namespace mash
