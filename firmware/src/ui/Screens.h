// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ui/Screens.h — Screen ID enum and E-Ink layout constants
// Planning: §9.6 | Roadmap: 1.4
//
// The SSD1681 is 200×200 pixels, 1-bit monochrome.
// Screen cycle order (USER short press advances): MAIN → MESSAGES → PLAYERS → RADIO → MAIN

#pragma once

#include <cstdint>

namespace airtak {
namespace ui {

enum class ScreenId : uint8_t {
    MAIN     = 0,  // GPS/radio/battery/BLE status overview
    MESSAGES = 1,  // Last 3 received text messages
    PLAYERS  = 2,  // Team name, player count, own status
    RADIO    = 3,  // Channel, PHY profile, RSSI, TX/RX packet counters
};

constexpr uint8_t SCREEN_COUNT = 4;

// Display dimensions (SSD1681, §7.1)
constexpr uint16_t EINK_WIDTH  = 200;
constexpr uint16_t EINK_HEIGHT = 200;

// Layout zones (row ranges in pixels, used by each screen renderer)
constexpr uint8_t  ROW_HEADER_START   = 0;
constexpr uint8_t  ROW_HEADER_END     = 16;  // 16 px header: screen title + border
constexpr uint8_t  ROW_CONTENT_START  = 18;
constexpr uint8_t  ROW_CONTENT_END    = 184;
constexpr uint8_t  ROW_FOOTER_START   = 186; // status bar: battery icon + BLE icon
constexpr uint8_t  ROW_FOOTER_END     = 200;

// Dirty-state bit flags for partial refresh tracking (§9.6)
constexpr uint16_t DISPLAY_DIRTY_GPS     = (1 << 0);
constexpr uint16_t DISPLAY_DIRTY_RADIO   = (1 << 1);
constexpr uint16_t DISPLAY_DIRTY_BATTERY = (1 << 2);
constexpr uint16_t DISPLAY_DIRTY_BLE     = (1 << 3);
constexpr uint16_t DISPLAY_DIRTY_GAME    = (1 << 4);
constexpr uint16_t DISPLAY_DIRTY_MESSAGE = (1 << 5);
constexpr uint16_t DISPLAY_DIRTY_PLAYERS = (1 << 6);
constexpr uint16_t DISPLAY_DIRTY_ALL     = 0xFFFF;

} // namespace ui

using ui::DISPLAY_DIRTY_GPS;
using ui::DISPLAY_DIRTY_RADIO;
using ui::DISPLAY_DIRTY_BATTERY;
using ui::DISPLAY_DIRTY_BLE;
using ui::DISPLAY_DIRTY_GAME;
using ui::DISPLAY_DIRTY_MESSAGE;
using ui::DISPLAY_DIRTY_PLAYERS;
using ui::DISPLAY_DIRTY_ALL;
using ui::EINK_WIDTH;
using ui::EINK_HEIGHT;
using ui::SCREEN_COUNT;

} // namespace airtak
