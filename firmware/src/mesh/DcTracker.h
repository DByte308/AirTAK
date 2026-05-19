// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/DcTracker.h — Duty-cycle trailing-hour accumulator and relay throttle
// Planning: §8.7.1 | Roadmap: 6.10
//
// Maintains a circular buffer of TX timestamps with associated ToA durations.
// Time-on-air is measured from the hardware by timing the RadioLib transmit()
// call — no theoretical formula needed. The radio blocks for the exact on-air
// duration, so the elapsed time IS the ToA.
//
// Compares the trailing-hour accumulator against per-sub-band regulatory limits
// and classifies relay packets into three priority tiers for DC-aware suppression.
// Source traffic (Tier 0) is never dropped; the mechanism only throttles relay traffic.

#pragma once

#include <cstdint>
#include "mesh/Portnums.h"

namespace airtak {

enum class DcLevel : uint8_t {
    DC_OK       = 0,   // 0-59%:  normal operation
    DC_CAUTION  = 1,   // 60-79%: all traffic accepted; Android notified
    DC_WARNING  = 2,   // 80-94%: Tier 2 relay dropped; e-ink "Radio Busy"
    DC_CRITICAL = 3,   // 95-100%: only Tier 1 relay + source; e-ink "Radio Guard"
};

class DcTracker {
public:
    static void init();

    // Record a completed TX event. Called from Radio::transmit() after
    // successful RadioLib transmit. toa_ms is the measured elapsed time
    // of the blocking transmit() call — hardware-measured, not computed.
    static void recordTx(uint32_t toa_ms, uint32_t timestamp_ms);

    // Returns the current DC level by comparing trailing-hour accumulator
    // against the per-band regulatory limit.
    static DcLevel currentLevel();

    // Trailing-hour accumulator in milliseconds.
    static uint32_t trailingHourMs();

    // Per-band DC limit in ms/hour. Returns UINT32_MAX for regions with no limit.
    static uint32_t dcLimitMsPerHour();

    // Current duty-cycle percentage in hundredths (e.g., 750 = 7.50%).
    // Returns 0 if no limit applies.
    static uint32_t dcPercentage();

    // Number of relay packets dropped by the DC-aware gate since boot.
    static uint32_t relaysDropped();

    // Increment the dropped-relay counter. Called by Router when the
    // DC gate decides to suppress a relay.
    static void incrementDroppedRelays();

    // Returns true if the given portnum is Tier 1 relay priority (§8.7.1).
    // Tier 1 packets are relayed through DC_WARNING, dropped only at DC_CRITICAL.
    // NOTE: AIRTAK_PLAYER classification depends on is_state_transition field;
    // this function returns false for AIRTAK_PLAYER — the caller must check
    // airtakPlayerIsStateTransition() separately to promote state transitions.
    static bool isTier1Relay(Portnum portnum);

    // Reset accumulator and clear TX log. Called on LoRa region change (§9.4.2).
    static void reset();

    // Tick: expire entries older than 1 hour. Called periodically.
    static void tick();

#ifdef AIRTAK_NATIVE_TEST
    static size_t testTxLogCount();
    static uint32_t testTrailingHourMs();
    static DcLevel testCurrentLevel();
    static void testReset();
#endif
};

} // namespace airtak
