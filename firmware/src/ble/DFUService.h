// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ble/DFUService.h — Nordic DFU GATT service
// Planning: §9.7 | Roadmap: 1.5, 6.1
//
// Registers the Adafruit/Nordic OTA DFU service alongside the AirTAK service so that
// firmware updates can be applied via nRF Connect DFU or OTA mode without a bootloader
// entry button press. The DFU service uses Nordic's open-source Buttonless DFU SMP protocol.

#pragma once

namespace airtak {
namespace ble {

class DFUService {
public:
    // Phase 1.5: Register the platform BLE DFU service.
    // Must be called before BLE advertising starts.
    static void init();

    // Phase 1.5: Request entry into DFU bootloader mode via the Buttonless DFU control point.
    // Called from AirTAKService when an ADMIN_DFU_ENTER command is received over BLE.
    static void enterDfu();
};

} // namespace ble
} // namespace airtak
