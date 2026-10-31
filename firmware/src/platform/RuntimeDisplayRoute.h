// Portable display-backend compatibility checks shared by the dispatcher and tests.
// Unknown or incompatible descriptors must not drive display pins.

#pragma once
#include "platform/BoardDescriptor.h"

namespace airtak {

// Compiled TFT_eSPI wiring and logical geometry after rotation 1.
// Optional/unwired pins use -1.
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

// SSD1306 128x64 with non-negative I2C pins/address; reset is optional.
bool runtimeOledDisplayCompatible(const BoardDescriptor& bd);

// Exact TFT pin/geometry match, including optional -1 pins; geometry must be >= 0.
bool runtimeTftDisplayCompatible(const BoardDescriptor& bd, const RuntimeTftWiring& wiring);

} // namespace airtak
