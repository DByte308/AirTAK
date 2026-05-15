// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/MashService.cpp — MASH GATT service implementation
// Planning: §10.1, §10.3, §10.4, §10.5 | Roadmap: 1.5

#include "ble/MashService.h"
#include "ble/DFUService.h"
#include "ble/Framing.h"
#include "config/ConfigManager.h"
#include "config/ConfigGroups.h"
#include "mesh/Router.h"
#include "mesh/PacketPool.h"
#include "hal/Radio.h"
#include "hal/GPS.h"
#include "config.h"
#include "mash/ble.pb.h"
#include <pb_encode.h>
#include <pb_decode.h>
#include <cstring>
#include <Arduino.h>

extern EventGroupHandle_t g_radio_event_group;

namespace mash {
namespace ble {

static BLEService        s_svc       (MASH_SERVICE_UUID);
static BLECharacteristic s_config_rw (CHAR_CONFIG_RW_UUID);
static BLECharacteristic s_mesh_tx   (CHAR_MESH_TX_UUID);
static BLECharacteristic s_mesh_rx   (CHAR_MESH_RX_UUID);
static BLECharacteristic s_status    (CHAR_DEVICE_STATUS_UUID);
static BLECharacteristic s_admin     (CHAR_ADMIN_UUID);

static QueueHandle_t    s_mesh_rx_queue = nullptr;
static QueueHandle_t    s_radio_tx_queue = nullptr; // set by MeshNode via setTxQueue()

// ── Config reassembler state ─────────────────────────────────────────────────
// Supports up to 8 concurrent fragment streams (one per connection, simplified here
// to a single stream since MASH supports only one BLE connection).
static uint8_t  s_config_assembly[640];      // 512-byte config payload plus ConfigPayload envelope
static uint64_t s_config_recv_mask  = 0;
static uint8_t  s_mesh_tx_assembly[LORA_MAX_PAYLOAD_BYTES];
static uint64_t s_mesh_tx_recv_mask = 0;
static uint8_t  s_admin_assembly[128];
static uint64_t s_admin_recv_mask = 0;

static void sendFramed(BLECharacteristic& chr, const uint8_t* payload, uint16_t len, bool indicate) {
    uint16_t chunks = Framing::chunkCount(len);
    uint8_t frame[BLE_MTU];
    for (uint16_t i = 0; i < chunks; i++) {
        size_t frame_len = Framing::encodeChunk(payload, len, i, frame, sizeof(frame));
        if (frame_len == 0) return;
        if (indicate) chr.indicate(frame, static_cast<uint16_t>(frame_len));
        else chr.notify(frame, static_cast<uint16_t>(frame_len));
    }
}

// ── CONFIG_RW write callback ──────────────────────────────────────────────────
// Android writes chunked frames; we reassemble, then parse ConfigPayload proto.
static void onConfigWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                          uint8_t* data, uint16_t len) {
    (void)conn_hdl; (void)chr;

    FrameHeader hdr;
    const uint8_t* chunk_payload;
    size_t chunk_len;
    if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
    if (hdr.total_length > sizeof(s_config_assembly)) return;

    // On first chunk, reset the assembly buffer.
    if (hdr.chunk_index == 0) {
        memset(s_config_assembly, 0, sizeof(s_config_assembly));
        s_config_recv_mask = 0;
    }

    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_config_assembly, &s_config_recv_mask);
    if (!done) return;

    mash_ConfigPayload request = mash_ConfigPayload_init_zero;
    pb_istream_t in_stream = pb_istream_from_buffer(s_config_assembly, hdr.total_length);
    if (!pb_decode(&in_stream, mash_ConfigPayload_fields, &request)) return;
    if (request.group > mash_ConfigGroup_AUDIO) return;

    ConfigGroup group = static_cast<ConfigGroup>(request.group);
    int result = config::ConfigManager::applyGroup(group,
        request.data.bytes, request.data.size, request.commit);

    mash_ConfigAck ack = mash_ConfigAck_init_zero;
    ack.group = request.group;
    ack.success = (result == 0);
    ack.radio_restarted = request.commit && config::groupRequiresRadioRestart(group);
    if (!ack.success) {
        strncpy(ack.error, "invalid_config_payload", sizeof(ack.error) - 1);
    }

    uint8_t ack_buf[128];
    pb_ostream_t out_stream = pb_ostream_from_buffer(ack_buf, sizeof(ack_buf));
    if (pb_encode(&out_stream, mash_ConfigAck_fields, &ack)) {
        sendFramed(s_config_rw, ack_buf, static_cast<uint16_t>(out_stream.bytes_written), true);
    }
}

// ── MESH_TX write callback ────────────────────────────────────────────────────
// Android sends raw OTA-format mesh packet bytes for transmission.
// The data is the serialized MeshPacket (with OTA wire header + encrypted payload).
static void onMeshTx(uint16_t conn_hdl, BLECharacteristic* chr,
                     uint8_t* data, uint16_t len) {
    (void)conn_hdl; (void)chr;
    if (!s_radio_tx_queue) return;

    FrameHeader hdr;
    const uint8_t* chunk_payload;
    size_t chunk_len;
    if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
    if (hdr.total_length < 12 || hdr.total_length > sizeof(s_mesh_tx_assembly)) return;
    if (hdr.chunk_index == 0) {
        memset(s_mesh_tx_assembly, 0, sizeof(s_mesh_tx_assembly));
        s_mesh_tx_recv_mask = 0;
    }
    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_mesh_tx_assembly, &s_mesh_tx_recv_mask);
    if (!done) return;

    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    memcpy(pkt->data, s_mesh_tx_assembly, hdr.total_length);
    pkt->length = hdr.total_length;

    // Signal Radio task that a TX packet is ready.
    if (xQueueSendToBack(s_radio_tx_queue, &pkt, 0) != pdTRUE) {
        PacketPool::free(pkt);
    } else {
        xEventGroupSetBits(::g_radio_event_group, RADIO_EVENT_TX_READY);
    }
}

// ── ADMIN write callback ──────────────────────────────────────────────────────
// Receives AdminRequest proto: command(1, varint), confirm_token(2, string).
static void onAdminWrite(uint16_t conn_hdl, BLECharacteristic* chr,
                         uint8_t* data, uint16_t len) {
    (void)conn_hdl; (void)chr;

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

    mash_AdminRequest request = mash_AdminRequest_init_zero;
    pb_istream_t in_stream = pb_istream_from_buffer(s_admin_assembly, hdr.total_length);
    if (!pb_decode(&in_stream, mash_AdminRequest_fields, &request)) return;

    bool success = false;
    switch (request.command) {
        case mash_AdminCommand_REBOOT:
            NVIC_SystemReset();
            success = true;
            break;
        case mash_AdminCommand_DFU_ENTER:
            DFUService::enterDfu();
            success = true;
            break;
        case mash_AdminCommand_RADIO_RESTART:
            Radio::pause();
            Radio::reinit();
            Radio::resume();
            success = true;
            break;
        case mash_AdminCommand_GPS_RESET:
            GPS::setPower(false);
            vTaskDelay(pdMS_TO_TICKS(200));
            GPS::setPower(true);
            success = true;
            break;
        default:
            break;
    }

    mash_AdminResponse response = mash_AdminResponse_init_zero;
    response.command = request.command;
    response.success = success;
    if (!success) {
        strncpy(response.error, "unsupported_admin_command", sizeof(response.error) - 1);
    }

    uint8_t resp[128];
    pb_ostream_t out_stream = pb_ostream_from_buffer(resp, sizeof(resp));
    if (pb_encode(&out_stream, mash_AdminResponse_fields, &response)) {
        sendFramed(s_admin, resp, static_cast<uint16_t>(out_stream.bytes_written), true);
    }
}

// ── MashService::init() ──────────────────────────────────────────────────────
void MashService::init(QueueHandle_t mesh_rx_queue) {
    s_mesh_rx_queue = mesh_rx_queue;

    s_svc.begin();

    // CONFIG_RW: Read + Write + Indicate (authenticated).
    s_config_rw.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_config_rw.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_ENC_WITH_MITM);
    s_config_rw.setMaxLen(520);
    s_config_rw.setWriteCallback(onConfigWrite);
    s_config_rw.begin();

    // MESH_TX: Write without response (authenticated).
    s_mesh_tx.setProperties(CHR_PROPS_WRITE_WO_RESP);
    s_mesh_tx.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_mesh_tx.setMaxLen(LORA_MAX_PAYLOAD_BYTES);
    s_mesh_tx.setWriteCallback(onMeshTx);
    s_mesh_tx.begin();

    // MESH_RX: Notify (authenticated).
    s_mesh_rx.setProperties(CHR_PROPS_NOTIFY);
    s_mesh_rx.setPermission(SECMODE_ENC_WITH_MITM, SECMODE_NO_ACCESS);
    s_mesh_rx.setMaxLen(520);
    s_mesh_rx.begin();

    // DEVICE_STATUS: Read + Notify (open — no auth required per §10.4).
    s_status.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
    s_status.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
    s_status.setMaxLen(256);
    s_status.begin();

    // ADMIN: Write + Indicate (authenticated).
    s_admin.setProperties(CHR_PROPS_WRITE | CHR_PROPS_INDICATE);
    s_admin.setPermission(SECMODE_NO_ACCESS, SECMODE_ENC_WITH_MITM);
    s_admin.setMaxLen(128);
    s_admin.setWriteCallback(onAdminWrite);
    s_admin.begin();
}

void MashService::setRadioTxQueue(QueueHandle_t q) { s_radio_tx_queue = q; }

void MashService::notifyDeviceStatus(const uint8_t* data, uint16_t len) {
    sendFramed(s_status, data, len, false);
}

void MashService::notifyMeshRx(const uint8_t* data, uint16_t len) {
    sendFramed(s_mesh_rx, data, len, false);
}

void MashService::indicateConfigResponse(const uint8_t* data, uint16_t len) {
    sendFramed(s_config_rw, data, len, true);
}

void MashService::indicateAdminResponse(const uint8_t* data, uint16_t len) {
    sendFramed(s_admin, data, len, true);
}

} // namespace ble
} // namespace mash
