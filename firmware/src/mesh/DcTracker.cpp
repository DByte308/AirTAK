// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/DcTracker.cpp — Duty-cycle trailing-hour accumulator and relay throttle
// Planning: §8.7.1 | Roadmap: 6.10
//
// Time-on-air is measured from hardware by timing the blocking RadioLib transmit()
// call. The radio holds the bus for exactly the on-air duration, so elapsed time IS ToA.
// No theoretical formula — the hardware tells us.

#include "mesh/DcTracker.h"
#include "config/Defaults.h"
#include "config/ConfigManager.h"
#include "config.h"
#include <Arduino.h>
#include <cstring>

namespace airtak {

// ── TxRecord and circular buffer (§8.7.1) ────────────────────────────────────
struct TxRecord {
    uint32_t timestamp_ms;
    uint16_t toa_ms;
};

static TxRecord s_tx_log[defaults::DC_TX_LOG_SIZE];
static uint8_t  s_tx_log_head = 0;
static uint32_t s_trailing_hour_ms = 0;
static uint32_t s_last_tick_ms = 0;
static DcLevel   s_current_level = DcLevel::DC_OK;

// ── Per-band DC limits (§8.7.1 table) ────────────────────────────────────────
static uint32_t dcLimitMsForRegion() {
    float freq_mhz = config::ConfigManager::loraFrequencyMhz();
    if (freq_mhz >= 863.0f && freq_mhz <= 870.0f) {
        // EU868 sub-band g1 (869.400–869.650 MHz): 10% = 360 s/h
        // AirTAK default 869.525 MHz falls within g1.
        return defaults::DC_LIMIT_EU868_G1_MS_PER_HOUR;
    }
    // Other regions: no mandatory duty-cycle limit. Throttling is inert.
    return UINT32_MAX;
}

// ── DC level evaluation ──────────────────────────────────────────────────────
static void reevaluateDcLevel() {
    uint32_t limit = dcLimitMsForRegion();
    if (limit == UINT32_MAX) {
        s_current_level = DcLevel::DC_OK;
        return;
    }
    uint32_t pct = (s_trailing_hour_ms * 100UL) / limit;
    if (pct >= defaults::DC_CRITICAL_THRESHOLD_PCT) {
        s_current_level = DcLevel::DC_CRITICAL;
    } else if (pct >= defaults::DC_WARNING_THRESHOLD_PCT) {
        s_current_level = DcLevel::DC_WARNING;
    } else if (pct >= defaults::DC_CAUTION_THRESHOLD_PCT) {
        s_current_level = DcLevel::DC_CAUTION;
    } else {
        s_current_level = DcLevel::DC_OK;
    }
}

// ── DcTracker public API ─────────────────────────────────────────────────────

void DcTracker::init() {
    memset(s_tx_log, 0, sizeof(s_tx_log));
    s_tx_log_head = 0;
    s_trailing_hour_ms = 0;
    s_last_tick_ms = millis();
    s_current_level = DcLevel::DC_OK;
}

void DcTracker::recordTx(uint32_t toa_ms, uint32_t timestamp_ms) {
    if (toa_ms == 0) return;

    // Discard entries older than 1 hour before inserting new one.
    tick();

    // Evict oldest entry if slot is occupied (FIFO circular buffer).
    TxRecord* slot = &s_tx_log[s_tx_log_head];
    if (slot->timestamp_ms != 0 && slot->toa_ms > 0) {
        int32_t age_ms = (int32_t)(timestamp_ms - slot->timestamp_ms);
        if (age_ms < 3600000 && s_trailing_hour_ms >= slot->toa_ms) {
            s_trailing_hour_ms -= slot->toa_ms;
        } else {
            s_trailing_hour_ms = 0;
        }
    }

    slot->timestamp_ms = timestamp_ms;
    slot->toa_ms = static_cast<uint16_t>(toa_ms);
    s_trailing_hour_ms += toa_ms;

    s_tx_log_head++;
    if (s_tx_log_head >= defaults::DC_TX_LOG_SIZE) s_tx_log_head = 0;

    reevaluateDcLevel();
}

DcLevel DcTracker::currentLevel() {
    return s_current_level;
}

uint32_t DcTracker::trailingHourMs() {
    return s_trailing_hour_ms;
}

uint32_t DcTracker::dcLimitMsPerHour() {
    return dcLimitMsForRegion();
}

bool DcTracker::isTier1Relay(Portnum portnum) {
    switch (portnum) {
        case Portnum::AIRTAK_ACK:
        case Portnum::AIRTAK_MODERATION:
        case Portnum::AIRTAK_POSITION_REQUEST:
        case Portnum::AIRTAK_TACTICAL:
            return true;
        default:
            return false;
    }
}

void DcTracker::reset() {
    memset(s_tx_log, 0, sizeof(s_tx_log));
    s_tx_log_head = 0;
    s_trailing_hour_ms = 0;
    s_current_level = DcLevel::DC_OK;
}

void DcTracker::tick() {
    uint32_t now_ms = millis();

    // Rate-limit to once per second.
    if ((int32_t)(now_ms - s_last_tick_ms) < 1000) return;
    s_last_tick_ms = now_ms;

    for (uint8_t i = 0; i < defaults::DC_TX_LOG_SIZE; i++) {
        TxRecord* entry = &s_tx_log[i];
        if (entry->timestamp_ms == 0 || entry->toa_ms == 0) continue;

        int32_t age_ms = (int32_t)(now_ms - entry->timestamp_ms);
        if (age_ms >= 3600000) {
            if (s_trailing_hour_ms >= entry->toa_ms) {
                s_trailing_hour_ms -= entry->toa_ms;
            } else {
                s_trailing_hour_ms = 0;
            }
            entry->timestamp_ms = 0;
            entry->toa_ms = 0;
        }
    }
}

#ifdef AIRTAK_NATIVE_TEST
size_t DcTracker::testTxLogCount() {
    size_t count = 0;
    for (uint8_t i = 0; i < defaults::DC_TX_LOG_SIZE; i++) {
        if (s_tx_log[i].timestamp_ms != 0) count++;
    }
    return count;
}

uint32_t DcTracker::testTrailingHourMs() {
    return s_trailing_hour_ms;
}

DcLevel DcTracker::testCurrentLevel() {
    return s_current_level;
}

void DcTracker::testReset() {
    reset();
}
#endif

} // namespace airtak
