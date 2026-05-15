// MASH — Mesh Airsoft Situational Hub
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
#include "config.h"
#include <Arduino.h>
#include <cstring>
#include <cstdio>

// nRF52840 FICR register address for unique device ID
#define NRF_FICR_BASE        0x10000000UL
#define NRF_FICR_DEVICEID0   (*(volatile uint32_t*)(NRF_FICR_BASE + 0x060))

namespace mash {

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
static uint16_t encodeFixed32(uint8_t* buf, uint16_t pos, uint32_t field, uint32_t v) {
    pos = encodeTag(buf, pos, field, 5); // wire type 5 = fixed 32-bit
    memcpy(buf + pos, &v, 4);
    return pos + 4;
}

// ── serializeUser() ───────────────────────────────────────────────────────────
// Serializes a meshtastic.User proto for NODEINFO_APP (§13.2).
// Fields: id(1,string), long_name(2,string), short_name(3,string),
//         hw_model(6,varint), is_licensed(7,bool), role(8,varint).
static uint16_t serializeUser(uint8_t* buf, uint16_t buf_len) {
    // id: "!XXXXXXXX" where X = lower 8 hex digits of node_id (Meshtastic convention).
    char id_str[12];
    snprintf(id_str, sizeof(id_str), "!%08lx", (unsigned long)s_node_id);

    // long_name: configured device name (§13.2)
    const char* long_name  = config::ConfigManager::deviceLongName();
    if (!long_name || long_name[0] == '\0') long_name = defaults::DEVICE_LONG_NAME;

    // short_name: first 4 chars of the hex suffix (Meshtastic convention)
    char short_name[5];
    snprintf(short_name, sizeof(short_name), "%04lx", (unsigned long)(s_node_id & 0xFFFF));

    uint16_t pos = 0;
    pos = encodeString(buf, pos, 1, id_str);     // field 1: id
    pos = encodeString(buf, pos, 2, long_name);  // field 2: long_name
    pos = encodeString(buf, pos, 3, short_name); // field 3: short_name
    // field 6: hw_model = 41 (LILYGOT_ECHO) — Meshtastic HardwareModel enum
    pos = encodeTag   (buf, pos, 6, 0); pos = encodeVarint(buf, pos, 41);
    // field 8: role = 0 (CLIENT, default)
    pos = encodeTag   (buf, pos, 8, 0); pos = encodeVarint(buf, pos, 0);

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
    // field 14: precision_bits = 13 (approximately 10 m precision, standard)
    pos = encodeTag    (buf, pos, 14, 0); pos = encodeVarint(buf, pos, 13);

    (void)buf_len;
    return pos;
}

// ── MeshNode::init() ─────────────────────────────────────────────────────────
void MeshNode::init() {
    // Derive node ID from lower 32 bits of FICR.DEVICEID[0] (§8.8).
    s_node_id = NRF_FICR_DEVICEID0;
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
    uint32_t heartbeat_ms = reduced_rate
        ? (defaults::POSITION_HIT_HEARTBEAT_SECS * 1000UL)
        : (defaults::POSITION_BROADCAST_INTERVAL_SECS * 1000UL);

    bool should_tx = false;
    // Movement trigger: ≥ 10 m displacement AND ≥ 15 s since last TX.
    // Suppress movement-triggered TX for players who are not actively moving (HIT/ELIMINATED).
    if (!reduced_rate &&
        displacement >= defaults::POSITION_MOVEMENT_THRESHOLD_M &&
        (now - s_last_position_ms) >= (defaults::POSITION_MIN_TX_INTERVAL_SECS * 1000UL)) {
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
    if (GPS::hasFix()) {
        uint8_t buf[64];
        uint16_t len = serializePosition(buf, sizeof(buf));
        if (len > 0) {
            // Unicast response to requester (§8.12).
            Router::sendPacket(Portnum::POSITION_APP, requester_id, buf, len, false, tx_queue);
        }
        GPS::markPositionTransmitted();
        s_last_position_ms = millis();
    }
}

uint32_t MeshNode::nodeId()          { return s_node_id;          }
uint32_t MeshNode::knownNodeCount()  { return s_known_node_count; }

// ── MeshNode::onNodeInfoReceived() ───────────────────────────────────────────
// Track up to 50 unique remote node IDs. When a new node is seen for the first
// time, increment s_known_node_count and mark the display dirty so the main
// screen reflects the updated mesh size.
constexpr uint8_t  MAX_KNOWN_NODES = 50;
static uint32_t    s_known_ids[MAX_KNOWN_NODES];
static uint8_t     s_known_id_count = 0;

void MeshNode::onNodeInfoReceived(uint32_t src_node_id) {
    if (src_node_id == s_node_id || src_node_id == 0) return;
    for (uint8_t i = 0; i < s_known_id_count; i++) {
        if (s_known_ids[i] == src_node_id) return; // already known
    }
    if (s_known_id_count < MAX_KNOWN_NODES) {
        s_known_ids[s_known_id_count++] = src_node_id;
    }
    s_known_node_count = s_known_id_count;
    Display::markDirty(DISPLAY_DIRTY_RADIO);
}

} // namespace mash
