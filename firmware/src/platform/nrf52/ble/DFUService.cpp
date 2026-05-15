// MASH - Mesh Airsoft Situational Hub
// firmware/src/platform/nrf52/ble/DFUService.cpp - Nordic/Bluefruit DFU service
// Planning: section 9.7 | Roadmap: 1.5, 6.1

#include "ble/DFUService.h"
#include <bluefruit.h>

namespace mash {
namespace ble {

static BLEDfu s_dfu;

void DFUService::init() {
    s_dfu.begin();
}

void DFUService::enterDfu() {
    enterOTADfu();
}

} // namespace ble
} // namespace mash
