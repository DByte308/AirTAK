// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/nrf52/ble/AirTAKService.cpp - Bluefruit AirTAK GATT service
// Planning: section 10.1, section 10.3, section 10.4, section 10.5 | Roadmap: 1.5

#include "ble/AirTAKService.h"
#include "ble/DFUService.h"
#include "ble/Framing.h"
#include "ble/BLEQueue.h"
#include "config/ConfigManager.h"
#include "config/ConfigGroups.h"
#include "mesh/ChannelManager.h"
#include "mesh/Router.h"
#include "mesh/PacketPool.h"
#include "hal/Radio.h"
#include "hal/GPS.h"
#include "config.h"
#include "airtak/ble.pb.h"
#include <pb_encode.h>
#include <pb_decode.h>
#include <bluefruit.h>
#include <nrf.h>
#include <Arduino.h>
#include <cstring>

extern EventGroupHandle_t g_radio_event_group;
extern QueueHandle_t g_ble_tx_queue;

namespace airtak {
namespace ble {

static BLEService        s_svc       (AIRTAK_SERVICE_UUID);
static BLECharacteristic s_config_rw (CHAR_CONFIG_RW_UUID);
static BLECharacteristic s_mesh_tx   (CHAR_MESH_TX_UUID);
static BLECharacteristic s_mesh_rx   (CHAR_MESH_RX_UUID);
static BLECharacteristic s_status    (CHAR_DEVICE_STATUS_UUID);
static BLECharacteristic s_admin     (CHAR_ADMIN_UUID);

static QueueHandle_t    s_mesh_rx_queue = nullptr;
static QueueHandle_t    s_radio_tx_queue = nullptr;

static uint8_t  s_config_assembly[640];
static uint64_t s_config_recv_mask  = 0;
static uint8_t  s_mesh_tx_assembly[LORA_MAX_PAYLOAD_BYTES];
static uint64_t s_mesh_tx_recv_mask = 0;
static uint8_t  s_admin_assembly[128];
static uint64_t s_admin_recv_mask = 0;
static uint64_t s_config_apply_started_ms = 0;
static volatile bool s_pending_config_restart = false;
static airtak_ConfigGroup s_pending_config_group = airtak_ConfigGroup_DEVICE;
static uint64_t s_pending_config_started_ms = 0;

static void sendFramed(BLECharacteristic& chr, const uint8_t* payload, uint16_t len, bool indicate);

static const char* configErrorForResult(int result) {
    switch (result) {
        case -1: return "invalid_config_group";
        case -2: return "invalid_config_payload";
        case -3: return "invalid_config_payload";
        case -4: return "config_persist_failed";
        case -5: return "radio_restart_queue_failed";
        default: return "invalid_config_payload";
    }
}

static void enqueueConfigAck(airtak_ConfigGroup group, bool success, bool radio_restarted,
                             uint32_t apply_duration_ms, const char* error) {
    airtak_ConfigAck ack = airtak_ConfigAck_init_zero;
    ack.group = group;
    ack.success = success;
    ack.radio_restarted = radio_restarted;
    ack.apply_duration_ms = apply_duration_ms;
    if (!success && error) {
        strncpy(ack.error, error, sizeof(ack.error) - 1);
    }

    uint8_t ack_buf[128];
    pb_ostream_t out_stream = pb_ostream_from_buffer(ack_buf, sizeof(ack_buf));
    if (!pb_encode(&out_stream, airtak_ConfigAck_fields, &ack)) return;

    if (!::g_ble_tx_queue) {
        sendFramed(s_config_rw, ack_buf, static_cast<uint16_t>(out_stream.bytes_written), true);
        return;
    }

    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    pkt->data[0] = BLE_QUEUE_CONFIG;
    memcpy(pkt->data + 1, ack_buf, out_stream.bytes_written);
    pkt->length = static_cast<uint16_t>(1 + out_stream.bytes_written);
    if (xQueueSendToBack(::g_ble_tx_queue, &pkt, 0) != pdTRUE) {
        PacketPool::free(pkt);
    }
}

static void sendFramed(BLECharacteristic& chr, const uint8_t* payload, uint16_t len, bool indicate) {
    uint16_t chunks = Framing::chunkCount(len);
    uint8_t frame[BLE_FRAME_MAX_LEN];
    for (uint16_t i = 0; i < chunks; i++) {
        size_t frame_len = Framing::encodeChunk(payload, len, i, frame, sizeof(frame));
        if (frame_len == 0) return;
        if (indicate) chr.indicate(frame, static_cast<uint16_t>(frame_len));
        else chr.notify(frame, static_cast<uint16_t>(frame_len));
    }
}

static void onConfigWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                          uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    (void)chr;

    int result = 0;

    FrameHeader hdr;
    const uint8_t* chunk_payload;
    size_t chunk_len;
    if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
    if (hdr.total_length > sizeof(s_config_assembly)) return;

    if (hdr.chunk_index == 0) {
        memset(s_config_assembly, 0, sizeof(s_config_assembly));
        s_config_recv_mask = 0;
    }

    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_config_assembly, &s_config_recv_mask);
    if (!done) return;

    airtak_ConfigPayload request = airtak_ConfigPayload_init_zero;
    pb_istream_t in_stream = pb_istream_from_buffer(s_config_assembly, hdr.total_length);
    if (!pb_decode(&in_stream, airtak_ConfigPayload_fields, &request)) return;
    if (request.group > airtak_ConfigGroup_AUDIO) return;

    ConfigGroup group = static_cast<ConfigGroup>(request.group);

    if (request.data.size == 0 && !request.commit) {
        airtak_ConfigPayload response = {};
        response.group = request.group;
        response.data.size = config::ConfigManager::readGroup(
            group, response.data.bytes, sizeof(response.data.bytes));
        response.commit = false;

        uint8_t response_buf[sizeof(s_config_assembly)];
        pb_ostream_t response_stream = pb_ostream_from_buffer(response_buf, sizeof(response_buf));
        if (pb_encode(&response_stream, airtak_ConfigPayload_fields, &response)) {
            sendFramed(s_config_rw, response_buf, static_cast<uint16_t>(response_stream.bytes_written), true);
        }
        return;
    }

    s_config_apply_started_ms = millis();
    result = config::ConfigManager::applyGroup(group,
        request.data.bytes, request.data.size, request.commit);

    if (result == 0 && request.commit && config::ConfigManager::isRadioRestartPending()) {
        s_pending_config_restart = true;
        s_pending_config_group = request.group;
        s_pending_config_started_ms = s_config_apply_started_ms;
        return;
    }

    enqueueConfigAck(request.group, result == 0, false,
                     millis() - s_config_apply_started_ms,
                     result == 0 ? nullptr : configErrorForResult(result));
}

static void onMeshTx(uint16_t conn_hdl, BLECharacteristic* chr,
                     uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    (void)chr;
    if (!s_radio_tx_queue) return;

    FrameHeader hdr;
    const uint8_t* chunk_payload;
    size_t chunk_len;
    if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
    if (hdr.total_length == 0 || hdr.total_length > sizeof(s_mesh_tx_assembly)) return;
    if (hdr.chunk_index == 0) {
        memset(s_mesh_tx_assembly, 0, sizeof(s_mesh_tx_assembly));
        s_mesh_tx_recv_mask = 0;
    }
    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_mesh_tx_assembly, &s_mesh_tx_recv_mask);
    if (!done) return;

    if (Router::sendDataPacket(s_mesh_tx_assembly, hdr.total_length, s_radio_tx_queue)) {
        return;
    }

    // Router rejected the packet (unparseable Data protobuf, no usable TX slot,
    // or payload too large).  Silently drop — never bypass the Router to send
    // raw bytes unencrypted and without hop-limit enforcement.
}

static void onAdminWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                         uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    (void)chr;

    FrameHeader hdr;
    const uint8_t* chunk_payload;
    size_t chunk_len;
    if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
    if (hdr.total_length > sizeof(s_admin_assembly)) return;
    if (hdr.chunk_index == 0) {
        memset(s_admin_assembly, 0, sizeof(s_admin_assembly));
        s_admin_recv_mask = 0;
    }
    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_admin_assembly, &s_admin_recv_mask);
    if (!done) return;

    airtak_AdminRequest request = airtak_AdminRequest_init_zero;
    pb_istream_t in_stream = pb_istream_from_buffer(s_admin_assembly, hdr.total_length);
    if (!pb_decode(&in_stream, airtak_AdminRequest_fields, &request)) return;

    bool success = false;
    switch (request.command) {
        case airtak_AdminCommand_REBOOT:
            NVIC_SystemReset();
            success = true;
            break;
        case airtak_AdminCommand_DFU_ENTER:
            DFUService::enterDfu();
            success = true;
            break;
        case airtak_AdminCommand_RADIO_RESTART:
            config::ConfigManager::radioRestartProcedure();
            success = true;
            break;
        case airtak_AdminCommand_GPS_RESET:
            GPS::setPower(false);
            vTaskDelay(pdMS_TO_TICKS(200));
            GPS::setPower(true);
            success = true;
            break;
        case airtak_AdminCommand_SET_PHONE_GPS:
            if (request.has_phone_gps && GPS::usingExternalFix()) {
                GPS::setExternalFix(
                    request.phone_gps.latitude,
                    request.phone_gps.longitude,
                    request.phone_gps.altitude_m,
                    request.phone_gps.accuracy_m);
                success = true;
            }
            break;
        default:
            break;
    }

    airtak_AdminResponse response = airtak_AdminResponse_init_zero;
    response.command = request.command;
    response.success = success;
    if (!success) {
        strncpy(response.error, "unsupported_admin_command", sizeof(response.error) - 1);
    }

    uint8_t resp[128];
    pb_ostream_t out_stream = pb_ostream_from_buffer(resp, sizeof(resp));
    if (pb_encode(&out_stream, airtak_AdminResponse_fields, &response)) {
        sendFramed(s_admin, resp, static_cast<uint16_t>(out_stream.bytes_written), true);
    }
}

void AirTAKService::init(QueueHandle_t mesh_rx_queue) {
    s_mesh_rx_queue = mesh_rx_queue;

    s_svc.begin();

    s_config_rw.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_config_rw.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_ENC_WITH_MITM);
    s_config_rw.setMaxLen(BLE_FRAME_MAX_LEN);
    s_config_rw.setWriteCallback(onConfigWrite);
    s_config_rw.begin();

    s_mesh_tx.setProperties(CHR_PROPS_WRITE_WO_RESP);
    s_mesh_tx.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_mesh_tx.setMaxLen(BLE_FRAME_MAX_LEN);
    s_mesh_tx.setWriteCallback(onMeshTx);
    s_mesh_tx.begin();

    s_mesh_rx.setProperties(CHR_PROPS_NOTIFY);
    s_mesh_rx.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_NO_ACCESS);
    s_mesh_rx.setMaxLen(BLE_FRAME_MAX_LEN);
    s_mesh_rx.begin();

    s_status.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
    s_status.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
    s_status.setMaxLen(BLE_FRAME_MAX_LEN);
    s_status.begin();

    s_admin.setProperties(CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_admin.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_admin.setMaxLen(BLE_FRAME_MAX_LEN);
    s_admin.setWriteCallback(onAdminWrite);
    s_admin.begin();
}

void AirTAKService::setRadioTxQueue(QueueHandle_t q) { s_radio_tx_queue = q; }

void AirTAKService::notifyDeviceStatus(const uint8_t* data, uint16_t len) {
    uint8_t frame[BLE_FRAME_MAX_LEN];
    size_t frame_len = Framing::encodeChunk(data, len, 0, frame, sizeof(frame));
    if (frame_len > 0) {
        s_status.write(frame, static_cast<uint16_t>(frame_len));
    }
    sendFramed(s_status, data, len, false);
}

void AirTAKService::notifyMeshRx(const uint8_t* data, uint16_t len) {
    sendFramed(s_mesh_rx, data, len, false);
}

void AirTAKService::indicateConfigResponse(const uint8_t* data, uint16_t len) {
    sendFramed(s_config_rw, data, len, true);
}

void AirTAKService::indicateAdminResponse(const uint8_t* data, uint16_t len) {
    sendFramed(s_admin, data, len, true);
}

void AirTAKService::completePendingConfigRestart(bool success) {
    if (!s_pending_config_restart) return;
    s_pending_config_restart = false;
    enqueueConfigAck(s_pending_config_group, success, success,
                     millis() - s_pending_config_started_ms,
                     success ? nullptr : "radio_restart_failed");
}

} // namespace ble
} // namespace airtak
