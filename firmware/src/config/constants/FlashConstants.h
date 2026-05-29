// AirTAK — Airsoft Team Awareness Kit
// Flash Storage Constants

#pragma once
#include <cstdint>
#include <cstddef>

namespace airtak {

// ── nRF52 QSPI Flash (MX25R1635F) ──────────────────────────────────────────
constexpr uint32_t FLASH_BLOCK_SIZE            = 4096;   // Erase block size
constexpr uint32_t FLASH_PROG_SIZE             = 256;    // Program page size
constexpr size_t   FLASH_LFS_LOOKAHEAD_SIZE    = 32;     // LittleFS lookahead buffer

// ── Flash File Paths ────────────────────────────────────────────────────────
// Config group files on LittleFS: "/config/device.pb", "/config/lora.pb", etc.
// Path buffer max length
constexpr size_t   FLASH_PATH_MAX_BYTES        = 48;     // Max path buffer size

// ── ESP32 LittleFS ─────────────────────────────────────────────────────────
// Temporary file suffix used for atomic writes
constexpr const char* FLASH_TMP_SUFFIX         = ".tmp";

// ── Config Buffer Sizes ────────────────────────────────────────────────────
constexpr size_t   NATIVE_FLASH_PATH_MAX       = 260;    // Native test MAX_PATH for file operations
constexpr size_t   CONFIG_BUF_SIZE             = 512;    // Per-group config buffer size
constexpr size_t   CONFIG_GROUP_COUNT          = 8;      // Number of config groups

} // namespace airtak
