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

// ── applyPhyProfile() ─────────────────────────────────────────────────────────
// Apply PHY profile parameters to the SX1262. Returns true on success.
static bool applyPhyProfile(uint8_t profile_idx) {
    if (profile_idx >= 5) profile_idx = 0;
    uint8_t sf = defaults::PHY_PROFILES[profile_idx].spreading_factor;
    uint32_t bw_hz = defaults::PHY_PROFILES[profile_idx].bandwidth_hz;
    uint8_t cr = defaults::PHY_PROFILES[profile_idx].coding_rate;
    config::ConfigManager::loraPhyProfile(profile_idx, sf, bw_hz, cr);

    // Determine frequency from LoraConfig region.
    // Phase 2.8: read from ConfigManager; default to EU868.
    float freq_mhz = config::ConfigManager::loraFrequencyMhz();

    float bw_khz = bw_hz / 1000.0f;
    int16_t radio_cr = cr;   // RadioLib uses 5=4/5, 6=4/6, 7=4/7, 8=4/8

    int16_t err = s_radio.setFrequency(freq_mhz);
    if (err != RADIOLIB_ERR_NONE) return false;

    err = s_radio.setBandwidth(bw_khz);
    if (err != RADIOLIB_ERR_NONE) return false;

    err = s_radio.setSpreadingFactor(sf);
    if (err != RADIOLIB_ERR_NONE) return false;

    err = s_radio.setCodingRate(radio_cr);
    if (err != RADIOLIB_ERR_NONE) return false;

    s_active_phy = profile_idx;
    return true;
}

// ── Radio::init() ─────────────────────────────────────────────────────────────
void Radio::init() {
    // Phase 1.2: Configure SX1262 with global PHY parameters from §8.1.
    // Profile 0 (SF11/BW250/CR4/5) is the default; overridden by the active
    // channel slot's phy_profile_index once ConfigManager is fully available.

    // SX1262 uses a dedicated SPI bus on the T-Echo Plus (same bus as radio).
    // The Module constructor already references the correct pins.

    uint8_t initial_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (initial_phy >= 5) initial_phy = 0;
    uint8_t sf = defaults::PHY_PROFILES[initial_phy].spreading_factor;
    uint32_t bw_hz = defaults::PHY_PROFILES[initial_phy].bandwidth_hz;
    uint8_t cr = defaults::PHY_PROFILES[initial_phy].coding_rate;
    config::ConfigManager::loraPhyProfile(initial_phy, sf, bw_hz, cr);

    // RadioLib begin(freq, bw, sf, cr, syncWord, txPower, preamble).
    int16_t result = s_radio.begin(
        config::ConfigManager::loraFrequencyMhz(),
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

    s_active_phy = initial_phy;

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

    // Read live parameters from ConfigManager (committed config buffers).
    float   freq_mhz = config::ConfigManager::loraFrequencyMhz();
    uint8_t tx_power = config::ConfigManager::loraTxPowerDbm();

    // PHY profile: prefer the PRIMARY channel slot's committed value.
    // Note: ChannelManager::rekey() is called after reinit() in radioRestartProcedure();
    // for CHANNELS-driven PHY changes the slot data is current because ConfigManager
    // already wrote s_buffers[CHANNELS] before triggering the restart, and
    // primarySlotPhyProfileIndex() parses directly from that buffer.
    uint8_t new_phy = config::ConfigManager::primarySlotPhyProfileIndex();
    if (new_phy >= 5) new_phy = 0;
    s_active_phy = new_phy;
    uint8_t sf = defaults::PHY_PROFILES[s_active_phy].spreading_factor;
    uint32_t bw_hz = defaults::PHY_PROFILES[s_active_phy].bandwidth_hz;
    uint8_t cr = defaults::PHY_PROFILES[s_active_phy].coding_rate;
    config::ConfigManager::loraPhyProfile(s_active_phy, sf, bw_hz, cr);

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

    if (result != RADIOLIB_ERR_NONE) return;

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
        if (!applyPhyProfile(phy_profile_idx)) return false;
    }

    // Synchronous transmit. RadioLib will drive TXEN/RXEN via setRfSwitchPins.
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
