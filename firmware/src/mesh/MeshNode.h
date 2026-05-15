// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/MeshNode.h — Node identity, NODEINFO broadcast, smart beaconing
// Planning: §8.8, §13.2, §8.7 | Roadmap: 2.2, 2.4
//
// Owns the local node ID (derived from the selected platform identity source, §8.8).
// Broadcasts NODEINFO_APP at startup and every 3 hours (§13.2).
// Implements smart beaconing policy for POSITION_APP (§8.7).

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>
#include <event_groups.h>

namespace airtak {

class MeshNode {
public:
    // Phase 2.2: Initialize node — read platform identity, load callsign
    // from GameConfig, broadcast initial NODEINFO_APP on channel 0.
    static void init();

    // Phase 2.2: Broadcast a NODEINFO_APP meshtastic.User packet to all nodes.
    static void broadcastNodeInfo(QueueHandle_t tx_queue);

    // Phase 2.2: Called periodically to broadcast NODEINFO_APP (every 3 hours, §13.2).
    static void tick(QueueHandle_t tx_queue);

    // Returns the local node ID.
    static uint32_t nodeId();

    // Phase 2.4: Evaluate smart beaconing policy and send POSITION_APP if appropriate.
    // Called from GPS task when GPS_EVENT_NEW_POSITION fires, and on heartbeat timer.
    // Policy: tx when displacement ≥ 10 m AND ≥ 15 s elapsed, OR 90 s heartbeat.
    // HIT/RESPAWNING/ELIMINATED players use 5-minute heartbeat (§8.7).
    static void maybeTransmitPosition(QueueHandle_t tx_queue, EventGroupHandle_t events);

    // Phase 2.12: Handle a received AIRTAK_POSITION_REQUEST targeting this node.
    // Responds with POSITION_APP within 2 seconds, bypassing the beaconing timer.
    static void handlePositionRequest(uint32_t requester_id, QueueHandle_t tx_queue);

    // Returns the number of known mesh nodes (excludes self).
    static uint32_t knownNodeCount();

    // Phase 2.2: Called by the Router when a NODEINFO_APP packet is received from a
    // remote node. Tracks unique source IDs so knownNodeCount() stays accurate.
    // Updates the display dirty flag when a new node is first seen.
    static void onNodeInfoReceived(uint32_t src_node_id);
};

} // namespace airtak
