// AirTAK — Airsoft Team Awareness Kit
// Recovery Mode Constants

#pragma once
#include <cstdint>

namespace airtak {

// ── Recovery Mode Button Timing (§12.7) ────────────────────────────────────
// User must hold button at power-on for this many ms to enter recovery
constexpr uint32_t RECOVERY_BUTTON_HOLD_MS       = 5000;   // 5 seconds
// Minimum hold for a valid short press in recovery menu
constexpr uint32_t RECOVERY_SHORT_PRESS_MIN_MS   = 50;
// USB serial DFU bootloader: hold 5 s after SAFE MODE
constexpr uint32_t RECOVERY_USB_SERIAL_HOLD_MS   = 5000;   // 5 seconds
// Config erase: hold 10 s after SAFE MODE
constexpr uint32_t RECOVERY_CONFIG_ERASE_HOLD_MS = 10000;  // 10 seconds
// Poll interval during recovery mode loop
constexpr uint32_t RECOVERY_POLL_MS              = 10;

// ── Recovery BLE ─────────────────────────────────────────────────────────
constexpr const char* RECOVERY_BLE_NAME        = "AirTAK Recovery";
constexpr uint32_t    RECOVERY_DFU_ENTRY_DELAY_MS = 250;
constexpr uint32_t    RECOVERY_RESTART_DELAY_MS = 500;
constexpr uint32_t    RECOVERY_PERIPH_SETTLE_MS = 50;
constexpr uint32_t    RECOVERY_DEBOUNCE_POLL_MS = 100;

} // namespace airtak
