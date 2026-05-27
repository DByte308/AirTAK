// AirTAK — Airsoft Team Awareness Kit
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
#include "hal/Touch.h"
#include "ble/BLEServer.h"
#include "ble/AirTAKService.h"
#include "mesh/MeshNode.h"
#include "mesh/Router.h"
#include "mesh/ChannelManager.h"
#include "mesh/PacketPool.h"
#include "mesh/DcTracker.h"
#include "game/PlayerState.h"
#include "system/RecoveryMode.h"
#include "platform/Platform.h"
#include "config.h"

#if defined(AIRTAK_PLATFORM_ESP32) && defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
#include <esp_ota_ops.h>
#endif

// ── Inter-task communication handles ─────────────────────────────────────────
// All inter-task communication uses these queues and event groups.
// No direct function calls between task contexts.

QueueHandle_t       g_radio_tx_queue     = nullptr;  // packets queued for TX
QueueHandle_t       g_radio_rx_queue     = nullptr;  // packets received from LoRa
QueueHandle_t       g_ble_tx_queue       = nullptr;  // BLE notifications to send
QueueHandle_t       g_config_change_queue = nullptr; // pending config group changes
EventGroupHandle_t  g_radio_event_group  = nullptr;  // DIO1 IRQ + restart signals
EventGroupHandle_t  g_system_event_group = nullptr;  // GPS fix, BLE connect, etc.

#ifdef AIRTAK_PHASE7_AUDIO
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
#ifdef AIRTAK_PHASE7_AUDIO
void task_audio_codec(void* params);
#endif

#ifndef LED_STATE_ON
#define LED_STATE_ON LOW
#endif
#ifndef LED_BUILTIN
#define LED_BUILTIN (-1)
#endif

#ifdef NRF52840_XXAA
static inline void wdtInit() {
    NRF_WDT->CONFIG = 3;           // SLEEP=Run(1) | HALT=Run(2)
    NRF_WDT->CRV = 32768 * 8;      // 8 seconds @ 32.768 kHz
    NRF_WDT->RREN = 0x01;          // Enable reload channel 0
    NRF_WDT->TASKS_START = 1;
}
static inline void wdtFeed() {
    NRF_WDT->RR[0] = 0x6E524635UL;
}
#else
static inline void wdtInit() {}
static inline void wdtFeed() {}
#endif

static inline void ledOn() {
#ifdef LED_BUILTIN
    if (LED_BUILTIN >= 0) { pinMode(LED_BUILTIN, OUTPUT); digitalWrite(LED_BUILTIN, LED_STATE_ON); }
#endif
}
static inline void ledOff() {
#ifdef LED_BUILTIN
    if (LED_BUILTIN >= 0) { pinMode(LED_BUILTIN, OUTPUT); digitalWrite(LED_BUILTIN, !LED_STATE_ON); }
#endif
}

// ── setup() ──────────────────────────────────────────────────────────────────
void setup() {
    ledOn();

    wdtInit();

    Serial.begin(115200);
    delay(1000);
#ifdef AIRTAK_FLASH_TEST
    Serial.println("FLASH TEST boot");
#else
    Serial.println("AirTAK boot");
#endif
    Serial.flush();

    wdtFeed();

    if (airtak::RecoveryMode::requestedAtBoot()) {
        airtak::RecoveryMode::run();
    }

    wdtFeed();
    airtak::platform::initBoard();
    delay(50);

    airtak::PacketPool::init();

    wdtFeed();
    ledOff();
#ifdef AIRTAK_FLASH_TEST
    Serial.println("FLASH TEST: FlashStore init...");
#else
    Serial.println("AirTAK: FlashStore init...");
#endif
    Serial.flush();
    if (!airtak::config::FlashStore::init()) {
#ifdef AIRTAK_FLASH_TEST
        Serial.println("FLASH TEST: FlashStore init failed; using volatile defaults");
#else
        Serial.println("AirTAK: FlashStore init failed; using volatile defaults");
#endif
    } else {
#ifdef AIRTAK_FLASH_TEST
        Serial.println("FLASH TEST: FlashStore OK");
#else
        Serial.println("AirTAK: FlashStore OK");
#endif
#if defined(AIRTAK_PLATFORM_ESP32) && defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
        esp_ota_mark_app_valid_cancel_rollback();
#endif
    }
    Serial.flush();
    ledOn();
    wdtFeed();
    airtak::config::ConfigManager::init();
    airtak::config::ConfigManager::registerCallback([](airtak::ConfigGroup group) {
        airtak::Display::markDirty(airtak::ui::DISPLAY_DIRTY_ALL);
        if (group == airtak::ConfigGroup::POSITION) {
            uint8_t src = airtak::config::ConfigManager::positionGpsSource();
            airtak::GPS::setExternalSource(src == 1);
        } else if (group == airtak::ConfigGroup::POWER) {
            airtak::Display::setBrightness(airtak::config::ConfigManager::powerBrightnessPct());
        }
    });
    airtak::ChannelManager::init();
    airtak::PlayerState::init();
    airtak::Button::init();

    // Create inter-task communication primitives.
    g_radio_tx_queue      = xQueueCreate(8,  sizeof(void*));
    g_radio_rx_queue      = xQueueCreate(16, sizeof(void*));
    g_ble_tx_queue        = xQueueCreate(8,  sizeof(void*));
    g_config_change_queue = xQueueCreate(16, sizeof(void*));
    g_radio_event_group   = xEventGroupCreate();
    g_system_event_group  = xEventGroupCreate();

#ifdef AIRTAK_PHASE7_AUDIO
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
    configASSERT(xTaskCreate(task_radio_rxtx,      "radio",    4096, nullptr, 4, nullptr) == pdPASS);
    configASSERT(xTaskCreate(task_gps_parser,      "gps",      4096, nullptr, 3, nullptr) == pdPASS);
    configASSERT(xTaskCreate(task_ble_server,      "ble",      4096, nullptr, 3, nullptr) == pdPASS);
    configASSERT(xTaskCreate(task_config_apply,    "config",   2048, nullptr, 2, nullptr) == pdPASS);
    configASSERT(xTaskCreate(task_display_refresh, "display",  2048, nullptr, 1, nullptr) == pdPASS);
    configASSERT(xTaskCreate(task_battery_monitor, "battery",  2048, nullptr, 0, nullptr) == pdPASS);

#ifdef AIRTAK_PHASE7_AUDIO
    configASSERT(xTaskCreate(task_audio_codec, "audio", 3072, nullptr, 2, nullptr) == pdPASS);
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
    airtak::Radio::init();
    airtak::MeshNode::init();
    airtak::Router::init();
    airtak::DcTracker::init();
    {
        const auto* s = airtak::ChannelManager::primarySlot();
        Serial.printf("[INIT] %s hash=0x%02X psk=%u\n",
            s?s->name:"?", s?s->channel_hash:0, s?(unsigned)s->psk_len:0);
        Serial.flush();
    }
    airtak::MeshNode::broadcastNodeInfo(g_radio_tx_queue);
    for (;;) {
        airtak::Radio::process(g_radio_rx_queue, g_radio_tx_queue, g_radio_event_group);
        vTaskDelay(1);

        // Drain the RX queue and pass packets through the flood router (§8.7).
        airtak::PooledPacket* pkt = nullptr;
        while (xQueueReceive(g_radio_rx_queue, &pkt, 0) == pdTRUE && pkt) {
            airtak::Router::processIncoming(pkt->data, (uint8_t)pkt->length,
                                          pkt->rssi, pkt->snr,
                                          g_radio_tx_queue);
            airtak::PacketPool::free(pkt);
        }

        // Tick relay and retry queues.
        airtak::Router::tickRelayQueue(g_radio_tx_queue);
        airtak::Router::tickRetryQueue(g_radio_tx_queue);

        airtak::MeshNode::tick(g_radio_tx_queue);

        {
            static uint32_t ls = 0;
            if (millis() - ls > 10000) {
                ls = millis();
                Serial.printf("[STATS] tx=%lu rx=%lu rssi=%.1f snr=%.1f dio=%lu\n",
                    airtak::Radio::packetsTx(), airtak::Radio::packetsRx(),
                    airtak::Radio::lastRssi(), airtak::Radio::lastSnr(),
                    airtak::Radio::dio1IsrCount());
                Serial.flush();
            }
        }
    }
}

void task_gps_parser(void* params) {
    // Phase 1.3: L76K GPS bring-up | Phase 2.4: POSITION_APP TX
    airtak::GPS::init();
    for (;;) {
        airtak::GPS::process(g_system_event_group);
        // Yield for 100 ms between polling iterations to prevent starvation of
        // lower-priority tasks (config apply, display, battery monitor) and
        // competition with same-priority BLE task (§9.3).
        // GPS NMEA sentences arrive at 1 Hz (1000 ms), so 100 ms polling is sufficient.
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void task_ble_server(void* params) {
    // Phase 1.5: BLE GATT server skeleton
    airtak::ble::AirTAKService::setRadioTxQueue(g_radio_tx_queue);
    airtak::ble::BLEServer::init(g_radio_rx_queue);
    for (;;) {
        airtak::ble::BLEServer::process(g_ble_tx_queue, g_config_change_queue);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

void task_config_apply(void* params) {
    // Phase 2.8: hot-reload config — driven by g_config_change_queue.
    // ConfigManager::applyGroup() sets s_radio_restart_pending when a LORA or
    // CHANNELS commit arrives on the BLE task stack. The BLE task posts a
    // sentinel (nullptr) to g_config_change_queue instead of calling
    // radioRestartProcedure() directly, keeping the BLE event loop unblocked.
    // This task drains the queue and performs the ≤300 ms radio restart safely
    // from its own stack context (priority LOW/2, stack 2 KB).
    for (;;) {
        void* sentinel = nullptr;
        // Block indefinitely until a restart request arrives.
        if (xQueueReceive(g_config_change_queue, &sentinel, portMAX_DELAY) == pdTRUE) {
            if (airtak::config::ConfigManager::isRadioRestartPending()) {
                airtak::config::ConfigManager::radioRestartProcedure();
                airtak::ble::AirTAKService::completePendingConfigRestart(true);
            }
        }
    }
}

void task_display_refresh(void* params) {
    airtak::Display::init();
    airtak::Display::setBrightness(airtak::config::ConfigManager::powerBrightnessPct());
    airtak::Display::refresh(g_system_event_group);
    uint32_t last_refresh_check_ms = millis();
    uint32_t last_interaction_ms    = millis();
    uint32_t last_player_tick_ms   = 0;
    bool     screen_changed        = false;
    bool     display_asleep        = false;
    for (;;) {
        uint32_t now = millis();

        airtak::ButtonGesture gesture = airtak::Button::poll();
        bool interaction = (gesture != airtak::ButtonGesture::NONE);

        airtak::TouchGesture touch = airtak::Touch::poll();
        if (touch != airtak::TouchGesture::NONE) interaction = true;

        if (interaction) {
            last_interaction_ms = now;
            if (display_asleep) {
                airtak::Display::wake();
                display_asleep = false;
            }
        }

        // Handle gestures
        if (gesture == airtak::ButtonGesture::SHORT_PRESS) {
            if (airtak::PlayerState::isCancellable()) {
                airtak::PlayerState::cancelHit(g_radio_tx_queue);
            } else {
                airtak::Display::nextScreen();
                screen_changed = true;
            }
        } else if (gesture == airtak::ButtonGesture::LONG_PRESS) {
            airtak::PlayerState::toggleHit(g_radio_tx_queue);
        }

        if (touch == airtak::TouchGesture::SWIPE_LEFT) {
            airtak::Display::previousScreen();
            screen_changed = true;
        } else if (touch == airtak::TouchGesture::SWIPE_RIGHT) {
            airtak::Display::nextScreen();
            screen_changed = true;
        }

        // Display sleep timeout
        uint32_t off_secs = airtak::config::ConfigManager::powerDisplayOffSecs();
        if (!display_asleep && off_secs > 0 && (now - last_interaction_ms) >= off_secs * 1000) {
            airtak::Display::sleep();
            display_asleep = true;
        }

        if (display_asleep) {
            vTaskDelay(pdMS_TO_TICKS(250));
            continue;
        }

        if (screen_changed) {
            airtak::Display::refresh(g_system_event_group);
            last_refresh_check_ms = now;
            screen_changed = false;
        } else if ((now - last_refresh_check_ms) >= 5000) {
            airtak::Display::refresh(g_system_event_group);
            last_refresh_check_ms = now;
        }

        if ((now - last_player_tick_ms) >= 1000) {
            airtak::PlayerState::tick(g_radio_tx_queue);
            last_player_tick_ms = now;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

void task_battery_monitor(void* params) {
    // Phase 1.7: battery ADC task
    airtak::Battery::init();
    for (;;) {
        airtak::Battery::sample(g_system_event_group);
        vTaskDelay(pdMS_TO_TICKS(60000)); // sample every 60 s
    }
}

#ifdef AIRTAK_PHASE7_AUDIO
void task_audio_codec(void* params) {
    // Phase 7.3: firmware audio pipeline (Codec2 encode/decode)
    for (;;) {
        // TODO: Phase 7.3 — process BLE audio frames from g_audio_rx_queue
        vTaskDelay(pdMS_TO_TICKS(20)); // 20 ms Codec2 frame interval
    }
}
#endif
