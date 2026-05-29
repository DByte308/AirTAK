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

// ── Config File Base Paths ────────────────────────────────────────────────
constexpr const char* CONFIG_PATH_PREFIX     = "/config/";
constexpr const char* CONFIG_PATH_DEVICE     = "/config/device.pb";
constexpr const char* CONFIG_PATH_LORA       = "/config/lora.pb";
constexpr const char* CONFIG_PATH_CHANNELS   = "/config/channels.pb";
constexpr const char* CONFIG_PATH_POSITION   = "/config/position.pb";
constexpr const char* CONFIG_PATH_POWER      = "/config/power.pb";
constexpr const char* CONFIG_PATH_GAME       = "/config/game.pb";
constexpr const char* CONFIG_PATH_SECURITY   = "/config/security.pb";
constexpr const char* CONFIG_PATH_AUDIO      = "/config/audio.pb";
constexpr const char* CONFIG_PATH_PKI_KEYPAIR = "/config/pki_keypair.bin";
constexpr const char* CONFIG_PATH_UNKNOWN    = "/config/unknown.pb";

// ── Config Group Identifiers ──────────────────────────────────────────────
// (Enum is in ConfigGroups.h; these are explicit value constants)
constexpr uint8_t  CONFIG_GROUP_ID_DEVICE      = 0;
constexpr uint8_t  CONFIG_GROUP_ID_LORA        = 1;
constexpr uint8_t  CONFIG_GROUP_ID_CHANNELS    = 2;
constexpr uint8_t  CONFIG_GROUP_ID_POSITION    = 3;
constexpr uint8_t  CONFIG_GROUP_ID_POWER       = 4;
constexpr uint8_t  CONFIG_GROUP_ID_GAME        = 5;
constexpr uint8_t  CONFIG_GROUP_ID_SECURITY    = 6;
constexpr uint8_t  CONFIG_GROUP_ID_AUDIO       = 7;
constexpr uint8_t  CONFIG_GROUP_ID_PKI_KEYPAIR = 100;

// ── Channel Role Identifiers ──────────────────────────────────────────────
// (Enum is in ChannelManager.h; these are explicit value constants)
constexpr uint8_t  CHANNEL_ROLE_DISABLED   = 0;
constexpr uint8_t  CHANNEL_ROLE_PRIMARY    = 1;
constexpr uint8_t  CHANNEL_ROLE_SECONDARY  = 2;

// ── Meshtastic Device Role ─────────────────────────────────────────────────
constexpr uint32_t MESHTASTIC_ROLE_CLIENT  = 0;

} // namespace airtak
