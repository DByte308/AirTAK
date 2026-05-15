// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/DFUService.cpp — Nordic DFU GATT service implementation
// Planning: §9.7 | Roadmap: 1.5, 6.1

#include "ble/DFUService.h"
#include <bluefruit.h>

namespace mash {
namespace ble {

static BLEDfu s_dfu;

void DFUService::init() {
    // Adafruit Bluefruit BLEDfu registers the Nordic DFU service automatically.
    // Must be called before service advertising begins.
    s_dfu.begin();
}

} // namespace ble
} // namespace mash
