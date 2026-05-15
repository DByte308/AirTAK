// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/BLEServer.cpp — GATT server lifecycle implementation
// Planning: §9.7, §10.6 | Roadmap: 1.5

#include "ble/BLEServer.h"
#include "ble/MashService.h"
#include "ble/DFUService.h"
#include "ble/Framing.h"
#include "hal/Display.h"
#include "hal/Battery.h"
#include "hal/GPS.h"
#include "hal/Radio.h"
#include "mesh/MeshNode.h"
#include "mesh/ChannelManager.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "config.h"
#include "mash/ble.pb.h"
#include <pb_encode.h>
#include <bluefruit.h>
#include <Arduino.h>
#include <cstdio>
#include <cstring>

namespace mash {
namespace ble {

static uint16_t s_conn_hdl        = 0xFFFF;
static bool     s_connected       = false;
static uint32_t s_pending_passkey = 0;
static bool     s_has_passkey     = false;
static uint32_t s_last_status_ms  = 0;

// ── DeviceStatus protobuf builder ────────────────────────────────────────────

static uint16_t buildDeviceStatus(uint8_t* buf, uint16_t buf_len) {
    mash_DeviceStatus status = mash_DeviceStatus_init_zero;
    status.node_id = MeshNode::nodeId();
    strncpy(status.node_name, config::ConfigManager::deviceLongName(), sizeof(status.node_name) - 1);
    status.battery_level = Battery::level();
    status.charging = Battery::isCharging();

    status.has_gps = true;
    const GpsFix& fix = GPS::currentFix();
    status.gps.fix = fix.valid;
    status.gps.satellites = fix.satellites;
    status.gps.latitude = fix.latitude;
    status.gps.longitude = fix.longitude;
    status.gps.altitude_m = fix.altitude_m;
    status.gps.accuracy_m = fix.accuracy_m;

    status.has_radio = true;
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        strncpy(status.radio.channel_name, primary->name, sizeof(status.radio.channel_name) - 1);
    }
    status.radio.last_rssi = Radio::lastRssi();
    status.radio.last_snr = Radio::lastSnr();
    status.radio.packets_rx = Radio::packetsRx();
    status.radio.packets_tx = Radio::packetsTx();

    status.mesh_node_count = MeshNode::knownNodeCount();
    status.uptime_seconds = millis() / 1000;
    strncpy(status.firmware_version, MASH_FIRMWARE_VERSION_STR, sizeof(status.firmware_version) - 1);

    pb_ostream_t stream = pb_ostream_from_buffer(buf, buf_len);
    if (!pb_encode(&stream, mash_DeviceStatus_fields, &status)) return 0;
    return static_cast<uint16_t>(stream.bytes_written);
}

// ── BLE callbacks ────────────────────────────────────────────────────────────
static void onConnect(uint16_t conn_hdl) {
    s_conn_hdl  = conn_hdl;
    s_connected = true;
    Display::markDirty(DISPLAY_DIRTY_BLE);
    // Send initial DEVICE_STATUS immediately on connection.
    uint8_t buf[256];
    uint16_t len = buildDeviceStatus(buf, sizeof(buf));
    if (len > 0) MashService::notifyDeviceStatus(buf, len);
}

static void onDisconnect(uint16_t conn_hdl, uint8_t reason) {
    (void)reason;
    s_conn_hdl    = 0xFFFF;
    s_connected   = false;
    s_has_passkey = false;
    Display::markDirty(DISPLAY_DIRTY_BLE);
    // Restart advertising after disconnect.
    Bluefruit.Advertising.start(0);
}

static bool onPasskeyDisplay(uint16_t conn_hdl, uint8_t const passkey[6], bool match_request) {
    (void)conn_hdl;
    uint32_t parsed = 0;
    for (uint8_t i = 0; i < 6; ++i) parsed = (parsed * 10U) + (passkey[i] - '0');
    s_pending_passkey = parsed;
    s_has_passkey     = true;
    Display::showPasscode(parsed);
    return match_request ? true : true;
}

// ── BLEServer::init() ────────────────────────────────────────────────────────
void BLEServer::init(QueueHandle_t mesh_rx_queue) {
    Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
    Bluefruit.begin();
    Bluefruit.setName(defaults::DEVICE_LONG_NAME);
    Bluefruit.setTxPower(4); // +4 dBm BLE advertising TX power

    // LE Secure Connections + Numeric Comparison (§10.6).
    // DISPLAY_ONLY: device shows a passkey on the E-Ink; the user confirms on the phone.
    Bluefruit.Security.setIOCaps(true, false, false);
    Bluefruit.Security.setMITM(true);
    Bluefruit.Security.setPairPasskeyCallback(onPasskeyDisplay);

    Bluefruit.Periph.setConnectCallback(onConnect);
    Bluefruit.Periph.setDisconnectCallback(onDisconnect);

    // Register MASH service and Nordic DFU service (§10.1).
    MashService::init(mesh_rx_queue);
    DFUService::init();

    // Configure advertising payload.
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addUuid(BLEUuid(MASH_SERVICE_UUID));
    Bluefruit.ScanResponse.addName();
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(160, 160); // 100 ms @ 0.625 ms/unit
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);
}

// ── BLEServer::process() ─────────────────────────────────────────────────────
void BLEServer::process(QueueHandle_t ble_tx_queue, QueueHandle_t /*config_queue*/) {
    // Deliver any queued BLE notifications (MESH_RX, DEVICE_STATUS) from the tx queue.
    // The tx queue carries PooledPacket* pointers with a 1-byte type prefix.
    PooledPacket* pkt = nullptr;
    while (xQueueReceive(ble_tx_queue, &pkt, 0) == pdTRUE && pkt) {
        if (pkt->length > 1 && s_connected) {
            uint8_t type = pkt->data[0];
            const uint8_t* payload = pkt->data + 1;
            uint16_t payload_len   = pkt->length - 1;
            if (type == 0x01) {
                // MESH_RX notification
                MashService::notifyMeshRx(payload, payload_len);
            } else if (type == 0x02) {
                // DEVICE_STATUS notification
                MashService::notifyDeviceStatus(payload, payload_len);
            }
        }
        PacketPool::free(pkt);
    }
    tick();
}

// ── BLEServer::tick() ────────────────────────────────────────────────────────
void BLEServer::tick() {
    // Send a keepalive DEVICE_STATUS notification every 5 seconds (§10.4).
    if (!s_connected) return;
    uint32_t now = millis();
    if ((now - s_last_status_ms) < 5000) return;
    s_last_status_ms = now;

    uint8_t buf[256];
    uint16_t len = buildDeviceStatus(buf, sizeof(buf));
    if (len > 0) MashService::notifyDeviceStatus(buf, len);
}

bool     BLEServer::isConnected()       { return s_connected;       }
uint16_t BLEServer::connectionHandle()  { return s_conn_hdl;        }
uint32_t BLEServer::pendingPasscode()   { return s_pending_passkey; }
bool     BLEServer::hasPendingPasscode(){ return s_has_passkey;     }

void BLEServer::disconnect() {
    if (s_connected) Bluefruit.disconnect(s_conn_hdl);
}

} // namespace ble
} // namespace mash
