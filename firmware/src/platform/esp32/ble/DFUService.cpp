// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/ble/DFUService.cpp — ESP32 OTA DFU service stub
// Planning: §9.7 | Roadmap: 1.5, 6.1
//
// The ESP32 does not have a Nordic Buttonless DFU service.  Firmware updates on
// the T-Deck are applied via USB-CDC (esptool/PlatformIO upload) or via the
// standard Arduino OTA mechanism over WiFi.
//
// This stub satisfies the DFUService.h interface:
//   init()     — no-op (no BLE DFU GATT service on ESP32)
//   enterDfu() — triggers esp_restart() so the user can manually enter download
//                mode (hold trackball center, press RESET).  A future revision
//                can use esp_ota_set_boot_partition() + esp_restart() for proper
//                OTA when a BLE file-transfer mechanism is added.

#include "ble/DFUService.h"
#include <esp_system.h>

namespace airtak {
namespace ble {

void DFUService::init() {
    // No NimBLE DFU service registered on ESP32.
}

void DFUService::enterDfu() {
    // Restart the ESP32 — the user must manually hold the trackball centre
    // and press RESET to enter download mode, or use the Arduino OTA path.
    esp_restart();
}

} // namespace ble
} // namespace airtak
