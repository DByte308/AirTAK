// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/MeshNode.h — Node identity, NODEINFO broadcast, smart beaconing
// Planning: §8.8, §13.2, §8.7 | Roadmap: 2.2, 2.4
//
// Owns the local node ID (derived from FICR.DEVICEID[0], §8.8).
// Broadcasts NODEINFO_APP at startup and every 3 hours (§13.2).
// Implements smart beaconing policy for POSITION_APP (§8.7).

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>
#include <event_groups.h>

namespace mash {

class MeshNode {
public:
    // Phase 2.2: Initialize node — read FICR.DEVICEID[0] for node ID, load callsign
    // from GameConfig, broadcast initial NODEINFO_APP on channel 0.
    static void init();

    // Phase 2.2: Called periodically to broadcast NODEINFO_APP (every 3 hours, §13.2).
    static void tick(QueueHandle_t tx_queue);

    // Returns the local node ID (lower 32 bits of FICR.DEVICEID[0]).
    static uint32_t nodeId();

    // Phase 2.4: Evaluate smart beaconing policy and send POSITION_APP if appropriate.
    // Called from GPS task when GPS_EVENT_NEW_POSITION fires, and on heartbeat timer.
    // Policy: tx when displacement ≥ 10 m AND ≥ 15 s elapsed, OR 90 s heartbeat.
    // HIT/RESPAWNING/ELIMINATED players use 5-minute heartbeat (§8.7).
    static void maybeTransmitPosition(QueueHandle_t tx_queue, EventGroupHandle_t events);

    // Phase 2.12: Handle a received MASH_POSITION_REQUEST targeting this node.
    // Responds with POSITION_APP within 2 seconds, bypassing the beaconing timer.
    static void handlePositionRequest(uint32_t requester_id, QueueHandle_t tx_queue);

    // Returns the number of known mesh nodes (excludes self).
    static uint32_t knownNodeCount();
};

} // namespace mash
