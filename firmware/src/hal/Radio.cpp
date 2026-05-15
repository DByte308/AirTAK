// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Radio.cpp — SX1262 radio HAL implementation
// Planning: §8.1, §9.2 | Roadmap: 1.2
//
// Uses RadioLib ≥ 6.4.0. All SX1262 parameters must match §8.1 global PHY parameters
// and the active LoraConfig PHY profile. PHY profiles are loaded from ConfigManager.

#include "hal/Radio.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"

// RadioLib include — provided via lib/RadioLib/ (platformio.ini dependency)
#include <RadioLib.h>

namespace mash {

// ── Static state ──────────────────────────────────────────────────────────────
static SX1262 s_radio = new Module(PIN_SX1262_NSS, PIN_SX1262_DIO1,
                                   PIN_SX1262_RESET, PIN_SX1262_BUSY);
static volatile float s_last_rssi = 0.0f;
static volatile float s_last_snr  = 0.0f;
static volatile uint32_t s_packets_rx = 0;
static volatile uint32_t s_packets_tx = 0;

// ── DIO1 interrupt handler ────────────────────────────────────────────────────
static EventGroupHandle_t s_event_group = nullptr;

static void IRAM_ATTR dio1_isr() {
    if (s_event_group) {
        BaseType_t higher_priority_woken = pdFALSE;
        xEventGroupSetBitsFromISR(s_event_group, RADIO_EVENT_DIO1, &higher_priority_woken);
        portYIELD_FROM_ISR(higher_priority_woken);
    }
}

// ── Radio::init() ─────────────────────────────────────────────────────────────
void Radio::init() {
    // TODO Phase 1.2: Load active LoraConfig from ConfigManager and apply.
    // Use global PHY parameters from §8.1:
    //   sync word 0x2B, preamble 16, explicit header, CRC on, IQ not inverted.
    // Apply PHY profile from LoraConfig.profiles[active_slot.phy_profile_index].

    // Placeholder: initialize with Profile 0 defaults (SF11/BW250/CR4/5 at EU868)
    // until ConfigManager is available (Phase 1.6).
    int16_t result = s_radio.begin(
        LORA_FREQ_EU868,                     // frequency MHz
        defaults::PHY_PROFILES[0].bandwidth_hz / 1000.0f, // kHz
        defaults::PHY_PROFILES[0].spreading_factor,
        defaults::PHY_PROFILES[0].coding_rate,
        LORA_SYNC_WORD,
        defaults::LORA_TX_POWER_DBM,
        LORA_PREAMBLE_LENGTH
    );

    if (result != RADIOLIB_ERR_NONE) {
        // TODO: log error and retry; do not block indefinitely
    }

    // Configure TXEN/RXEN for T-Echo Plus RF switch
    s_radio.setRfSwitchPins(PIN_SX1262_RXEN, PIN_SX1262_TXEN);

    // Attach DIO1 interrupt
    pinMode(PIN_SX1262_DIO1, INPUT);
    attachInterrupt(digitalPinToInterrupt(PIN_SX1262_DIO1), dio1_isr, RISING);
}

// ── Radio::reinit() ───────────────────────────────────────────────────────────
void Radio::reinit() {
    // TODO Phase 2.8: Called by ConfigManager during radio restart procedure (§9.4.2).
    // 1. Reset SX1262 hardware
    // 2. Re-apply updated LoraConfig parameters
    // 3. Reattach DIO1 interrupt
    // Target: ≤ 300 ms total.
}

// ── Radio::process() ─────────────────────────────────────────────────────────
void Radio::process(QueueHandle_t rx_queue, QueueHandle_t tx_queue, EventGroupHandle_t event_group) {
    s_event_group = event_group;

    EventBits_t bits = xEventGroupWaitBits(
        event_group,
        RADIO_EVENT_DIO1 | RADIO_EVENT_TX_READY | RADIO_EVENT_PAUSE,
        pdTRUE,   // clear on exit
        pdFALSE,  // wait for any bit
        pdMS_TO_TICKS(100)
    );

    if (bits & RADIO_EVENT_PAUSE) {
        // Radio restart requested by ConfigManager (§9.4.2).
        xEventGroupSetBits(event_group, RADIO_EVENT_PAUSED);
        xEventGroupWaitBits(event_group, RADIO_EVENT_RESUME, pdTRUE, pdTRUE, portMAX_DELAY);
        return;
    }

    if (bits & RADIO_EVENT_DIO1) {
        // TODO Phase 1.2: Read received packet from SX1262, populate RadioPacket, push to rx_queue.
        s_packets_rx++;
    }

    if (bits & RADIO_EVENT_TX_READY) {
        // TODO Phase 1.2: Pull packet from tx_queue and transmit.
        s_packets_tx++;
    }
}

// ── Radio::transmit() ────────────────────────────────────────────────────────
bool Radio::transmit(const uint8_t* data, uint8_t length, uint8_t phy_profile_idx) {
    // TODO Phase 1.2: Transmit the packet using RadioLib.
    // Switch PHY profile if phy_profile_idx != current profile (multi-PHY, Phase 7).
    (void)data; (void)length; (void)phy_profile_idx;
    return false;
}

float Radio::lastSnr()     { return s_last_snr;     }
float Radio::lastRssi()    { return s_last_rssi;    }
uint32_t Radio::packetsRx() { return s_packets_rx; }
uint32_t Radio::packetsTx() { return s_packets_tx; }

} // namespace mash
