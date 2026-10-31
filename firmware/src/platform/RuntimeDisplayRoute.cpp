// AirTAK — Airsoft Team Awareness Kit
// platform/RuntimeDisplayRoute.cpp — portable runtime display routing predicates
//
// Values are compared against the descriptor only; no board headers, Arduino,
// FreeRTOS, or build_src_filter is consulted. Unknown board returns false for
// both predicates, so the runtime dispatcher never drives a display it cannot
// identify.

#include "platform/RuntimeDisplayRoute.h"

namespace airtak {

bool runtimeOledDisplayCompatible(const BoardDescriptor& bd) {
    if (&bd == &unknownBoard()) return false;
    if (bd.display != DisplayKind::Oled) return false;

    // SSD1306 128x64 on a valid I2C bus, with a sensible panel address.
    if (bd.oledSda < 0 || bd.oledScl < 0) return false;   // no I2C bus
    if (bd.oledI2cAddr < 0) return false;                  // no addressed panel
    if (bd.oledWidth != 128 || bd.oledHeight != 64) return false;

    return true;  // oledRst is optional (-1 allowed)
}

bool runtimeTftDisplayCompatible(const BoardDescriptor& bd, const RuntimeTftWiring& w) {
    if (&bd == &unknownBoard()) return false;
    if (bd.display != DisplayKind::Tft) return false;

    // Geometry must be valid on BOTH sides; optional pins (-1) match exactly.
    if (bd.tftWidth < 0 || bd.tftHeight < 0) return false;
    if (w.width < 0 || w.height < 0) return false;

    return bd.tftSck == w.sck && bd.tftMosi == w.mosi && bd.tftMiso == w.miso &&
           bd.tftCs  == w.cs  && bd.tftDc  == w.dc   &&
           bd.tftRst == w.rst && bd.tftBl  == w.bl   &&
           bd.tftWidth == w.width && bd.tftHeight == w.height;
}

} // namespace airtak
