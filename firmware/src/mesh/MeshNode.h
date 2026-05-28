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
    // Also loads or generates the Curve25519 PKI keypair.
    static void init();

    // Phase 2.2: Broadcast a NODEINFO_APP meshtastic.User packet to all nodes.
    static void broadcastNodeInfo(QueueHandle_t tx_queue);

    // Backdate the NODEINFO periodic timer so tick() will rebroadcast soon after
    // startup (within ~30 s) instead of waiting the full broadcast interval.
    // Call this AFTER the initial explicit broadcastNodeInfo() so the follow-up
    // burst still fires quickly.
    static void scheduleStartupBurst();

    // Phase 2.13: Broadcast TELEMETRY_APP with battery level to the mesh.
    static void broadcastTelemetry(QueueHandle_t tx_queue);

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
    static void onNodeInfoReceived(uint32_t src_node_id,
                                   const char* long_name = nullptr,
                                   const char* short_name = nullptr);

    // Called by the Router when a POSITION_APP packet is received from a remote node.
    // Tracks the remote node's position for map display.
    static void onPositionReceived(uint32_t src_node_id,
                                   double lat, double lon, int32_t alt_m,
                                   uint32_t timestamp_secs);

    // Called by the Router when a TELEMETRY_APP packet is received from a remote node.
    // Tracks the remote node's battery level.
    static void onTelemetryReceived(uint32_t src_node_id,
                                    float battery_level);

    // Query information about a known remote node. Returns nullptr if not found.
    struct RemoteNodeInfo {
        uint32_t node_id;
        char long_name[40];
        char short_name[5];
        bool has_position;
        double latitude;
        double longitude;
        int32_t altitude_m;
        uint32_t last_position_secs;
        float battery_level;
        uint32_t last_seen_ms;
    };
    static const RemoteNodeInfo* getRemoteNode(uint8_t index);
    static uint8_t remoteNodeCount();

    // ── PKI keystore ───────────────────────────────────────────────────────
    // Returns our 32-byte Curve25519 public key (generated at init).
    static const uint8_t* pkiPublicKey();

    // Returns our 32-byte Curve25519 private key.
    static const uint8_t* pkiPrivateKey();

    // Returns the remote node's public key if stored, or nullptr.
    static const uint8_t* remotePublicKey(uint32_t node_id);

    // Stores a remote node's public key (32 bytes).
    static void storeRemotePublicKey(uint32_t node_id, const uint8_t pubkey[32]);
};

} // namespace airtak
