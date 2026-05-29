// AirTAK — Airsoft Team Awareness Kit
// Timing Constants — Boot, Task Loop, Stats, Player, and Config Intervals
#pragma once
#include <cstdint>

namespace airtak {

// ── Boot Timing ─────────────────────────────────────────────────────────────

/// Wait delay after Serial.begin() to allow the serial port to stabilise (1 second)
constexpr uint32_t BOOT_SERIAL_SENTINEL_MS    = 1000;

/// Settle delay after board hardware initialisation is complete
constexpr uint32_t BOOT_BOARD_INIT_SETTLE_MS  = 50;

/// Delay when FlashStore initialisation fails, before retry or halt
constexpr uint32_t BOOT_FLASH_ERROR_DELAY_MS  = 2000;

/// Settle time for peripheral power rails after power-on
constexpr uint32_t BOOT_PERIPH_SETTLE_MS      = 100;

// ── Task Loop Delays ────────────────────────────────────────────────────────

/// Radio task main loop poll interval
constexpr uint32_t TASK_RADIO_POLL_MS           = 1;

/// GPS parser task loop interval
constexpr uint32_t TASK_GPS_LOOP_MS             = 100;

/// BLE server task loop interval
constexpr uint32_t TASK_BLE_LOOP_MS             = 10;

/// Display refresh task loop interval
constexpr uint32_t TASK_DISPLAY_LOOP_MS         = 50;

/// Battery voltage sampling interval (60 seconds)
constexpr uint32_t TASK_BATTERY_SAMPLE_MS       = 60000;

/// Codec2 audio frame interval (20 ms per frame)
constexpr uint32_t TASK_AUDIO_FRAME_MS          = 20;

// ── Stats ───────────────────────────────────────────────────────────────────

/// Interval for printing runtime statistics to the serial console
constexpr uint32_t STATS_PRINT_INTERVAL_MS      = 10000;

// ── Player Tick ─────────────────────────────────────────────────────────────

/// Interval for the player-state heartbeat tick (1 second)
constexpr uint32_t PLAYER_TICK_INTERVAL_MS      = 1000;

// ── Radio Restart ───────────────────────────────────────────────────────────

/// Pause duration after a ConfigManager-triggered radio restart before resuming operations
constexpr uint32_t RADIO_RESTART_PAUSE_MS       = 2000;

// ── Config Change Queue ────────────────────────────────────────────────────

/// Send timeout when pushing a configuration change onto the delivery queue
constexpr uint32_t CONFIG_QUEUE_SEND_TIMEOUT_MS = 500;

// ── Random Hex String Lengths ──────────────────────────────────────────────

/// Number of hex characters for a random node identifier
constexpr uint8_t  RANDOM_HEX_NODE_ID_LEN      = 8;

/// Number of hex characters appended to form a random short-name suffix
constexpr uint8_t  RANDOM_HEX_SHORT_NAME_LEN   = 4;

} // namespace airtak
