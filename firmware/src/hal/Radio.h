// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Radio.h — SX1262 radio HAL (thin wrapper over RadioLib)
// Planning: §8.1, §9.2 | Roadmap: 1.2
//
// Provides: init(), reinit(), process(), transmit(), and radio restart support.
// The radio restart procedure (§9.4.2) is exposed here and invoked by ConfigManager
// when CHANNELS or LORA config groups change.
//
// All SX1262 register values must match Meshtastic's configuration for channel 0
// interoperability (§8.1, §2.1). Do not change global PHY parameters without a
// corresponding interop validation against stock Meshtastic devices.

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>
#include <event_groups.h>

namespace mash {

// Event bits used in g_radio_event_group
constexpr EventBits_t RADIO_EVENT_DIO1      = (1 << 0); // SX1262 DIO1 interrupt
constexpr EventBits_t RADIO_EVENT_TX_READY  = (1 << 1); // packet queued for TX
constexpr EventBits_t RADIO_EVENT_PAUSE     = (1 << 2); // radio restart: pause RX/TX
constexpr EventBits_t RADIO_EVENT_PAUSED    = (1 << 3); // radio restart: task paused
constexpr EventBits_t RADIO_EVENT_RESUME    = (1 << 4); // radio restart: resume

// Encapsulates a LoRa packet for queue transport (fixed-size pool entries).
// Filled by the Radio RX task; consumed by the mesh Router task.
struct RadioPacket {
    uint8_t  data[255];      // raw OTA bytes (§8.2 wire format)
    uint8_t  length;
    float    rssi;           // dBm
    float    snr;            // dB
    uint8_t  phy_profile_idx; // which PHY profile received this packet
};

class Radio {
public:
    // Phase 1.2: Initialize SX1262 with global PHY parameters from §8.1.
    // Must be called once from the Radio RX/TX task before any other Radio call.
    static void init();

    // Phase 2.8: Reinitialize SX1262 with new LoRa parameters after a config change.
    // Called by ConfigManager::applyPending() within the radio restart procedure (§9.4.2).
    // Target: ≤ 300 ms. Does not restart the MCU. Does not disconnect BLE.
    static void reinit();

    // Main task loop — called from task_radio_rxtx().
    // Waits for DIO1 interrupt or TX queue item; dispatches accordingly.
    static void process(QueueHandle_t rx_queue, QueueHandle_t tx_queue, EventGroupHandle_t event_group);

    // Transmit a single packet. Blocks until TX complete or timeout.
    // Returns true on success.
    static bool transmit(const uint8_t* data, uint8_t length, uint8_t phy_profile_idx);

    // SNR of the most recently received packet (used by Router for relay delay, §8.7).
    static float lastSnr();
    static float lastRssi();

    // Packet counters (reported in DeviceStatus, §10.4).
    static uint32_t packetsRx();
    static uint32_t packetsTx();
};

} // namespace mash
