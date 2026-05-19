// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Radio.cpp — SX1262 / SX1276 radio HAL implementation
// Planning: §8.1, §9.2 | Roadmap: 1.2
//
// Uses RadioLib ≥ 6.4.0. Supports SX1262 (default, all currently supported boards)
// and SX1276 (T3 S3 multi-radio board).  At init(), both radios are probed on the
// shared SPI bus; the first to respond becomes the active radio for the session.
//
// All SX1262 parameters must match §8.1 global PHY parameters and the active
// LoraConfig PHY profile.  PHY profiles are loaded from ConfigManager.

#include "hal/Radio.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "mesh/ChannelManager.h"
#include "mesh/PacketPool.h"
#include "mesh/DcTracker.h"
#include "ble/BLEQueue.h"
#include "config.h"

#include <RadioLib.h>
#include <SPI.h>
#include <cstring>

#ifndef IRAM_ATTR
#define IRAM_ATTR
#endif

namespace airtak {

// ── Radio type ─────────────────────────────────────────────────────────────────
enum class RadioType : uint8_t {
    NONE     = 0,
    SX1262   = 1,
    SX1276   = 2,
    SX1280   = 3,   // reserved — pins defined, driver pending
    LR1121   = 4,   // reserved — pins defined, driver pending
};

// ── SX1262 state ───────────────────────────────────────────────────────────────
// Module and radio objects for the SX1262 variant.  These are used when the probe
// detects an SX1262 (or when the board only defines SX1262 pins).
static Module        s_mod_sx1262(AIRTAK_PIN_SX1262_NSS, AIRTAK_PIN_SX1262_DIO1,
                                  AIRTAK_PIN_SX1262_RESET, AIRTAK_PIN_SX1262_BUSY);
static SX1262        s_radio_sx1262(&s_mod_sx1262);

// ── SX1276 state (T3 S3 multi-radio board) ────────────────────────────────────
// Only compiled when SX1276 pins are defined (e.g. T3 S3 BoardConfig.h).
#if defined(AIRTAK_PIN_SX1276_DIO0)
static Module        s_mod_sx1276(AIRTAK_PIN_LORA_CS, AIRTAK_PIN_SX1276_DIO0,
                                  AIRTAK_PIN_LORA_RESET, AIRTAK_PIN_SX1276_DIO2);
static SX1276        s_radio_sx1276(&s_mod_sx1276);
#endif

// ── Runtime-selected active radio ──────────────────────────────────────────────
static RadioType     s_radio_type    = RadioType::NONE;
static PhysicalLayer* s_active_radio  = nullptr;     // polymorphic access
static volatile bool s_radio_failed   = false;
static volatile float    s_last_rssi    = 0.0f;
static volatile float    s_last_snr     = 0.0f;
static volatile uint32_t s_packets_rx   = 0;
static volatile uint32_t s_packets_tx   = 0;
static volatile bool     s_rx_flag      = false;
static volatile bool     s_tx_flag      = false;
static uint8_t           s_active_phy   = 0;   // index of currently configured PHY profile

// ── DIO1 interrupt handler ────────────────────────────────────────────────────
// NOTE: RadioLib uses this internally when using interrupt-driven receive.
// We set it via setPacketReceivedAction / setPacketSentAction below.
static EventGroupHandle_t s_event_group = nullptr;

static void IRAM_ATTR on_packet_received() {
    s_rx_flag = true;
    if (s_event_group) {
        BaseType_t woken = pdFALSE;
        xEventGroupSetBitsFromISR(s_event_group, RADIO_EVENT_DIO1, &woken);
        portYIELD_FROM_ISR(woken);
    }
}

static void IRAM_ATTR on_packet_sent() {
    s_tx_flag = true;
    if (s_event_group) {
        BaseType_t woken = pdFALSE;
        xEventGroupSetBitsFromISR(s_event_group, RADIO_EVENT_TX_READY, &woken);
        portYIELD_FROM_ISR(woken);
    }
}

// ── resolvePhyProfile() ───────────────────────────────────────────────────────
// Resolve the SF/BW/CR for a PHY profile index.  Returns true on success and
// writes the validated values to out parameters.  Falls back to profile 0
// (Long/Mesh) if the requested profile is unusable.  Also sets s_active_phy.
// Does NOT write SX1262 registers — caller must do that after begin().
static bool resolvePhyProfile(uint8_t& profile_idx,
                              uint8_t& sf, uint32_t& bw_hz, uint8_t& cr) {
    if (profile_idx >= 5) profile_idx = 0;

    // Start from compile-time defaults.  loraPhyProfile() overlays the committed
    // LORA config on top when available; keep the defaults if it fails.
    sf    = defaults::PHY_PROFILES[profile_idx].spreading_factor;
    bw_hz = defaults::PHY_PROFILES[profile_idx].bandwidth_hz;
    cr    = defaults::PHY_PROFILES[profile_idx].coding_rate;
    if (!config::ConfigManager::loraPhyProfile(profile_idx, sf, bw_hz, cr)) {
        // Config is missing or malformed for this profile.  If the defaults are
        // also unusable (profile 4 User Defined = all zeros), fall back to
        // profile 0 Long/Mesh so the radio stays operational.
        if (sf < 7 || sf > 12 || bw_hz == 0 || cr < 5 || cr > 8) {
            profile_idx = 0;
            sf    = defaults::PHY_PROFILES[0].spreading_factor;
            bw_hz = defaults::PHY_PROFILES[0].bandwidth_hz;
            cr    = defaults::PHY_PROFILES[0].coding_rate;
        }
    }

    s_active_phy = profile_idx;
    return (sf >= 7 && sf <= 12 && bw_hz > 0 && cr >= 5 && cr <= 8);
}

// ── Radio::init() ─────────────────────────────────────────────────────────────
void Radio::init() {
    // Phase 1.2: Configure the radio with global PHY parameters from §8.1.
    // Default to profile 0 (Long/Mesh); overridden from config if available.
    //
    // Multi-radio boards (e.g. T3 S3) ship with different radio modules soldered
    // to the same PCB.  We probe SX1262 first (most common), then SX1276.
    // The first radio that responds to begin() becomes the active radio.

    uint8_t  initial_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (initial_phy >= 5) initial_phy = 0;

    uint8_t  sf    = 0;
    uint32_t bw_hz = 0;
    uint8_t  cr    = 0;
    if (!resolvePhyProfile(initial_phy, sf, bw_hz, cr)) {
        s_radio_failed = true;
        return;
    }

    float freq_mhz = config::ConfigManager::loraFrequencyMhz();
    if (freq_mhz < 100.0f) freq_mhz = (float)LORA_FREQ_EU868;
    uint8_t tx_power = config::ConfigManager::loraTxPowerDbm();

    // ── Probe SX1262 ──────────────────────────────────────────────────────────
    {
        int16_t result = s_radio_sx1262.begin(
            freq_mhz, bw_hz / 1000.0f, sf, cr,
            LORA_SYNC_WORD, tx_power, LORA_PREAMBLE_LENGTH,
            AIRTAK_SX1262_TCXO_VOLTAGE);

        if (result == RADIOLIB_ERR_NONE) {
            s_radio_type   = RadioType::SX1262;
            s_active_radio = &s_radio_sx1262;
            s_radio_sx1262.setCRC(true);
            s_radio_sx1262.explicitHeader();
            s_radio_sx1262.setDio2AsRfSwitch(true);
        }
    }

    // ── Probe SX1276 (fallback for T3 S3 multi-radio boards) ──────────────────
#if defined(AIRTAK_PIN_SX1276_DIO0)
    if (s_radio_type == RadioType::NONE) {
        int16_t result = s_radio_sx1276.begin(
            freq_mhz, bw_hz / 1000.0f, sf, cr,
            LORA_SYNC_WORD, tx_power, LORA_PREAMBLE_LENGTH);

        if (result == RADIOLIB_ERR_NONE) {
            s_radio_type   = RadioType::SX1276;
            s_active_radio = &s_radio_sx1276;
            // SX1276 uses implicit header by default; explicit is set in begin().
            // No DIO2-as-rf-switch — TX/RX switching is done via TXEN/RXEN pins
            // (defined in BoardConfig.h as AIRTAK_PIN_SX1276_RXEN/TXEN but
            //  RadioLib's SX1276 module handles this automatically when those
            //  pins are wired to DIO1/DIO2 as defined in the Module constructor).
        }
    }
#endif

    if (s_radio_type == RadioType::NONE) {
        s_radio_failed = true;
        return;
    }

    // Register interrupt callbacks and enter continuous receive.
    s_active_radio->setPacketReceivedAction(on_packet_received);
    s_active_radio->setPacketSentAction(on_packet_sent);
    s_active_radio->startReceive();
}

// ── Radio::reinit() ───────────────────────────────────────────────────────────
void Radio::reinit() {
    // Phase 2.8: Called by ConfigManager::radioRestartProcedure() (§9.4.2).
    // Re-apply LoRa parameters from the committed ConfigManager buffers.
    // Reinitialises whichever radio type was detected at init().

    // Reset the radio hardware via RESET pin.
    digitalWrite(AIRTAK_PIN_SX1262_RESET, LOW);
    delay(2);
    digitalWrite(AIRTAK_PIN_SX1262_RESET, HIGH);
    delay(10);

    uint8_t  new_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (new_phy >= 5) new_phy = 0;
    uint8_t  sf    = 0;
    uint32_t bw_hz = 0;
    uint8_t  cr    = 0;
    if (!resolvePhyProfile(new_phy, sf, bw_hz, cr)) {
        ble::queueAdminError("radio_reinit_phy_failed");
        s_radio_failed = true;
        return;
    }

    float   freq_mhz = config::ConfigManager::loraFrequencyMhz();
    if (freq_mhz < 100.0f) freq_mhz = (float)LORA_FREQ_EU868;
    uint8_t tx_power = config::ConfigManager::loraTxPowerDbm();

    int16_t result = RADIOLIB_ERR_UNKNOWN;

    if (s_radio_type == RadioType::SX1262) {
        result = s_radio_sx1262.begin(
            freq_mhz, bw_hz / 1000.0f, sf, cr,
            LORA_SYNC_WORD, tx_power, LORA_PREAMBLE_LENGTH,
            AIRTAK_SX1262_TCXO_VOLTAGE);
        if (result == RADIOLIB_ERR_NONE) {
            s_radio_sx1262.setCRC(true);
            s_radio_sx1262.explicitHeader();
            s_radio_sx1262.setDio2AsRfSwitch(true);
        }
    }
#if defined(AIRTAK_PIN_SX1276_DIO0)
    else if (s_radio_type == RadioType::SX1276) {
        result = s_radio_sx1276.begin(
            freq_mhz, bw_hz / 1000.0f, sf, cr,
            LORA_SYNC_WORD, tx_power, LORA_PREAMBLE_LENGTH);
    }
#endif

    if (result != RADIOLIB_ERR_NONE) {
        ble::queueAdminError("radio_reinit_begin_failed");
        s_radio_failed = true;
        return;
    }

    s_radio_failed = false;
    s_active_radio->setPacketReceivedAction(on_packet_received);
    s_active_radio->setPacketSentAction(on_packet_sent);
    s_active_radio->startReceive();
}

// ── Radio::pause() / Radio::resume() ─────────────────────────────────────────
void Radio::pause() {
    if (s_event_group) xEventGroupSetBits(s_event_group, RADIO_EVENT_PAUSE);
}

void Radio::resume() {
    if (s_event_group) xEventGroupSetBits(s_event_group, RADIO_EVENT_RESUME);
}

bool Radio::isPaused() {
    if (!s_event_group) return false;
    return (xEventGroupGetBits(s_event_group) & RADIO_EVENT_PAUSED) != 0;
}

// ── Radio::process() ─────────────────────────────────────────────────────────
void Radio::process(QueueHandle_t rx_queue, QueueHandle_t tx_queue,
                    EventGroupHandle_t event_group) {
    s_event_group = event_group;

    // If the radio hardware failed to initialize or reinitialize, do not
    // attempt any SX1262 operations — the module is in an unknown state
    // and register reads may hang the SPI bus indefinitely (A3 fix).
    if (s_radio_failed) {
        // Still drain the TX queue (discard silently) so callers don't stall.
        PooledPacket* pkt = nullptr;
        while (xQueueReceive(tx_queue, &pkt, 0) == pdTRUE && pkt) {
            PacketPool::free(pkt);
        }
        // Block on events with a 1-second timeout to avoid busy-spinning,
        // but still respond to PAUSE/RESUME for radio restart recovery.
        EventBits_t bits = xEventGroupWaitBits(
            event_group,
            RADIO_EVENT_PAUSE | RADIO_EVENT_RESUME,
            pdTRUE, pdFALSE, pdMS_TO_TICKS(1000));
        if (bits & RADIO_EVENT_PAUSE) {
            xEventGroupSetBits(event_group, RADIO_EVENT_PAUSED);
            xEventGroupWaitBits(event_group, RADIO_EVENT_RESUME, pdTRUE, pdTRUE, portMAX_DELAY);
            xEventGroupClearBits(event_group, RADIO_EVENT_PAUSED);
        }
        return;
    }

    EventBits_t bits = xEventGroupWaitBits(
        event_group,
        RADIO_EVENT_DIO1 | RADIO_EVENT_TX_READY | RADIO_EVENT_PAUSE,
        pdTRUE,   // clear on exit
        pdFALSE,  // wait for any bit
        pdMS_TO_TICKS(100)
    );

    // ── Radio restart (§9.4.2) ────────────────────────────────────────────────
    if (bits & RADIO_EVENT_PAUSE) {
        xEventGroupSetBits(event_group, RADIO_EVENT_PAUSED);
        // Wait for ConfigManager to complete reinit and signal RESUME.
        xEventGroupWaitBits(event_group, RADIO_EVENT_RESUME, pdTRUE, pdTRUE, portMAX_DELAY);
        xEventGroupClearBits(event_group, RADIO_EVENT_PAUSED);
        // Drain any stale flags before resuming.
        s_rx_flag = false;
        s_tx_flag = false;
        return;
    }

    // ── Received packet (DIO1 interrupt) ─────────────────────────────────────
    if (bits & RADIO_EVENT_DIO1) {
        if (s_rx_flag) {
            s_rx_flag = false;

            size_t pkt_len = s_active_radio->getPacketLength();
            if (pkt_len == 0 || pkt_len > LORA_MAX_PAYLOAD_BYTES) {
                s_active_radio->startReceive();
            } else {
                PooledPacket* pkt = PacketPool::alloc();
                if (pkt) {
                    int16_t err = s_active_radio->readData(pkt->data, (uint8_t)pkt_len);
                    if (err == RADIOLIB_ERR_NONE) {
                        pkt->length = (uint16_t)pkt_len;
                        pkt->rssi   = s_active_radio->getRSSI();
                        pkt->snr    = s_active_radio->getSNR();
                        pkt->phy_profile_index = s_active_phy;
                        s_last_rssi = pkt->rssi;
                        s_last_snr  = pkt->snr;
                        s_packets_rx++;
                        if (xQueueSendToBack(rx_queue, &pkt, 0) != pdTRUE) {
                            PacketPool::free(pkt);
                        }
                    } else {
                        PacketPool::free(pkt);
                    }
                }
                s_active_radio->startReceive();
            }
        } else {
            s_active_radio->startReceive();
        }
    }

    // ── TX queue has a packet ready ───────────────────────────────────────────
    if (bits & RADIO_EVENT_TX_READY) {
        PooledPacket* pkt = nullptr;
        if (xQueueReceive(tx_queue, &pkt, 0) == pdTRUE && pkt) {
            // transmit() handles the RadioLib call and frees the pooled packet.
            transmit(pkt->data, (uint8_t)pkt->length, pkt->phy_profile_index);
            PacketPool::free(pkt);
        }
    }
}

// ── Radio::transmit() ────────────────────────────────────────────────────────
bool Radio::transmit(const uint8_t* data, uint8_t length, uint8_t phy_profile_idx) {
    // Phase 7 multi-PHY: if the requested profile differs from the active one,
    // reconfigure the radio. This is only expected during Phase 7 audio bursts.
    if (phy_profile_idx != s_active_phy) {
        uint8_t  idx   = phy_profile_idx;
        uint8_t  sf    = 0;
        uint32_t bw_hz = 0;
        uint8_t  cr    = 0;
        if (!resolvePhyProfile(idx, sf, bw_hz, cr)) return false;

        int16_t err = RADIOLIB_ERR_NONE;
        if (s_radio_type == RadioType::SX1262) {
            err = s_radio_sx1262.setBandwidth(bw_hz / 1000.0f);
            if (err == RADIOLIB_ERR_NONE)
                err = s_radio_sx1262.setSpreadingFactor(sf);
            if (err == RADIOLIB_ERR_NONE)
                err = s_radio_sx1262.setCodingRate(cr);
        }
#if defined(AIRTAK_PIN_SX1276_DIO0)
        else if (s_radio_type == RadioType::SX1276) {
            err = s_radio_sx1276.setBandwidth(bw_hz / 1000.0f);
            if (err == RADIOLIB_ERR_NONE)
                err = s_radio_sx1276.setSpreadingFactor(sf);
            if (err == RADIOLIB_ERR_NONE)
                err = s_radio_sx1276.setCodingRate(cr);
        }
#endif
        if (err != RADIOLIB_ERR_NONE) return false;
    }

    uint32_t tx_start_ms = millis();
    int16_t err = s_active_radio->transmit(const_cast<uint8_t*>(data), length);
    uint32_t tx_elapsed_ms = millis() - tx_start_ms;
    s_active_radio->startReceive();

    if (err == RADIOLIB_ERR_NONE) {
        s_packets_tx++;
        DcTracker::recordTx(tx_elapsed_ms, tx_start_ms);
        return true;
    }
    return false;
}

float    Radio::lastSnr()    { return s_last_snr;   }
float    Radio::lastRssi()   { return s_last_rssi;  }
uint32_t Radio::packetsRx()  { return s_packets_rx; }
uint32_t Radio::packetsTx()  { return s_packets_tx; }

} // namespace airtak
