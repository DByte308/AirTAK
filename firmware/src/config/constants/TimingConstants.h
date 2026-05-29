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

// ── Power GPS Duty Cycle ─────────────────────────────────────────────────
constexpr uint32_t POWER_GPS_ON_SECS_DEF       = 30;     // Default GPS on time
constexpr uint32_t POWER_GPS_OFF_SECS_DEF      = 60;     // Default GPS off time

// ── Radio Restart Poll ───────────────────────────────────────────────────
constexpr uint32_t RADIO_RESTART_POLL_MS       = 10;     // Poll interval during radio restart pause

// ── Random Callsign Hex Length ───────────────────────────────────────────
constexpr uint8_t  RANDOM_HEX_CALLSIGN_LEN     = 6;      // Random hex string len for fallback callsign

// ── Conversion Factors ────────────────────────────────────────────────────
constexpr uint32_t SECS_TO_MS                    = 1000;   // Seconds to milliseconds
constexpr uint32_t MS_TO_SECS                    = 1000;   // Milliseconds per second
constexpr float    HZ_TO_KHZ                     = 1000.0f;// Hz to kHz conversion

// ── General Delays ────────────────────────────────────────────────────────
constexpr uint32_t PERIPH_POWER_SETTLE_MS        = 50;     // Peripheral power rail settle (TFT/OLED)
constexpr uint32_t TFT_WAKE_DELAY_MS             = 20;     // TFT display wake delay
constexpr uint32_t TFT_PERIPH_SETTLE_MS          = 50;     // TFT peripheral initialization settle
constexpr uint32_t TFT_BACKLIGHT_OFF_HOLD_MS     = 3;      // Backlight hold during off transition
constexpr uint32_t TFT_BACKLIGHT_PULSE_US        = 30;     // Backlight PWM pulse in microseconds

// ── Router Diagnostic Throttles ───────────────────────────────────────────
constexpr uint32_t TX_DUMP_THROTTLE_MS           = 2000;   // TX dump diagnostic throttle
constexpr uint32_t PKI_DIAG_INTERVAL_MS          = 3000;   // PKI/decrypt diagnostic interval

// ── Debug Print Limits ────────────────────────────────────────────────────
constexpr uint8_t  DEBUG_PRINT_LIMIT_PACKET      = 10;     // Packet debug print limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_NODEINFO    = 5;      // NODEINFO debug print limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_TEXTMSG     = 5;      // Text message debug print limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_TRACEROUTE  = 5;      // Traceroute debug print limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_ADMIN       = 5;      // Admin debug print limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_UNHANDLED   = 20;     // Unhandled portnum debug limit
constexpr uint8_t  DEBUG_PRINT_LIMIT_TXHEX       = 16;     // Packet hex dump bytes
constexpr uint8_t  DEBUG_PRINT_LIMIT_RXHEX       = 32;     // RX packet hex dump bytes

} // namespace airtak
