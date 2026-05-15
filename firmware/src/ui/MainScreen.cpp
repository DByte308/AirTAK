// MASH — Mesh Airsoft Situational Hub
// firmware/src/ui/MainScreen.cpp — Main status screen renderer
// Planning: §9.6 | Roadmap: 1.4
//
// Renders to SSD1681 (200×200 px):
//   Header: "MASH" + screen indicator dots
//   Row 1: GPS lock icon + coord / "NO FIX"
//   Row 2: Radio: channel name + RSSI (if recent)
//   Row 3: Battery percentage + charging indicator
//   Row 4: BLE: "CONNECTED" or "WAITING"
//   Row 5: Mesh: N nodes seen
//   Footer: mini status icons

#include "ui/Screens.h"
#include "hal/GPS.h"
#include "hal/Battery.h"
#include "hal/Radio.h"
#include "ble/BLEServer.h"
#include "mesh/MeshNode.h"
#include "mesh/ChannelManager.h"
#include <Arduino.h>

namespace mash {
namespace ui {

// TODO Phase 1.4: Replace stubs with actual GFX calls to an Adafruit_GFX-compatible
// display driver (GxEPD2 or equivalent) configured for the SSD1681.

void renderMainScreen() {
    // Header
    // TODO: drawRect(0, ROW_HEADER_START, EINK_WIDTH, ROW_HEADER_END, BLACK);
    // TODO: setCursor(4, 4); print("MASH");

    // GPS row
    if (GPS::hasFix()) {
        const GpsFix& fix = GPS::currentFix();
        // TODO: printf("%.5f, %.5f", fix.lat, fix.lon);
        (void)fix;
    } else {
        // TODO: print("GPS: NO FIX");
    }

    // Radio row
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        // TODO: printf("CH: %s  RSSI: %.0f", primary->name, Radio::lastRssi());
        (void)primary;
    }

    // Battery row
    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    (void)pct;
    // TODO: printf("BAT: %d%%%s", pct, Battery::isCharging() ? " CHG" : "");

    // BLE row
    // TODO: print(BLEServer::isConnected() ? "BLE: CONNECTED" : "BLE: WAITING");

    // Mesh node count
    // TODO: printf("MESH: %u nodes", MeshNode::knownNodeCount());
}

} // namespace ui
} // namespace mash
