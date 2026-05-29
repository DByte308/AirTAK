// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ui/Screens.h — Screen ID enum and E-Ink layout constants
// Planning: §9.6 | Roadmap: 1.4
//
// The SSD1681 is 200×200 pixels, 1-bit monochrome.
// Screen cycle order (USER short press advances): MAIN → MESSAGES → PLAYERS → RADIO → MAIN

#pragma once

#include <cstdint>
#include "config/constants/Constants.h"

namespace airtak {
namespace ui {

enum class ScreenId : uint8_t {
    MAIN     = 0,  // GPS/radio/battery/BLE status overview
    MESSAGES = 1,  // Last 3 received text messages
    PLAYERS  = 2,  // Team name, player count, own status
    RADIO    = 3,  // Channel, PHY profile, RSSI, TX/RX packet counters
};

} // namespace ui
} // namespace airtak
