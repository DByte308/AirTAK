// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/DFUService.h — Nordic DFU GATT service
// Planning: §9.7 | Roadmap: 1.5, 6.1
//
// Registers the Adafruit/Nordic OTA DFU service alongside the MASH service so that
// firmware updates can be applied via nRF Connect DFU or OTA mode without a bootloader
// entry button press. The DFU service uses Nordic's open-source Buttonless DFU SMP protocol.

#pragma once

namespace mash {
namespace ble {

class DFUService {
public:
    // Phase 1.5: Register the BLE DFU service (BLEDfu from Adafruit Bluefruit library).
    // Must be called before Bluefruit.Advertising.start().
    static void init();

    // Phase 1.5: Request entry into DFU bootloader mode via the Buttonless DFU control point.
    // Called from MashService when an ADMIN_DFU_ENTER command is received over BLE.
    static void enterDfu();
};

} // namespace ble
} // namespace mash
