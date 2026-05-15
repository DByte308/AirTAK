// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Radio.cpp — SX1262 radio HAL implementation
// Planning: §8.1, §9.2 | Roadmap: 1.2
//
// Uses RadioLib ≥ 6.4.0. All SX1262 parameters must match §8.1 global PHY parameters
// and the active LoraConfig PHY profile. PHY profiles are loaded from ConfigManager.

#include "hal/Radio.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "mesh/ChannelManager.h"
#include "mesh/PacketPool.h"
#include "ble/BLEQueue.h"
#include "config.h"

// RadioLib include — provided via lib/RadioLib/ (platformio.ini dependency)
#include <RadioLib.h>
#include <SPI.h>
#include <cstring>

#ifndef IRAM_ATTR
#define IRAM_ATTR
#endif

namespace airtak {

// ── Static state ──────────────────────────────────────────────────────────────
static SX1262 s_radio = new Module(AIRTAK_PIN_SX1262_NSS, AIRTAK_PIN_SX1262_DIO1,
                                   AIRTAK_PIN_SX1262_RESET, AIRTAK_PIN_SX1262_BUSY);
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
    // Phase 1.2: Configure SX1262 with global PHY parameters from §8.1.
    // Default to profile 0 (Long/Mesh); overridden from config if available.

    uint8_t  initial_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (initial_phy >= 5) initial_phy = 0;

    uint8_t  sf    = 0;
    uint32_t bw_hz = 0;
    uint8_t  cr    = 0;
    if (!resolvePhyProfile(initial_phy, sf, bw_hz, cr)) {
        // Even the fallback failed — radio is non-functional.
        // The display will show an error state.
        return;
    }

    float freq_mhz = config::ConfigManager::loraFrequencyMhz();
    if (freq_mhz < 100.0f) freq_mhz = (float)LORA_FREQ_EU868;

    // RadioLib begin(freq, bw, sf, cr, syncWord, txPower, preamble).
    // Use validated freq_mhz with fallback — NOT a fresh ConfigManager call
    // that could return 0 for a corrupt/missing LORA config.
    int16_t result = s_radio.begin(
        freq_mhz,
        bw_hz / 1000.0f,
        sf,
        cr,
        LORA_SYNC_WORD,
        config::ConfigManager::loraTxPowerDbm(),
        LORA_PREAMBLE_LENGTH,
        AIRTAK_SX1262_TCXO_VOLTAGE
    );

    if (result != RADIOLIB_ERR_NONE) {
        // Log and continue — device is non-functional without radio, but do not
        // assert/reboot here; the display will show an error state.
        return;
    }

    // Global PHY parameters (§8.1) — same across all profiles.
    s_radio.setCRC(true);         // CRC enabled
    s_radio.explicitHeader();

    s_radio.setDio2AsRfSwitch(true);

    // Register interrupt callbacks (RadioLib 6.x API).
    s_radio.setPacketReceivedAction(on_packet_received);
    s_radio.setPacketSentAction(on_packet_sent);

    // Enter continuous receive mode.
    s_radio.startReceive();
}

// ── Radio::reinit() ───────────────────────────────────────────────────────────
void Radio::reinit() {
    // Phase 2.8: Called by ConfigManager::radioRestartProcedure() (§9.4.2).
    // Re-apply LoRa parameters from the committed ConfigManager buffers.
    // Reads frequency and TX power from the LORA config group; reads the active
    // PHY profile index from the PRIMARY channel slot via ChannelManager so that
    // a CHANNELS config commit that changes phy_profile_index takes effect here.
    // Target: ≤ 300 ms. Does not restart MCU. Does not disconnect BLE.

    // Reset SX1262 hardware via RESET pin.
    digitalWrite(AIRTAK_PIN_SX1262_RESET, LOW);
    delay(2);
    digitalWrite(AIRTAK_PIN_SX1262_RESET, HIGH);
    delay(10);

    // Resolve PHY profile: prefer the PRIMARY channel slot's committed value.
    // Falls back to profile 0 Long/Mesh via resolvePhyProfile if unusable.
    uint8_t  new_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (new_phy >= 5) new_phy = 0;
    uint8_t  sf    = 0;
    uint32_t bw_hz = 0;
    uint8_t  cr    = 0;
    if (!resolvePhyProfile(new_phy, sf, bw_hz, cr)) {
        ble::queueAdminError("radio_reinit_phy_failed");
        return;
    }

    // Read live parameters from ConfigManager (committed config buffers).
    float   freq_mhz = config::ConfigManager::loraFrequencyMhz();
    if (freq_mhz < 100.0f) freq_mhz = (float)LORA_FREQ_EU868;
    uint8_t tx_power = config::ConfigManager::loraTxPowerDbm();

    int16_t result = s_radio.begin(
        freq_mhz,
        bw_hz / 1000.0f,
        sf,
        cr,
        LORA_SYNC_WORD,
        tx_power,
        LORA_PREAMBLE_LENGTH,
        AIRTAK_SX1262_TCXO_VOLTAGE
    );

    if (result != RADIOLIB_ERR_NONE) {
        // Radio hardware is unresponsive after reset — notify BLE client.
        // The firmware will continue operating without radio; the display
        // and BLE remain functional for diagnostics.
        ble::queueAdminError("radio_reinit_begin_failed");
        return;
    }

    s_radio.setCRC(true);
    s_radio.explicitHeader();
    s_radio.setDio2AsRfSwitch(true);
    s_radio.setPacketReceivedAction(on_packet_received);
    s_radio.setPacketSentAction(on_packet_sent);
    s_radio.startReceive();
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

            // Determine received packet length.
            size_t pkt_len = s_radio.getPacketLength();
            if (pkt_len == 0 || pkt_len > LORA_MAX_PAYLOAD_BYTES) {
                // Malformed; restart receive and discard.
                s_radio.startReceive();
            } else {
                PooledPacket* pkt = PacketPool::alloc();
                if (pkt) {
                    int16_t err = s_radio.readData(pkt->data, (uint8_t)pkt_len);
                    if (err == RADIOLIB_ERR_NONE) {
                        pkt->length = (uint16_t)pkt_len;
                        pkt->rssi   = s_radio.getRSSI();
                        pkt->snr    = s_radio.getSNR();
                        pkt->phy_profile_index = s_active_phy;
                        s_last_rssi = pkt->rssi;
                        s_last_snr  = pkt->snr;
                        s_packets_rx++;

                        // Enqueue for Router processing. Drop if queue full.
                        if (xQueueSendToBack(rx_queue, &pkt, 0) != pdTRUE) {
                            PacketPool::free(pkt);
                        }
                    } else {
                        PacketPool::free(pkt);
                    }
                }
                // Restart receive regardless of allocation success.
                s_radio.startReceive();
            }
        } else {
            // Spurious interrupt (TX done DIO1 edge during receive window).
            s_radio.startReceive();
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
    // reconfigure the SX1262. This is only expected during Phase 7 audio bursts.
    if (phy_profile_idx != s_active_phy) {
        uint8_t  idx   = phy_profile_idx;
        uint8_t  sf    = 0;
        uint32_t bw_hz = 0;
        uint8_t  cr    = 0;
        if (!resolvePhyProfile(idx, sf, bw_hz, cr)) return false;

        // Apply the resolved SF/BW/CR to the live SX1262 (frequency unchanged).
        int16_t err = s_radio.setBandwidth(bw_hz / 1000.0f);
        if (err != RADIOLIB_ERR_NONE) return false;
        err = s_radio.setSpreadingFactor(sf);
        if (err != RADIOLIB_ERR_NONE) return false;
        err = s_radio.setCodingRate(cr);
        if (err != RADIOLIB_ERR_NONE) return false;
    }

    // Synchronous transmit.  const_cast is safe here — RadioLib's SX1262::transmit
    // does not modify the buffer (it DMA-copies to the radio FIFO).
    int16_t err = s_radio.transmit(const_cast<uint8_t*>(data), length);

    // Return to continuous receive immediately after TX.
    s_radio.startReceive();

    if (err == RADIOLIB_ERR_NONE) {
        s_packets_tx++;
        return true;
    }
    return false;
}

float    Radio::lastSnr()    { return s_last_snr;   }
float    Radio::lastRssi()   { return s_last_rssi;  }
uint32_t Radio::packetsRx()  { return s_packets_rx; }
uint32_t Radio::packetsTx()  { return s_packets_tx; }

} // namespace airtak
