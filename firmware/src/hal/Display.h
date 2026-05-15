// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Display.h — SSD1681 E-Ink display driver
// Planning: §9.6, §7.1 | Roadmap: 1.4
//
// 1.54″ E-Ink 200×200 px, SSD1681 controller.
// Enforces minimum 60-second interval between full refreshes (§9.6).
// Dirty-state tracking per screen element prevents unnecessary refreshes.
// Screen rotation via short button press cycles through 4 layouts (§9.6).

#pragma once

#include <cstdint>
#include <event_groups.h>

namespace mash {

// Screen IDs — cycled by short button press (§9.6, §12.6)
enum class ScreenId : uint8_t {
    MAIN      = 0,  // node name, GPS, battery, node count, last packet time
    MESSAGES  = 1,  // last 3 received text messages
    PLAYERS   = 2,  // team name, own status badge, active player count
    RADIO     = 3,  // channel name, last RSSI/SNR, hop count
};

class Display {
public:
    // Phase 1.4: Initialize SSD1681 over SPI and render the static Main screen.
    static void init();

    // Phase 1.4: Called from task_display_refresh(). Checks dirty state and
    // refreshes the display if content has changed and ≥ 60 s since last refresh.
    static void refresh(EventGroupHandle_t system_event_group);

    // Advance to the next screen (triggered by short button press, §12.6).
    static void nextScreen();

    // Get the current screen ID.
    static ScreenId currentScreen();

    // Mark a screen element as dirty to trigger a future refresh.
    // Elements: GPS, battery, node count, messages, player status, radio stats.
    static void markDirty(uint16_t element_mask);

    // Clear the dirty mask (called after a successful refresh).
    static void clearDirty();

    // Force a full refresh regardless of dirty state or timing (used at boot).
    static void forceRefresh();
};

// Dirty-state element bit flags
constexpr uint16_t DISPLAY_DIRTY_GPS        = (1 << 0);
constexpr uint16_t DISPLAY_DIRTY_BATTERY    = (1 << 1);
constexpr uint16_t DISPLAY_DIRTY_NODE_COUNT = (1 << 2);
constexpr uint16_t DISPLAY_DIRTY_MESSAGES   = (1 << 3);
constexpr uint16_t DISPLAY_DIRTY_PLAYER     = (1 << 4);
constexpr uint16_t DISPLAY_DIRTY_RADIO      = (1 << 5);
constexpr uint16_t DISPLAY_DIRTY_ALL        = 0xFFFF;

} // namespace mash
