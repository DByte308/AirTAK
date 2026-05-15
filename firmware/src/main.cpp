// MASH — Mesh Airsoft Situational Hub
// firmware/src/main.cpp — Entry point; FreeRTOS scheduler initialization
// Planning: §9.2, §9.3 | Roadmap: 1.7
//
// Creates all FreeRTOS tasks with the priorities and stack sizes defined in §9.3.
// No shared mutable state is accessed outside a mutex; inter-task communication
// uses FreeRTOS queues and event groups exclusively.
//
// Task priorities (§9.3):
//   Radio RX/TX   — HIGH (4)    4 KB  SX1262 DIO1 interrupt
//   GPS Parser    — NORMAL (3)  2 KB  UART data available
//   BLE Server    — NORMAL (3)  4 KB  BLE stack events
//   Config Apply  — LOW (2)     2 KB  Config change queued
//   Audio Codec   — LOW (2)     3 KB  BLE audio frame or TX queue (Phase 7)
//   Display Refresh — LOW (1)   2 KB  5-minute timer or content change
//   Battery Monitor — IDLE (0)  1 KB  60-second timer

#include <Arduino.h>
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <event_groups.h>

#include "config/Defaults.h"
#include "config/FlashStore.h"
#include "config/ConfigManager.h"
#include "hal/Radio.h"
#include "hal/GPS.h"
#include "hal/Display.h"
#include "hal/Battery.h"
#include "hal/Button.h"
#include "ble/BLEServer.h"
#include "mesh/MeshNode.h"
#include "mesh/Router.h"
#include "mesh/ChannelManager.h"
#include "mesh/PacketPool.h"
#include "game/GameState.h"
#include "game/PlayerState.h"

// ── Inter-task communication handles ─────────────────────────────────────────
// All inter-task communication uses these queues and event groups.
// No direct function calls between task contexts.

QueueHandle_t       g_radio_tx_queue     = nullptr;  // packets queued for TX
QueueHandle_t       g_radio_rx_queue     = nullptr;  // packets received from LoRa
QueueHandle_t       g_ble_tx_queue       = nullptr;  // BLE notifications to send
QueueHandle_t       g_config_change_queue = nullptr; // pending config group changes
EventGroupHandle_t  g_radio_event_group  = nullptr;  // DIO1 IRQ + restart signals
EventGroupHandle_t  g_system_event_group = nullptr;  // GPS fix, BLE connect, etc.

#ifdef MASH_PHASE7_AUDIO
QueueHandle_t       g_audio_rx_queue     = nullptr;  // decoded audio frames from BLE
QueueHandle_t       g_audio_tx_queue     = nullptr;  // audio frames to encode and TX
#endif

// ── Task function forward declarations ───────────────────────────────────────
void task_radio_rxtx(void* params);
void task_gps_parser(void* params);
void task_ble_server(void* params);
void task_config_apply(void* params);
void task_display_refresh(void* params);
void task_battery_monitor(void* params);
#ifdef MASH_PHASE7_AUDIO
void task_audio_codec(void* params);
#endif

// ── setup() ──────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);

    // Initialize LittleFS config store and load all config groups from flash.
    // On first boot (no config files), defaults from Defaults.h are used.
    mash::config::FlashStore::init();
    mash::config::ConfigManager::init();

    // Create inter-task communication primitives.
    g_radio_tx_queue      = xQueueCreate(8,  sizeof(void*));
    g_radio_rx_queue      = xQueueCreate(16, sizeof(void*));
    g_ble_tx_queue        = xQueueCreate(8,  sizeof(void*));
    g_config_change_queue = xQueueCreate(8,  sizeof(void*));
    g_radio_event_group   = xEventGroupCreate();
    g_system_event_group  = xEventGroupCreate();

#ifdef MASH_PHASE7_AUDIO
    g_audio_rx_queue = xQueueCreate(4, sizeof(void*));
    g_audio_tx_queue = xQueueCreate(4, sizeof(void*));
#endif

    configASSERT(g_radio_tx_queue);
    configASSERT(g_radio_rx_queue);
    configASSERT(g_ble_tx_queue);
    configASSERT(g_config_change_queue);
    configASSERT(g_radio_event_group);
    configASSERT(g_system_event_group);

    // Create all FreeRTOS tasks. Tasks are stubs in Phase 1; filled in subsequent phases.
    xTaskCreate(task_radio_rxtx,      "radio",    4096, nullptr, 4, nullptr);
    xTaskCreate(task_gps_parser,      "gps",      2048, nullptr, 3, nullptr);
    xTaskCreate(task_ble_server,      "ble",      4096, nullptr, 3, nullptr);
    xTaskCreate(task_config_apply,    "config",   2048, nullptr, 2, nullptr);
    xTaskCreate(task_display_refresh, "display",  2048, nullptr, 1, nullptr);
    xTaskCreate(task_battery_monitor, "battery",  1024, nullptr, 0, nullptr);

#ifdef MASH_PHASE7_AUDIO
    xTaskCreate(task_audio_codec, "audio", 3072, nullptr, 2, nullptr);
#endif

    // FreeRTOS scheduler starts at end of setup(); loop() becomes the idle task.
}

void loop() {
    // Intentionally empty. All work is done in FreeRTOS tasks.
    vTaskDelay(portMAX_DELAY);
}

// ── Task stubs (filled in subsequent phases) ──────────────────────────────────

void task_radio_rxtx(void* params) {
    // Phase 1.2: SX1262 bring-up | Phase 2.5: flood router
    // Triggered by SX1262 DIO1 interrupt via g_radio_event_group.
    mash::Radio::init();
    mash::Router::init();
    mash::MeshNode::init();
    mash::MeshNode::broadcastNodeInfo(g_radio_tx_queue);
    for (;;) {
        mash::Radio::process(g_radio_rx_queue, g_radio_tx_queue, g_radio_event_group);

        // Drain the RX queue and pass packets through the flood router (§8.7).
        mash::PooledPacket* pkt = nullptr;
        while (xQueueReceive(g_radio_rx_queue, &pkt, 0) == pdTRUE && pkt) {
            mash::Router::processIncoming(pkt->data, (uint8_t)pkt->length,
                                          pkt->rssi, pkt->snr,
                                          g_radio_tx_queue);
            mash::PacketPool::free(pkt);
        }

        // Tick relay and retry queues.
        mash::Router::tickRelayQueue(g_radio_tx_queue);
        mash::Router::tickRetryQueue(g_radio_tx_queue);

        // Smart beaconing and NODEINFO schedule.
        mash::MeshNode::tick(g_radio_tx_queue);
    }
}

void task_gps_parser(void* params) {
    // Phase 1.3: L76K GPS bring-up | Phase 2.4: POSITION_APP TX
    mash::GPS::init();
    for (;;) {
        mash::GPS::process(g_system_event_group);
    }
}

void task_ble_server(void* params) {
    // Phase 1.5: BLE GATT server skeleton
    mash::ble::BLEServer::init(g_radio_rx_queue);
    for (;;) {
        mash::ble::BLEServer::process(g_ble_tx_queue, g_config_change_queue);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

void task_config_apply(void* params) {
    // Phase 2.8: hot-reload config — driven by config_change_queue posted by MashService.
    for (;;) {
        // Config apply is handled inline in MashService::onConfigWrite().
        // This task is reserved for future batch/async config validation logic.
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void task_display_refresh(void* params) {
    // Phase 1.4: E-Ink display bring-up | Phase 9.6: refresh management
    mash::Display::init();
    for (;;) {
        mash::Display::refresh(g_system_event_group);
        vTaskDelay(pdMS_TO_TICKS(5000)); // check every 5 s; actual refresh gated by dirty state
    }
}

void task_battery_monitor(void* params) {
    // Phase 1.7: battery ADC task
    for (;;) {
        mash::Battery::sample(g_system_event_group);
        vTaskDelay(pdMS_TO_TICKS(60000)); // sample every 60 s
    }
}

#ifdef MASH_PHASE7_AUDIO
void task_audio_codec(void* params) {
    // Phase 7.3: firmware audio pipeline (Codec2 encode/decode)
    for (;;) {
        // TODO: Phase 7.3 — process BLE audio frames from g_audio_rx_queue
        vTaskDelay(pdMS_TO_TICKS(20)); // 20 ms Codec2 frame interval
    }
}
#endif
