// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Display.cpp — SSD1681 E-Ink display driver implementation
// Planning: §9.6, §7.1 | Roadmap: 1.4

#include "hal/Display.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <Arduino.h>

namespace mash {

// ── Static state ──────────────────────────────────────────────────────────────
static ScreenId  s_current_screen = ScreenId::MAIN;
static uint16_t  s_dirty_mask     = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

// ── Display::init() ──────────────────────────────────────────────────────────
void Display::init() {
    // TODO Phase 1.4: Initialize SSD1681 over dedicated SPI bus using pins from config.h.
    // Render the static Main screen layout with placeholder values.
    // Reference: Meshtastic T-Echo E-Ink driver (§9.1 adopted components).
    //
    // SPI pins:
    //   SCK  = PIN_EINK_SCK  (P0.14)
    //   MOSI = PIN_EINK_MOSI (P0.13)
    //   CS   = PIN_EINK_CS   (P0.30)
    //   DC   = PIN_EINK_DC   (P0.28)
    //   RST  = PIN_EINK_RST  (P0.02)
    //   BUSY = PIN_EINK_BUSY (P0.03)
    s_last_refresh_ms = 0;
    s_dirty_mask      = DISPLAY_DIRTY_ALL;
}

// ── Display::refresh() ───────────────────────────────────────────────────────
void Display::refresh(EventGroupHandle_t system_event_group) {
    (void)system_event_group;

    if (s_dirty_mask == 0) return; // nothing changed

    uint32_t now = millis();
    if ((now - s_last_refresh_ms) < EINK_MIN_REFRESH_MS) return; // too soon

    // TODO Phase 1.4: Render the current screen layout to the SSD1681.
    // Switch on s_current_screen to select Main/Messages/Players/Radio.

    s_last_refresh_ms = now;
    s_dirty_mask      = 0;
}

// ── Display::nextScreen() ────────────────────────────────────────────────────
void Display::nextScreen() {
    uint8_t next = (static_cast<uint8_t>(s_current_screen) + 1) % 4;
    s_current_screen = static_cast<ScreenId>(next);
    markDirty(DISPLAY_DIRTY_ALL);
}

ScreenId Display::currentScreen()          { return s_current_screen; }
void Display::markDirty(uint16_t mask)     { s_dirty_mask |= mask; }
void Display::clearDirty()                 { s_dirty_mask = 0; }
void Display::forceRefresh()               { s_dirty_mask = DISPLAY_DIRTY_ALL; s_last_refresh_ms = 0; }

} // namespace mash
