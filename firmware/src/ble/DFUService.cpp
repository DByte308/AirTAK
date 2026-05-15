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

void DFUService::enterDfu() {
    // Trigger a system reset into DFU bootloader mode via the Adafruit Bluefruit
    // BLEDfu control point. This is equivalent to what the Nordic nRF Connect app
    // does when the user taps "Enter DFU" on the Buttonless DFU service.
    s_dfu.enterDfu();
}

} // namespace ble
} // namespace mash
