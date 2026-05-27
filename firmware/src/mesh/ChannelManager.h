// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/ChannelManager.h — Channel slot table and hash dispatch
// Planning: §8.5, §8.6 | Roadmap: 2.7
//
// Manages 8 channel slots. Each slot has its own PSK, role, and PHY profile index.
// Channel disambiguation by hash (§8.6): XOR-fold for slot 0, SHA-256[0] for slots 1–7.
// When all active slots share the same PHY profile, O(1) per-packet channel dispatch
// with no radio switching overhead.

#pragma once

#include <cstdint>
#include <cstddef>
#include "mesh/Crypto.h"

namespace airtak {

constexpr uint8_t MAX_CHANNEL_SLOTS = 8;

// esp32-hal-gpio.h (Arduino-ESP32) defines DISABLED as a C macro (0x00).
// Undefine it here so it does not corrupt the enum class member name below.
#ifdef DISABLED
#undef DISABLED
#endif

enum class ChannelRole : uint8_t { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 };

struct ChannelSlot {
    uint8_t      index;
    char         name[17];              // max 16 + null terminator
    uint8_t      psk[32];
    size_t       psk_len;
    ChannelRole  role;
    bool         uplink_enabled;
    bool         downlink_enabled;
    uint8_t      position_precision;
    uint8_t      phy_profile_index;
    uint8_t      channel_hash;          // computed by rekey(); cached here
    uint8_t      aes_key[32];           // derived from psk by rekey(); 16 or 32 bytes
    uint8_t      aes_key_len;           // 16 (AES-128) or 32 (AES-256)
};

class ChannelManager {
public:
    // Phase 2.7: Load channel config from ConfigManager and compute all channel hashes
    // and AES key schedules. Called at init and after any CHANNELS config group change.
    static void init();
    static void rekey(); // called during radio restart procedure (§9.4.2)

    // Phase 2.7: Find a channel slot matching the given hash byte.
    // Returns nullptr if no active slot matches (packet should be discarded).
    static const ChannelSlot* findByHash(uint8_t hash);
    static const ChannelSlot* findByHash(uint8_t hash, uint8_t start_index);

    // Returns the PRIMARY slot (slot 0 by default).
    static const ChannelSlot* primarySlot();

    // Returns a slot by index (0–7). Returns nullptr if index is out of range.
    static const ChannelSlot* slotByIndex(uint8_t index);

    // Returns true if any two active slots reference different PHY profile indexes.
    // If true, multi-PHY time-multiplexing is required (Phase 7).
    static bool requiresMultiPhy();
};

} // namespace airtak
