// AirTAK — Airsoft Team Awareness Kit
// System Constants — Serial, Tasks, Queues, Watchdog
#pragma once
#include <cstdint>

namespace airtak {

// ── Serial ──────────────────────────────────────────────────────────────────

/// Baud rate for the primary debug/serial console interface
constexpr uint32_t SERIAL_BAUD_RATE           = 115200;

// ── Task Stacks (bytes) ─────────────────────────────────────────────────────

/// Stack depth for the Radio RX/TX task
constexpr uint16_t TASK_STACK_RADIO_RXTX      = 4096;

/// Stack depth for the GPS parsing task
constexpr uint16_t TASK_STACK_GPS             = 4096;

/// Stack depth for the BLE server task
constexpr uint16_t TASK_STACK_BLE             = 4096;

/// Stack depth for the configuration management task
constexpr uint16_t TASK_STACK_CONFIG          = 2048;

/// Stack depth for the display rendering task
constexpr uint16_t TASK_STACK_DISPLAY         = 4096;

/// Stack depth for the battery monitoring task
constexpr uint16_t TASK_STACK_BATTERY         = 2048;

/// Stack depth for the audio (Codec2) processing task
constexpr uint16_t TASK_STACK_AUDIO           = 3072;

// ── Task Priorities (higher = more urgent) ───────────────────────────────────

/// FreeRTOS priority for the Radio RX/TX task
constexpr uint8_t  TASK_PRIO_RADIO_RXTX       = 4;

/// FreeRTOS priority for the GPS parsing task
constexpr uint8_t  TASK_PRIO_GPS              = 3;

/// FreeRTOS priority for the BLE server task
constexpr uint8_t  TASK_PRIO_BLE              = 3;

/// FreeRTOS priority for the configuration management task
constexpr uint8_t  TASK_PRIO_CONFIG           = 2;

/// FreeRTOS priority for the display rendering task
constexpr uint8_t  TASK_PRIO_DISPLAY          = 1;

/// FreeRTOS priority for the battery monitoring task
constexpr uint8_t  TASK_PRIO_BATTERY          = 0;

/// FreeRTOS priority for the audio processing task
constexpr uint8_t  TASK_PRIO_AUDIO            = 2;

// ── Queue Sizes ─────────────────────────────────────────────────────────────

/// Number of entries in the radio transmit queue
constexpr uint8_t  QUEUE_SIZE_RADIO_TX        = 8;

/// Number of entries in the radio receive queue
constexpr uint8_t  QUEUE_SIZE_RADIO_RX        = 16;

/// Number of entries in the BLE transmit queue
constexpr uint8_t  QUEUE_SIZE_BLE_TX          = 8;

/// Number of entries in the configuration-change delivery queue
constexpr uint8_t  QUEUE_SIZE_CONFIG_CHANGE   = 16;

/// Number of entries in the audio receive queue
constexpr uint8_t  QUEUE_SIZE_AUDIO_RX        = 4;

/// Number of entries in the audio transmit queue
constexpr uint8_t  QUEUE_SIZE_AUDIO_TX        = 4;

// ── WDT (nRF52840) ──────────────────────────────────────────────────────────

/// Watchdog timer timeout period in seconds
constexpr uint32_t WDT_TIMEOUT_SECS           = 8;

/// Magic value written to the WDT reload register (NRF_WDT->RR[0])
constexpr uint32_t WDT_RELOAD_MAGIC           = 0x6E524635UL;

} // namespace airtak
