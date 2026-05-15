// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Portnums.h — Port number enumeration (Meshtastic + AirTAK)
// Planning: §8.4 | Roadmap: T.1, T.2

#pragma once

#include <cstdint>

namespace airtak {

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

    // ── AirTAK private range (256–511) ─────────────────────────────────────────
    // Relayed by Meshtastic nodes but not decryptable without the game channel PSK.

    AIRTAK_PLAYER           = 256,  // PlayerStatePacket; hop_limit=7; ACKNOWLEDGED on transition
    AIRTAK_GAME             = 257,  // GameStatePacket; hop_limit=7; ACKNOWLEDGED
    AIRTAK_ZONE             = 258,  // ZonePacket; hop_limit=7; ACKNOWLEDGED
    AIRTAK_OBJECTIVE        = 259,  // ObjectivePacket; hop_limit=7; ACKNOWLEDGED
    AIRTAK_ADMIN            = 260,  // AdminPacket; hop_limit=7; ACKNOWLEDGED
    AIRTAK_ACK              = 261,  // AirTAKAck; hop_limit=hop_start of original; required for §8.9
    AIRTAK_POSITION_REQUEST = 262,  // PositionRequestPacket; hop_limit=7; BEST_EFFORT
    AIRTAK_AUDIO            = 263,  // AudioPacket; hop_limit=1; BEST_EFFORT; MUST NOT relay
};

// Returns true if portnum is in the AirTAK private range.
inline bool isAirTAKPortnum(Portnum p) {
    return static_cast<uint32_t>(p) >= 256 && static_cast<uint32_t>(p) <= 511;
}

// ACKNOWLEDGED delivery eligibility is defined by Router::isAcknowledgedPortnum
// (Router.h). That version includes TEXT_MESSAGE_APP and AIRTAK_PLAYER, which are
// conditionally ACKed at the call site (unicast vs broadcast, is_state_transition).
// Do not add a separate copy here — it will diverge.

} // namespace airtak
