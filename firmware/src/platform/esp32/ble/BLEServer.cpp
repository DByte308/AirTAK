// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/ble/BLEServer.cpp — NimBLE GATT server lifecycle
// Planning: §9.7, §10.6 | Roadmap: 1.5
//
// ESP32 implementation of BLEServer.h using NimBLE-Arduino (h2zero/NimBLE-Arduino ≥ 1.4).
// Mirrors the nRF52/Bluefruit implementation's behaviour:
//   — LE Secure Connections + MITM (Numeric Comparison)
//   — MTU 517 requested on every connection
//   — Passkey forwarded to Display::showPasscode() for on-screen confirmation
//   — DEVICE_STATUS notified every 5 s while connected
//   — Advertising restarts automatically on disconnect

#include <Arduino.h>
#include <NimBLEDevice.h>
// NimBLE-Arduino v1.4 defines compatibility macros like:
//   #define BLEServer NimBLEServer
//   #define BLECharacteristic NimBLECharacteristic
//   etc.
// These clash with our own class names.  Undef them before including our headers.
#ifdef BLEServer
#undef BLEServer
#endif
#ifdef BLECharacteristic
#undef BLECharacteristic
#endif
#ifdef BLEService
#undef BLEService
#endif
#ifdef BLEAdvertising
#undef BLEAdvertising
#endif
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
#include "mesh/PacketPool.h"
#include "mesh/DcTracker.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "config.h"
#include "airtak/ble.pb.h"
#include <pb_encode.h>
#include <cstring>
#include <cstdio>

extern QueueHandle_t g_ble_tx_queue;

namespace airtak {
namespace ble {

static NimBLEServer*      s_server      = nullptr;
static uint16_t           s_conn_hdl    = 0xFFFF;
static bool               s_connected   = false;
static uint32_t           s_pending_pk  = 0;
static bool               s_has_pk      = false;
static uint32_t           s_last_status = 0;

// ── DeviceStatus builder (shared with nRF52 implementation) ──────────────────

static uint16_t buildDeviceStatus(uint8_t* buf, uint16_t buf_len) {
    airtak_DeviceStatus status = airtak_DeviceStatus_init_zero;
    status.node_id = MeshNode::nodeId();
    strncpy(status.node_name, config::ConfigManager::deviceLongName(),
            sizeof(status.node_name) - 1);
    status.battery_level = Battery::level();
    status.charging      = Battery::isCharging();

    status.has_gps = true;
    const GpsFix& fix = GPS::currentFix();
    status.gps.fix         = fix.valid;
    status.gps.satellites  = fix.satellites;
    status.gps.latitude    = fix.latitude;
    status.gps.longitude   = fix.longitude;
    status.gps.altitude_m  = fix.altitude_m;
    status.gps.accuracy_m  = fix.accuracy_m;

    status.has_radio = true;
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        strncpy(status.radio.channel_name, primary->name,
                sizeof(status.radio.channel_name) - 1);
    }
    status.radio.last_rssi  = Radio::lastRssi();
    status.radio.last_snr   = Radio::lastSnr();
    status.radio.packets_rx = Radio::packetsRx();
    status.radio.packets_tx = Radio::packetsTx();

    status.mesh_node_count  = MeshNode::knownNodeCount();
    status.uptime_seconds   = millis() / 1000;
    strncpy(status.firmware_version, AIRTAK_FIRMWARE_VERSION_STR,
            sizeof(status.firmware_version) - 1);

    status.dc_level = static_cast<airtak_DcLevel>(DcTracker::currentLevel());
    status.trailing_hour_ms = DcTracker::trailingHourMs();

    std::string bt_addr = NimBLEDevice::getAddress().toString();
    strncpy(status.bluetooth_address, bt_addr.c_str(), sizeof(status.bluetooth_address) - 1);

    pb_ostream_t stream = pb_ostream_from_buffer(buf, buf_len);
    if (!pb_encode(&stream, airtak_DeviceStatus_fields, &status)) return 0;
    return static_cast<uint16_t>(stream.bytes_written);
}

// ── NimBLE server callbacks ───────────────────────────────────────────────────

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        s_conn_hdl   = desc->conn_handle;
        s_connected  = true;
        Display::markDirty(DISPLAY_DIRTY_BLE);
        pServer->updateConnParams(desc->conn_handle, 6, 24, 0, 300);

        uint8_t buf[256];
        uint16_t len = buildDeviceStatus(buf, sizeof(buf));
        if (len > 0) AirTAKService::notifyDeviceStatus(buf, len);
    }

    void onDisconnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        (void)desc;
        s_conn_hdl   = 0xFFFF;
        s_connected  = false;
        s_has_pk     = false;
        Display::markDirty(DISPLAY_DIRTY_BLE);
        NimBLEDevice::startAdvertising();
    }
};

class SecurityCallbacks : public NimBLESecurityCallbacks {
    // Numeric Comparison: NimBLE calls this when both sides display a 6-digit code.
    // Returning true confirms the match (user visually confirmed on phone side).
    // The passkey is shown on our TFT display via Display::showPasscode().
    uint32_t onPassKeyRequest() override { return 0; }

    void onPassKeyNotify(uint32_t pass_key) override {
        s_pending_pk = pass_key;
        s_has_pk     = true;
        Display::showPasscode(pass_key);
    }

    bool onSecurityRequest() override { return true; }

    void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
        if (!desc->sec_state.encrypted) {
            // Encryption failed entirely (key mismatch on bonded reconnect, or
            // pairing rejected). Delete the stale bond on our side so the next
            // connection attempt from the peer triggers a fresh pairing dialog
            // instead of looping on LE Encrypt failures forever.
            NimBLEDevice::deleteBond(NimBLEAddress(desc->peer_id_addr));
            if (s_server) s_server->disconnect(desc->conn_handle);
        }
        // If encrypted but not MITM-authenticated, leave the connection up.
        // Sensitive characteristics enforce WRITE_AUTHEN at the ATT layer.
        s_has_pk = false;
    }

    bool onConfirmPIN(uint32_t pin) override {
        // Display the pin and accept — user confirms on the Android side.
        s_pending_pk = pin;
        s_has_pk     = true;
        Display::showPasscode(pin);
        return true;
    }
};

// ── Public API ────────────────────────────────────────────────────────────────

void BLEServer::init(QueueHandle_t mesh_rx_queue) {
    NimBLEDevice::init(defaults::DEVICE_LONG_NAME);
    NimBLEDevice::setMTU(517);                 // negotiate 517-byte MTU on all connections (§10.2)
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);   // +9 dBm (closest allowed to +4 for BLE)

    // LE Secure Connections + MITM (Numeric Comparison, §10.6)
    NimBLEDevice::setSecurityAuth(BLE_SM_PAIR_AUTHREQ_SC |
                                  BLE_SM_PAIR_AUTHREQ_MITM |
                                  BLE_SM_PAIR_AUTHREQ_BOND);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);
    NimBLEDevice::setSecurityCallbacks(new SecurityCallbacks());

    s_server = NimBLEDevice::createServer();
    s_server->setCallbacks(new ServerCallbacks());
    s_server->advertiseOnDisconnect(false); // we restart manually in onDisconnect

    AirTAKService::init(mesh_rx_queue);
    DFUService::init();

    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->addServiceUUID(AIRTAK_SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setName(defaults::DEVICE_LONG_NAME);
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    NimBLEDevice::startAdvertising();
}

void BLEServer::process(QueueHandle_t ble_tx_queue, QueueHandle_t /*config_queue*/) {
    PooledPacket* pkt = nullptr;
    while (xQueueReceive(ble_tx_queue, &pkt, 0) == pdTRUE && pkt) {
        if (pkt->length > 1 && s_connected) {
            uint8_t  type        = pkt->data[0];
            const uint8_t* payload = pkt->data + 1;
            uint16_t payload_len = pkt->length - 1;
            if      (type == BLE_QUEUE_MESH_RX)       AirTAKService::notifyMeshRx(payload, payload_len);
            else if (type == BLE_QUEUE_DEVICE_STATUS)  AirTAKService::notifyDeviceStatus(payload, payload_len);
            else if (type == BLE_QUEUE_ADMIN)          AirTAKService::indicateAdminResponse(payload, payload_len);
            else if (type == BLE_QUEUE_CONFIG)         AirTAKService::indicateConfigResponse(payload, payload_len);
        }
        PacketPool::free(pkt);
    }
    tick();
}

void BLEServer::tick() {
    if (!s_connected) return;
    uint32_t now = millis();
    if ((now - s_last_status) < 5000) return;
    s_last_status = now;

    uint8_t buf[256];
    uint16_t len = buildDeviceStatus(buf, sizeof(buf));
    if (len > 0) AirTAKService::notifyDeviceStatus(buf, len);
}

bool     BLEServer::isConnected()        { return s_connected;   }
uint16_t BLEServer::connectionHandle()   { return s_conn_hdl;    }
uint32_t BLEServer::pendingPasscode()    { return s_pending_pk;  }
bool     BLEServer::hasPendingPasscode() { return s_has_pk;      }

void BLEServer::disconnect() {
    if (s_connected && s_server) s_server->disconnect(s_conn_hdl);
}

} // namespace ble
} // namespace airtak
