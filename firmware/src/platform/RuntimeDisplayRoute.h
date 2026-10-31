// AirTAK — Airsoft Team Awareness Kit
// platform/RuntimeDisplayRoute.h — portable runtime display routing predicates
//
// Pure C++17, Arduino/FreeRTOS-free. Decides whether the selected board
// descriptor is compatible with a given display backend, so a shared ESP32-S3
// image can route to OLED vs TFT at runtime instead of at compile time
// (mirrors the per-board Display.cpp split, but driven by the descriptor).
//
// The two predicates are the single source of truth for "which backend does
// this board need?" — the runtime dispatcher and the native tests both use them.

#pragma once
#include "platform/BoardDescriptor.h"

namespace airtak {

// Fixed configuration of a TFT panel as actually compiled into the backend
// (from the TFT_eSPI build flags + the LOGICAL geometry after rotation 1).
// A pin of -1 means "not wired / optional" (e.g. TFT_RST tied to system reset).
struct RuntimeTftWiring {
    int sck   = -1;
    int miso  = -1;
    int mosi  = -1;
    int cs    = -1;
    int dc    = -1;
    int rst   = -1;   // optional; -1 = reset tied to system reset
    int bl    = -1;
    int width = -1;   // LOGICAL width (post rotation-1)
    int height = -1;  // LOGICAL height (post rotation-1)
};

// True when [bd] describes the supported SSD1306 128x64 OLED: a valid I2C bus
// (Sda/Scl >= 0) and a sensible address (>= 0). The reset pin is optional (-1).
// Unknown / missing-data / wrong-size descriptors return false.
bool runtimeOledDisplayCompatible(const BoardDescriptor& bd);

// True when [bd] is a TFT board whose wiring exactly matches the compiled
// [wiring] tuple: pin-for-pin (including optional -1 pins) and geometry >= 0 on
// both sides. Mismatched / incomplete / unknown descriptors return false.
bool runtimeTftDisplayCompatible(const BoardDescriptor& bd, const RuntimeTftWiring& wiring);

} // namespace airtak
