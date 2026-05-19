// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/nrf52/ble/BLEServer.cpp - Bluefruit GATT server lifecycle
// Planning: section 9.7, section 10.6 | Roadmap: 1.5

#include "ble/BLEServer.h"
#include "ble/AirTAKService.h"
#include "ble/DFUService.h"
#include "ble/Framing.h"
#include "ble/BLEQueue.h"
#include "hal/Display.h"
#include "hal/Battery.h"
#include "hal/GPS.h"
#include "hal/Radio.h"
#include "mesh/MeshNode.h"
#include "mesh/ChannelManager.h"
#include "mesh/DcTracker.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "config.h"
#include "airtak/ble.pb.h"
#include <pb_encode.h>
#include <bluefruit.h>
#include <Arduino.h>
#include <cstdio>
#include <cstring>

namespace airtak {
namespace ble {

static uint16_t s_conn_hdl        = 0xFFFF;
static bool     s_connected       = false;
static uint32_t s_pending_passkey = 0;
static bool     s_has_passkey     = false;
static uint32_t s_last_status_ms  = 0;

static uint16_t buildDeviceStatus(uint8_t* buf, uint16_t buf_len) {
    airtak_DeviceStatus status = airtak_DeviceStatus_init_zero;
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
    strncpy(status.firmware_version, AIRTAK_FIRMWARE_VERSION_STR, sizeof(status.firmware_version) - 1);

    status.dc_level = static_cast<airtak_DcLevel>(DcTracker::currentLevel());
    status.trailing_hour_ms = DcTracker::trailingHourMs();

    pb_ostream_t stream = pb_ostream_from_buffer(buf, buf_len);
    if (!pb_encode(&stream, airtak_DeviceStatus_fields, &status)) return 0;
    return static_cast<uint16_t>(stream.bytes_written);
}

static void onConnect(uint16_t conn_hdl) {
    s_conn_hdl  = conn_hdl;
    s_connected = true;
    Display::markDirty(DISPLAY_DIRTY_BLE);

    uint8_t buf[256];
    uint16_t len = buildDeviceStatus(buf, sizeof(buf));
    if (len > 0) AirTAKService::notifyDeviceStatus(buf, len);
}

static void onDisconnect(uint16_t conn_hdl, uint8_t reason) {
    (void)conn_hdl;
    (void)reason;
    s_conn_hdl    = 0xFFFF;
    s_connected   = false;
    s_has_passkey = false;
    Display::markDirty(DISPLAY_DIRTY_BLE);
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

void BLEServer::init(QueueHandle_t mesh_rx_queue) {
    Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
    Bluefruit.begin();
    Bluefruit.setName(defaults::DEVICE_LONG_NAME);
    Bluefruit.setTxPower(4);

    Bluefruit.Security.setIOCaps(true, false, false);
    Bluefruit.Security.setMITM(true);
    Bluefruit.Security.setPairPasskeyCallback(onPasskeyDisplay);

    Bluefruit.Periph.setConnectCallback(onConnect);
    Bluefruit.Periph.setDisconnectCallback(onDisconnect);

    AirTAKService::init(mesh_rx_queue);
    DFUService::init();

    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addUuid(BLEUuid(AIRTAK_SERVICE_UUID));
    Bluefruit.ScanResponse.addName();
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(160, 160);
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);
}

void BLEServer::process(QueueHandle_t ble_tx_queue, QueueHandle_t /*config_queue*/) {
    PooledPacket* pkt = nullptr;
    while (xQueueReceive(ble_tx_queue, &pkt, 0) == pdTRUE && pkt) {
        if (pkt->length > 1 && s_connected) {
            uint8_t type = pkt->data[0];
            const uint8_t* payload = pkt->data + 1;
            uint16_t payload_len   = pkt->length - 1;
            if (type == BLE_QUEUE_MESH_RX) {
                AirTAKService::notifyMeshRx(payload, payload_len);
            } else if (type == BLE_QUEUE_DEVICE_STATUS) {
                AirTAKService::notifyDeviceStatus(payload, payload_len);
            } else if (type == BLE_QUEUE_ADMIN) {
                AirTAKService::indicateAdminResponse(payload, payload_len);
            } else if (type == BLE_QUEUE_CONFIG) {
                AirTAKService::indicateConfigResponse(payload, payload_len);
            }
        }
        PacketPool::free(pkt);
    }
    tick();
}

void BLEServer::tick() {
    if (!s_connected) return;
    uint32_t now = millis();
    if ((now - s_last_status_ms) < 5000) return;
    s_last_status_ms = now;

    uint8_t buf[256];
    uint16_t len = buildDeviceStatus(buf, sizeof(buf));
    if (len > 0) AirTAKService::notifyDeviceStatus(buf, len);
}

bool     BLEServer::isConnected()       { return s_connected;       }
uint16_t BLEServer::connectionHandle()  { return s_conn_hdl;        }
uint32_t BLEServer::pendingPasscode()   { return s_pending_passkey; }
bool     BLEServer::hasPendingPasscode(){ return s_has_passkey;     }

void BLEServer::disconnect() {
    if (s_connected) Bluefruit.disconnect(s_conn_hdl);
}

} // namespace ble
} // namespace airtak
