// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/MeshNode.cpp — Node identity and NODEINFO/POSITION broadcast
// Planning: §8.8, §13.2, §8.7 | Roadmap: 2.2, 2.4

#include "mesh/MeshNode.h"
#include "mesh/Router.h"
#include "mesh/Portnums.h"
#include "hal/GPS.h"
#include "hal/Display.h"
#include "game/PlayerState.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "platform/Platform.h"
#include "config.h"
#include <Arduino.h>
#include <cstring>
#include <cstdio>

namespace airtak {

static uint32_t s_node_id           = 0;
static uint32_t s_last_nodeinfo_ms  = 0;
static uint32_t s_last_position_ms  = 0;
static uint32_t s_known_node_count  = 0;

// ── Minimal proto serializers (meshtastic wire format) ────────────────────────
// These produce binary-compatible serializations of meshtastic.User and
// meshtastic.Position protos as required for TEXT_MESSAGE/NODEINFO/POSITION
// interoperability with Meshtastic clients.

static uint16_t encodeVarint(uint8_t* buf, uint16_t pos, uint32_t v) {
    while (v >= 0x80) { buf[pos++] = (uint8_t)(v | 0x80); v >>= 7; }
    buf[pos++] = (uint8_t)v;
    return pos;
}
static uint16_t encodeTag(uint8_t* buf, uint16_t pos, uint32_t field, uint8_t wire) {
    return encodeVarint(buf, pos, (field << 3) | wire);
}
static uint16_t encodeString(uint8_t* buf, uint16_t pos, uint32_t field, const char* str) {
    uint16_t len = (uint16_t)strlen(str);
    pos = encodeTag(buf, pos, field, 2); // wire type 2 = length-delimited
    pos = encodeVarint(buf, pos, len);
    memcpy(buf + pos, str, len);
    return pos + len;
}
static uint16_t encodeBytes(uint8_t* buf, uint16_t pos, uint32_t field, const uint8_t* data, uint16_t len) {
    pos = encodeTag(buf, pos, field, 2);
    pos = encodeVarint(buf, pos, len);
    memcpy(buf + pos, data, len);
    return pos + len;
}
static uint16_t encodeFixed32(uint8_t* buf, uint16_t pos, uint32_t field, uint32_t v) {
    pos = encodeTag(buf, pos, field, 5); // wire type 5 = fixed 32-bit
    memcpy(buf + pos, &v, 4);
    return pos + 4;
}

// ── serializeUser() ───────────────────────────────────────────────────────────
// Serializes a meshtastic.User proto for NODEINFO_APP (§13.2).
// Fields: id(1), long_name(2), short_name(3), macaddr(4),
//         hw_model(5), is_licensed(6), role(7).
static uint16_t serializeUser(uint8_t* buf, uint16_t buf_len) {
    // id: "!XXXXXXXX" where X = lower 8 hex digits of node_id (Meshtastic convention).
    char id_str[12];
    snprintf(id_str, sizeof(id_str), "!%08lx", (unsigned long)s_node_id);

    // long_name: configured device name (§13.2)
    const char* long_name  = config::ConfigManager::deviceLongName();
    if (!long_name || long_name[0] == '\0') long_name = defaults::DEVICE_LONG_NAME;

    const char* short_name = config::ConfigManager::deviceShortName();
    if (!short_name || short_name[0] == '\0') short_name = defaults::DEVICE_SHORT_NAME;

    uint8_t macaddr[6];
    platform::macAddress(macaddr);

    uint16_t pos = 0;
    pos = encodeString(buf, pos, 1, id_str);     // field 1: id
    pos = encodeString(buf, pos, 2, long_name);  // field 2: long_name
    pos = encodeString(buf, pos, 3, short_name); // field 3: short_name
    pos = encodeBytes (buf, pos, 4, macaddr, sizeof(macaddr));
    // field 5: hw_model — Meshtastic HardwareModel enum, platform-specific
    pos = encodeTag   (buf, pos, 5, 0); pos = encodeVarint(buf, pos, platform::meshtasticHwModel());
    // field 6: is_licensed = false (default, must match Meshtastic owner.is_licensed)
    pos = encodeTag   (buf, pos, 6, 0); pos = encodeVarint(buf, pos, 0);
    // field 7: role = 0 (CLIENT)
    pos = encodeTag   (buf, pos, 7, 0); pos = encodeVarint(buf, pos, 0);

    (void)buf_len;
    return pos;
}

// ── serializePosition() ───────────────────────────────────────────────────────
// Serializes a meshtastic.Position proto for POSITION_APP (§8.8).
// Fields: latitude_i(1,fixed32), longitude_i(2,fixed32), altitude(3,varint),
//         time(4,fixed32), pdop(8,varint), gps_accuracy(9,varint),
//         precision_bits(14,varint).
static uint16_t serializePosition(uint8_t* buf, uint16_t buf_len) {
    const GpsFix& fix = GPS::currentFix();

    // Latitude and longitude as integers (degrees × 1e7), matching Meshtastic wire format.
    int32_t lat_i = (int32_t)(fix.latitude  * 1e7);
    int32_t lon_i = (int32_t)(fix.longitude * 1e7);
    int32_t alt_m = (int32_t)fix.altitude_m;

    uint16_t pos = 0;
    pos = encodeFixed32(buf, pos, 1, (uint32_t)lat_i);  // latitude_i
    pos = encodeFixed32(buf, pos, 2, (uint32_t)lon_i);  // longitude_i
    pos = encodeTag    (buf, pos, 3, 0); pos = encodeVarint(buf, pos, (uint32_t)alt_m); // altitude
    // field 4: time (unix seconds; derived from GPS timestamp_ms / 1000)
    uint32_t unix_sec = (uint32_t)(fix.timestamp_ms / 1000);
    pos = encodeFixed32(buf, pos, 4, unix_sec);
    uint32_t precision_bits = config::ConfigManager::positionPrecisionBits();
    if (precision_bits > 32) precision_bits = 32;
    // NOTE: PositionConfig.gps_precision_bits is the authoritative global
    // setting.  The per-slot ChannelSlot.position_precision field is reserved
    // for a future per-slot override (not yet implemented).  If both are set,
    // the per-slot value should take precedence when transmitting on that slot.
    // For now, the global value is used for all slots.
    pos = encodeTag    (buf, pos, 14, 0); pos = encodeVarint(buf, pos, precision_bits);

    (void)buf_len;
    return pos;
}

// ── MeshNode::init() ─────────────────────────────────────────────────────────
void MeshNode::init() {
    // Derive node ID from the selected platform's hardware identity source (§8.8).
    s_node_id = platform::nodeId();
    s_last_nodeinfo_ms = millis();
}

// ── MeshNode::broadcastNodeInfo() ────────────────────────────────────────────
void MeshNode::broadcastNodeInfo(QueueHandle_t tx_queue) {
    uint8_t buf[128];
    uint16_t len = serializeUser(buf, sizeof(buf));
    if (len > 0) {
        Router::sendPacket(Portnum::NODEINFO_APP, 0xFFFFFFFF, buf, len, false, tx_queue);
    }
    s_last_nodeinfo_ms = millis();
}

// ── MeshNode::tick() ─────────────────────────────────────────────────────────
void MeshNode::tick(QueueHandle_t tx_queue) {
    uint32_t now = millis();

    // Periodic NODEINFO_APP every 3 hours (§13.2).
    if ((now - s_last_nodeinfo_ms) >= (defaults::NODEINFO_BROADCAST_INTERVAL_SECS * 1000UL)) {
        broadcastNodeInfo(tx_queue);
    }

    // Smart beaconing heartbeat check.
    maybeTransmitPosition(tx_queue, nullptr);
}

// ── MeshNode::maybeTransmitPosition() ────────────────────────────────────────
void MeshNode::maybeTransmitPosition(QueueHandle_t tx_queue, EventGroupHandle_t events) {
    if (!GPS::hasFix()) return;

    uint32_t now = millis();
    float displacement = GPS::displacementFromLastTxMeters();

    // Players in HIT, RESPAWNING, or ELIMINATED states use a 5-minute heartbeat (§8.7).
    PlayerStatus ps = PlayerState::status();
    bool reduced_rate = (ps == PlayerStatus::HIT ||
                         ps == PlayerStatus::RESPAWNING ||
                         ps == PlayerStatus::ELIMINATED);
    uint32_t heartbeat_secs = config::ConfigManager::positionBroadcastIntervalSecs();
    if (heartbeat_secs == 0) heartbeat_secs = defaults::POSITION_BROADCAST_INTERVAL_SECS;
    uint32_t min_tx_secs = config::ConfigManager::positionMinTxIntervalSecs();
    if (min_tx_secs == 0) min_tx_secs = defaults::POSITION_MIN_TX_INTERVAL_SECS;
    uint32_t movement_threshold_m = config::ConfigManager::positionMovementThresholdM();
    if (movement_threshold_m == 0) movement_threshold_m = defaults::POSITION_MOVEMENT_THRESHOLD_M;

    uint32_t heartbeat_ms = reduced_rate
        ? (defaults::POSITION_HIT_HEARTBEAT_SECS * 1000UL)
        : (heartbeat_secs * 1000UL);

    bool should_tx = false;
    // Movement trigger: ≥ 10 m displacement AND ≥ 15 s since last TX.
    // Suppress movement-triggered TX for players who are not actively moving (HIT/ELIMINATED).
    if (!reduced_rate &&
        displacement >= movement_threshold_m &&
        (now - s_last_position_ms) >= (min_tx_secs * 1000UL)) {
        should_tx = true;
    }
    // Heartbeat trigger.
    if ((now - s_last_position_ms) >= heartbeat_ms) {
        should_tx = true;
    }

    if (should_tx) {
        uint8_t buf[64];
        uint16_t len = serializePosition(buf, sizeof(buf));
        if (len > 0) {
            Router::sendPacket(Portnum::POSITION_APP, 0xFFFFFFFF, buf, len, false, tx_queue);
        }
        GPS::markPositionTransmitted();
        s_last_position_ms = now;
    }
    (void)events;
}

// ── MeshNode::handlePositionRequest() ────────────────────────────────────────
void MeshNode::handlePositionRequest(uint32_t requester_id, QueueHandle_t tx_queue) {
    // Respond within 2 seconds (§8.12), bypassing beaconing timer.
    (void)requester_id;
    if (GPS::hasFix()) {
        uint8_t buf[64];
        uint16_t len = serializePosition(buf, sizeof(buf));
        if (len > 0) {
            // Broadcast response so every stale map pin can refresh from one request (§8.7).
            Router::sendPacket(Portnum::POSITION_APP, 0xFFFFFFFF, buf, len, false, tx_queue);
        }
        GPS::markPositionTransmitted();
        s_last_position_ms = millis();
    }
}

uint32_t MeshNode::nodeId()          { return s_node_id;          }
uint32_t MeshNode::knownNodeCount()  { return s_known_node_count; }

// ── Remote node tracking ──────────────────────────────────────────────────────
// Track up to 50 unique remote node IDs with metadata from NODEINFO_APP,
// POSITION_APP, and TELEMETRY_APP packets. When a new node is seen for the first
// time, increment s_known_node_count and mark the display dirty so the main
// screen reflects the updated mesh size.
constexpr uint8_t  MAX_KNOWN_NODES = 50;

struct RemoteNode {
    uint32_t node_id;
    char     long_name[40];
    char     short_name[5];
    bool     has_position;
    double   latitude;
    double   longitude;
    int32_t  altitude_m;
    uint32_t last_position_secs;
    float    battery_level;
    bool     has_battery;
    uint32_t last_seen_ms;
};

static RemoteNode s_remote_nodes[MAX_KNOWN_NODES];
static uint8_t    s_known_id_count = 0;

static RemoteNode* findOrCreateRemote(uint32_t src_node_id) {
    if (src_node_id == 0 || src_node_id == s_node_id) return nullptr;
    for (uint8_t i = 0; i < s_known_id_count; i++) {
        if (s_remote_nodes[i].node_id == src_node_id) {
            return &s_remote_nodes[i];
        }
    }
    if (s_known_id_count >= MAX_KNOWN_NODES) return nullptr;
    auto& node = s_remote_nodes[s_known_id_count];
    memset(&node, 0, sizeof(node));
    node.node_id = src_node_id;
    node.battery_level = -1.0f;
    node.last_seen_ms = millis();
    s_known_id_count++;
    s_known_node_count = s_known_id_count;
    Display::markDirty(DISPLAY_DIRTY_RADIO);
#ifndef AIRTAK_NATIVE_TEST
    Serial.printf("[NODE] added %u count=%u\n", src_node_id, s_known_id_count);
    Serial.flush();
#endif
    return &node;
}

void MeshNode::onNodeInfoReceived(uint32_t src_node_id,
                                   const char* long_name,
                                   const char* short_name) {
    if (src_node_id == 0) return;
    RemoteNode* node = findOrCreateRemote(src_node_id);
    if (!node) return;
    node->last_seen_ms = millis();
    if (long_name && long_name[0]) {
        strncpy(node->long_name, long_name, sizeof(node->long_name) - 1);
        node->long_name[sizeof(node->long_name) - 1] = '\0';
    }
    if (short_name && short_name[0]) {
        strncpy(node->short_name, short_name, sizeof(node->short_name) - 1);
        node->short_name[sizeof(node->short_name) - 1] = '\0';
    }
#ifndef AIRTAK_NATIVE_TEST
    Serial.printf("[NODE] info %u name=%s short=%s\n", src_node_id,
                  node->long_name, node->short_name);
    Serial.flush();
#endif
}

void MeshNode::onPositionReceived(uint32_t src_node_id,
                                   double lat, double lon, int32_t alt_m,
                                   uint32_t timestamp_secs) {
    if (src_node_id == 0) return;
    RemoteNode* node = findOrCreateRemote(src_node_id);
    if (!node) return;
    node->has_position = true;
    node->latitude = lat;
    node->longitude = lon;
    node->altitude_m = alt_m;
    node->last_position_secs = timestamp_secs;
    node->last_seen_ms = millis();
}

void MeshNode::onTelemetryReceived(uint32_t src_node_id,
                                    float battery_level) {
    if (src_node_id == 0) return;
    RemoteNode* node = findOrCreateRemote(src_node_id);
    if (!node) return;
    node->battery_level = battery_level;
    node->has_battery = true;
    node->last_seen_ms = millis();
}

const MeshNode::RemoteNodeInfo* MeshNode::getRemoteNode(uint8_t index) {
    if (index >= s_known_id_count) return nullptr;
    static RemoteNodeInfo info;
    const auto& src = s_remote_nodes[index];
    info.node_id = src.node_id;
    strncpy(info.long_name, src.long_name, sizeof(info.long_name));
    info.long_name[sizeof(info.long_name) - 1] = '\0';
    strncpy(info.short_name, src.short_name, sizeof(info.short_name));
    info.short_name[sizeof(info.short_name) - 1] = '\0';
    info.has_position = src.has_position;
    info.latitude = src.latitude;
    info.longitude = src.longitude;
    info.altitude_m = src.altitude_m;
    info.last_position_secs = src.last_position_secs;
    info.battery_level = src.battery_level;
    info.last_seen_ms = src.last_seen_ms;
    return &info;
}

uint8_t MeshNode::remoteNodeCount() {
    return s_known_id_count;
}

} // namespace airtak
