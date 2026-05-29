// AirTAK — Airsoft Team Awareness Kit
// Touch Hardware Abstraction Constants

#pragma once
#include <cstdint>

namespace airtak {

// ── GT911 Touch Controller Registers ───────────────────────────────────────
constexpr uint16_t GT911_REG_CMD             = 0x8040;  // Command register
constexpr uint16_t GT911_REG_MOD_SW1         = 0x804D;  // Module switch 1 config
constexpr uint16_t GT911_REG_TOUCH_INFO      = 0x814E;  // Touch info / status base

// ── GT911 I2C Addresses ────────────────────────────────────────────────────
// GT911 can appear at 0x5D or 0x14 depending on INT pin state at boot
constexpr uint8_t  GT911_ADDR_PRIMARY        = 0x5D;
constexpr uint8_t  GT911_ADDR_SECONDARY      = 0x14;

// ── CST328 Touch Controller I2C Address ────────────────────────────────────
constexpr uint8_t  CST328_ADDR              = 0x15;

// ── CST328 Touch Data ─────────────────────────────────────────────────────
constexpr uint8_t  CST328_TOUCH_DATA_SIZE   = 7;      // CST328 touch data register block size

// ── Touch Gesture Detection ────────────────────────────────────────────────
constexpr int16_t  TOUCH_SWIPE_THRESHOLD_PX  = 8;      // Minimum pixels for swipe vs tap
constexpr uint8_t  TOUCH_MAX_POINTS          = 2;      // Max simultaneous touch points

// ── GT911 Touch Data Buffer Sizes ──────────────────────────────────────────
constexpr uint8_t  GT911_TOUCH_INFO_SIZE     = 40;     // Touch info register block
constexpr uint8_t  GT911_CONFIG_SIZE         = 7;      // GT911 config write size

// ── GT911 Init Commands ────────────────────────────────────────────────────
constexpr uint8_t  GT911_CMD_IDLE            = 0x00;   // Ensure controller is idle
constexpr uint8_t  GT911_CMD_SOFT_RESET      = 0x02;   // Soft reset command

// ── GT911 Init Timing ─────────────────────────────────────────────────────
constexpr uint32_t GT911_RESET_DELAY_MS      = 10;      // Delay after idle command
constexpr uint32_t GT911_SOFT_RESET_DELAY_MS = 200;     // Delay after soft reset
constexpr uint32_t GT911_CONFIG_DELAY_MS     = 50;      // Delay after config write
constexpr uint32_t GT911_FLUSH_DELAY_MS      = 10;      // Delay after touch info flush
constexpr uint32_t GT911_POST_FLUSH_DELAY_MS = 50;      // Delay after returning to idle

// ── GT911 MOD_SW1 Defaults ─────────────────────────────────────────────────
// Active touch report rate: bits [1:0] = 11 (active + 5 points + X/Y)
constexpr uint8_t  GT911_MOD_SW1_DEFAULT     = 0x03;

// ── Touch Coordinate Mapping ───────────────────────────────────────────────
// GT911 raw coordinate range
constexpr uint32_t GT911_RAW_X_MAX          = 4096;
constexpr uint32_t GT911_RAW_Y_MAX          = 4096;

} // namespace airtak
