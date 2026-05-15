// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/MeshNode.cpp — Node identity and NODEINFO/POSITION broadcast
// Planning: §8.8, §13.2, §8.7 | Roadmap: 2.2, 2.4

#include "mesh/MeshNode.h"
#include "mesh/Router.h"
#include "mesh/Portnums.h"
#include "hal/GPS.h"
#include "game/PlayerState.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <Arduino.h>

// nRF52840 FICR register address for unique device ID
#define NRF_FICR_BASE        0x10000000UL
#define NRF_FICR_DEVICEID0   (*(volatile uint32_t*)(NRF_FICR_BASE + 0x060))

namespace mash {

static uint32_t s_node_id           = 0;
static uint32_t s_last_nodeinfo_ms  = 0;
static uint32_t s_last_position_ms  = 0;
static uint32_t s_known_node_count  = 0;

void MeshNode::init() {
    // Derive node ID from lower 32 bits of FICR.DEVICEID[0] (§8.8).
    s_node_id = NRF_FICR_DEVICEID0;

    // Broadcast initial NODEINFO_APP on channel 0 (§13.2).
    // TODO Phase 2.2: Serialize meshtastic.User proto and send via Router.
    s_last_nodeinfo_ms = millis();
}

void MeshNode::tick(QueueHandle_t tx_queue) {
    uint32_t now = millis();

    // Periodic NODEINFO_APP every 3 hours (§13.2)
    if ((now - s_last_nodeinfo_ms) >= (defaults::NODEINFO_BROADCAST_INTERVAL_SECS * 1000UL)) {
        // TODO Phase 2.2: Re-broadcast NODEINFO_APP.
        s_last_nodeinfo_ms = now;
    }

    // Smart beaconing heartbeat check
    maybeTransmitPosition(tx_queue, nullptr);
}

void MeshNode::maybeTransmitPosition(QueueHandle_t tx_queue, EventGroupHandle_t events) {
    if (!GPS::hasFix()) return;

    uint32_t now = millis();
    float displacement = GPS::displacementFromLastTxMeters();

    // Determine heartbeat interval based on player status (§8.7)
    uint32_t heartbeat_ms = defaults::POSITION_BROADCAST_INTERVAL_SECS * 1000UL;
    // Phase 5.3: if PlayerState::status() is HIT/RESPAWNING/ELIMINATED, use 5-min heartbeat
    // heartbeat_ms = defaults::POSITION_HIT_HEARTBEAT_SECS * 1000UL;

    bool should_tx = false;
    // Movement trigger: ≥ 10 m displacement AND ≥ 15 s since last TX
    if (displacement >= defaults::POSITION_MOVEMENT_THRESHOLD_M &&
        (now - s_last_position_ms) >= (defaults::POSITION_MIN_TX_INTERVAL_SECS * 1000UL)) {
        should_tx = true;
    }
    // Heartbeat trigger
    if ((now - s_last_position_ms) >= heartbeat_ms) {
        should_tx = true;
    }

    if (should_tx) {
        // TODO Phase 2.4: Serialize POSITION_APP proto with GPS fix data and send.
        GPS::markPositionTransmitted();
        s_last_position_ms = now;
    }
    (void)tx_queue; (void)events;
}

void MeshNode::handlePositionRequest(uint32_t requester_id, QueueHandle_t tx_queue) {
    // Phase 2.12: Respond within 2 seconds, bypassing beaconing timer.
    // Force a POSITION_APP transmission immediately if fix is valid.
    if (GPS::hasFix()) {
        // TODO Phase 2.12: Serialize and send POSITION_APP immediately.
        GPS::markPositionTransmitted();
        s_last_position_ms = millis();
    }
    (void)requester_id; (void)tx_queue;
}

uint32_t MeshNode::nodeId()          { return s_node_id;           }
uint32_t MeshNode::knownNodeCount()  { return s_known_node_count;  }

} // namespace mash
