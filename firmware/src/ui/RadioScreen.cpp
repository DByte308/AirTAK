// MASH — Mesh Airsoft Situational Hub
// firmware/src/ui/RadioScreen.cpp — Radio diagnostics screen renderer
// Planning: §9.6 | Roadmap: 1.4
//
// Displays: active channel name, PHY profile, last RSSI/SNR, TX/RX packet counters.

#include "ui/Screens.h"
#include "hal/Radio.h"
#include "mesh/ChannelManager.h"
#include "config/Defaults.h"
#include <Arduino.h>

namespace mash {
namespace ui {

void renderRadioScreen() {
    // Header
    // TODO Phase 1.4: drawText("RADIO", header row);

    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        // TODO: printf("CH: %s  PHY: %d", primary->name, primary->phy_profile_index);
        (void)primary;
    }

    // TODO: printf("RSSI: %.0f dBm", Radio::lastRssi());
    // TODO: printf("SNR:  %.1f dB",  Radio::lastSnr());
    // TODO: printf("TX: %u  RX: %u", Radio::txPacketCount(), Radio::rxPacketCount());
}

} // namespace ui
} // namespace mash
