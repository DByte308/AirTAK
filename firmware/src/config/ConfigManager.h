// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/ConfigManager.h — Hot-reload config engine
// Planning: §9.4, §9.4.2 | Roadmap: 2.8
//
// Manages the in-memory config state for all 8 config groups.
// Supports staged commit (commit=false for validation, commit=true to persist).
// Config changes that affect radio (LORA, CHANNELS) trigger the radio restart procedure:
//   pause → drain TX queue → reinit radio → rekey channels → resume (≤ 300 ms, §9.4.2).

#pragma once

#include <cstdint>
#include <cstddef>
#include "config/ConfigGroups.h"

// Factory defaults are serialized with the committed nanopb bindings generated
// from proto/airtak/config.proto by tools/proto_gen.ps1.

namespace airtak {
namespace config {

// Callback invoked after a config group is successfully committed.
// Receives the group that changed. Implementations should update their local copy.
using ConfigChangedCallback = void (*)(ConfigGroup group);

class ConfigManager {
public:
    // Phase 2.8: Load all config groups from FlashStore.
    // Groups missing from flash are initialized with factory defaults from Defaults.h.
    static void init();

    // Phase 2.8: Write a config group from a nanopb-encoded buffer.
    // If commit=false: validates and stages the proto without changing committed state.
    // If commit=true: persists staged groups, updates committed state, and triggers side effects.
    // Returns 0 on success, negative error code on failure.
    static int applyGroup(ConfigGroup group, const uint8_t* pb_data, size_t pb_len, bool commit);

    // Phase 2.8: Read current in-memory state of a config group as a nanopb-encoded buffer.
    // Returns number of bytes written to buf, or 0 on failure.
    static size_t readGroup(ConfigGroup group, uint8_t* buf, size_t buf_len);

    // Phase 2.8: Factory reset a single config group (re-apply Defaults.h values).
    static void resetGroup(ConfigGroup group);

    // Phase 2.8: Register a callback for config change notifications.
    static void registerCallback(ConfigChangedCallback cb);

    // Phase 2.8: Perform the radio restart procedure for groups that require it (§9.4.2).
    // pause→drain→reinit→rekey→resume, target ≤ 300 ms.
    static void radioRestartProcedure();

    // Returns true if a radio restart is pending (groups committed but restart not yet run).
    static bool isRadioRestartPending();

    // Returns the device's configured long name (from DEVICE config group, field 1).
    // Falls back to the compile-time default if not configured.
    static const char* deviceLongName();

    // Returns the device's configured short name (from DEVICE config group, field 2).
    // Falls back to the compile-time default if not configured.
    static const char* deviceShortName();

    // ── Position parameter accessors (read from POSITION config buffer) ─────
    static uint32_t positionBroadcastIntervalSecs();
    static uint32_t positionMinTxIntervalSecs();
    static uint32_t positionMovementThresholdM();
    static uint32_t positionPrecisionBits();
    static uint8_t  positionHopLimitOverride();

    // ── LoRa parameter accessors (read from LORA config buffer) ──────────────
    // These are used by Radio::reinit() to apply the committed LORA config
    // without relying on hardcoded defaults (§9.4.2, Roadmap 2.8).

    // Returns the LoRa frequency in MHz derived from the configured region
    // (field 1: 0=EU_868 → 869.525 MHz, 1=US_915 → 906.875 MHz).
    static float loraFrequencyMhz();

    // Returns the configured TX power in dBm (field 2 of LORA config).
    // Falls back to LORA_TX_POWER_DBM compile-time default if not set.
    static uint8_t loraTxPowerDbm();

    // Returns the phy_profile_index of the PRIMARY channel slot (role=1)
    // from the CHANNELS config buffer (field 8 of the PRIMARY slot sub-message).
    // Falls back to 0 (Long/Mesh) if no PRIMARY slot is found or field is absent.
    static uint8_t primarySlotPhyProfileIndex();

    // Reads a PHY profile by index from LORA config. Returns false on malformed
    // config or an out-of-range index, in which case callers should use Defaults.h.
    static bool loraPhyProfile(uint8_t profile_index,
                               uint8_t& spreading_factor,
                               uint32_t& bandwidth_hz,
                               uint8_t& coding_rate);
};

} // namespace config
} // namespace airtak
