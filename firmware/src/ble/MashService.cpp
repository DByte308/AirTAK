// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/MashService.cpp — MASH GATT service implementation
// Planning: §10.1, §10.3, §10.4, §10.5 | Roadmap: 1.5

#include "ble/MashService.h"
#include "ble/Framing.h"
#include "config/ConfigManager.h"
#include "mesh/Router.h"
#include <cstring>
#include <Arduino.h>

namespace mash {
namespace ble {

static BLEService      s_svc(MASH_SERVICE_UUID);
static BLECharacteristic s_config_rw(CHAR_CONFIG_RW_UUID);
static BLECharacteristic s_mesh_tx  (CHAR_MESH_TX_UUID);
static BLECharacteristic s_mesh_rx  (CHAR_MESH_RX_UUID);
static BLECharacteristic s_status   (CHAR_DEVICE_STATUS_UUID);
static BLECharacteristic s_admin    (CHAR_ADMIN_UUID);

static QueueHandle_t    s_mesh_rx_queue = nullptr;

// CONFIG_RW write callback (§10.3): receives chunked nanopb ConfigPayload frames
static void onConfigWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    // TODO Phase 1.5: Framing::decodeChunk → reassemble → parse ConfigPayload proto
    // → ConfigManager::applyGroup(group, payload, len, commit)
    (void)chr; (void)data; (void)len;
}

// MESH_TX write callback (§10.3): Android sends a mesh packet for local dispatch + relay
static void onMeshTx(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl; (void)chr;
    // TODO Phase 1.5: Parse MeshPacket proto from data, push to radio TX queue via Router.
    (void)data; (void)len;
}

// ADMIN write callback (§10.5): receives AdminRequest proto (single characteristic, no framing)
static void onAdminWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    // TODO Phase 1.5: Parse AdminRequest, execute command, respond via indicateAdminResponse.
    (void)chr; (void)data; (void)len;
}

void MashService::init(QueueHandle_t mesh_rx_queue) {
    s_mesh_rx_queue = mesh_rx_queue;

    s_svc.begin();

    // CONFIG_RW: Read + Write (authenticated), Indicate
    s_config_rw.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_config_rw.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_ENC_WITH_MITM);
    s_config_rw.setFixedLen(0); // variable length
    s_config_rw.setWriteCallback(onConfigWrite);
    s_config_rw.begin();

    // MESH_TX: Write without response (authenticated)
    s_mesh_tx.setProperties(CHR_PROPS_WRITE_WO_RESP);
    s_mesh_tx.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_mesh_tx.setFixedLen(0);
    s_mesh_tx.setWriteCallback(onMeshTx);
    s_mesh_tx.begin();

    // MESH_RX: Notify (authenticated)
    s_mesh_rx.setProperties(CHR_PROPS_NOTIFY);
    s_mesh_rx.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_NO_ACCESS);
    s_mesh_rx.setFixedLen(0);
    s_mesh_rx.begin();

    // DEVICE_STATUS: Read/Notify (open — no auth required per §10.4)
    s_status.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
    s_status.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
    s_status.setFixedLen(0);
    s_status.begin();

    // ADMIN: Write (authenticated), Indicate
    s_admin.setProperties(CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_admin.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_admin.setFixedLen(0);
    s_admin.setWriteCallback(onAdminWrite);
    s_admin.begin();
}

void MashService::notifyDeviceStatus(const uint8_t* data, uint16_t len) {
    s_status.notify(data, len);
}

void MashService::notifyMeshRx(const uint8_t* data, uint16_t len) {
    s_mesh_rx.notify(data, len);
}

void MashService::indicateConfigResponse(const uint8_t* data, uint16_t len) {
    s_config_rw.indicate(data, len);
}

void MashService::indicateAdminResponse(const uint8_t* data, uint16_t len) {
    s_admin.indicate(data, len);
}

} // namespace ble
} // namespace mash
