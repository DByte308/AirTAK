// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Display.h — SSD1681 E-Ink display driver
// Planning: §9.6, §7.1 | Roadmap: 1.4
//
// 1.54″ E-Ink 200×200 px, SSD1681 controller.
// Enforces minimum 60-second interval between full refreshes (§9.6).
// Dirty-state tracking per screen element prevents unnecessary refreshes.
// Screen rotation via short button press cycles through 4 layouts (§9.6).
//
// Dirty flag constants and ScreenId are defined in ui/Screens.h.

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <event_groups.h>
#include "ui/Screens.h"   // ScreenId, ROW_*, DISPLAY_DIRTY_*, EINK_WIDTH/HEIGHT

namespace airtak {

class Display {
public:
    // Phase 1.4: Initialize SSD1681 over SPI and render the static Main screen.
    static void init();

    // Phase 1.4: Called from task_display_refresh(). Checks dirty state and
    // refreshes the display if content has changed and ≥ 60 s since last refresh.
    static void refresh(EventGroupHandle_t system_event_group);

    // Advance to the next screen (triggered by short button press, §12.6).
    static void nextScreen();

    // Go back to the previous screen.
    static void previousScreen();

    // Get the current screen ID.
    static ui::ScreenId currentScreen();

    // Mark a screen element as dirty to trigger a future refresh.
    static void markDirty(uint16_t element_mask);

    // Clear the dirty mask (called after a successful refresh).
    static void clearDirty();

    // Force a full refresh regardless of dirty state or timing (used at boot).
    static void forceRefresh();

    // Phase 1.5: Immediately render the BLE Numeric Comparison passkey (§10.6).
    // Bypasses the 60-second refresh interval because passkey timeout is ≈ 30 s.
    static void showPasscode(uint32_t passkey);

    // Boot recovery mode screen. Bypasses the normal refresh interval.
    static void showRecoveryMode(bool config_erased, bool bonds_cleared);

    // Set TFT/OLED backlight brightness 0–100. No-op on E-Ink.
    static void setBrightness(uint8_t pct);

    // Put the display into low-power sleep (backlight off, E-Ink deep sleep).
    // Display reinitializes on next refresh automatically.
    static void sleep();

    // Wake the display from sleep.
    static void wake();
};

} // namespace airtak
