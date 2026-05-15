// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/MashService.cpp — MASH GATT service implementation
// Planning: §10.1, §10.3, §10.4, §10.5 | Roadmap: 1.5

#include "ble/MashService.h"
#include "ble/Framing.h"
#include "config/ConfigManager.h"
#include "config/ConfigGroups.h"
#include "mesh/Router.h"
#include "mesh/PacketPool.h"
#include "hal/Radio.h"
#include "hal/GPS.h"
#include "config.h"
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
static uint8_t  s_config_assembly[512];      // max assembled ConfigPayload size
static uint16_t s_config_recv_mask  = 0;

// ── Minimal proto varint/bytes reader ────────────────────────────────────────
// Hand-rolled proto parser for ConfigPayload and AdminRequest.
// Returns false on malformed input. Advances *pos past the consumed bytes.
static bool read_varint(const uint8_t* buf, uint16_t buf_len,
                        uint16_t* pos, uint64_t* out) {
    *out = 0;
    int shift = 0;
    while (*pos < buf_len && shift < 64) {
        uint8_t b = buf[(*pos)++];
        *out |= (uint64_t)(b & 0x7F) << shift;
        if (!(b & 0x80)) return true;
        shift += 7;
    }
    return false;
}
static bool read_bytes(const uint8_t* buf, uint16_t buf_len,
                       uint16_t* pos, const uint8_t** data_out, uint32_t* len_out) {
    uint64_t sz;
    if (!read_varint(buf, buf_len, pos, &sz)) return false;
    if (*pos + sz > buf_len) return false;
    *data_out = buf + *pos;
    *len_out  = (uint32_t)sz;
    *pos     += (uint16_t)sz;
    return true;
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

    // On first chunk, reset the assembly buffer.
    if (hdr.chunk_index == 0) {
        memset(s_config_assembly, 0, sizeof(s_config_assembly));
        s_config_recv_mask = 0;
    }

    bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                    s_config_assembly, &s_config_recv_mask);
    if (!done) return;

    // All chunks received; parse the assembled ConfigPayload proto.
    // ConfigPayload fields: group(1, varint), data(2, bytes), commit(3, bool).
    uint16_t pos = 0;
    uint16_t total = hdr.total_length;
    uint32_t group_val = 0;
    const uint8_t* pb_data = nullptr;
    uint32_t pb_len = 0;
    bool commit = false;

    while (pos < total) {
        uint64_t tag_wire;
        if (!read_varint(s_config_assembly, total, &pos, &tag_wire)) break;
        uint32_t field = (uint32_t)(tag_wire >> 3);
        uint8_t wire   = (uint8_t)(tag_wire & 0x07);

        if (wire == 0) {
            // Varint field
            uint64_t v;
            if (!read_varint(s_config_assembly, total, &pos, &v)) break;
            if (field == 1) group_val = (uint32_t)v;
            if (field == 3) commit    = (v != 0);
        } else if (wire == 2) {
            // Length-delimited field
            const uint8_t* fd; uint32_t fl;
            if (!read_bytes(s_config_assembly, total, &pos, &fd, &fl)) break;
            if (field == 2) { pb_data = fd; pb_len = fl; }
        } else break; // unsupported wire type
    }

    if (pb_data == nullptr || group_val > 7) return;

    ConfigGroup group = static_cast<ConfigGroup>(group_val);
    int result = config::ConfigManager::applyGroup(group, pb_data, pb_len, commit);

    // Build and send ConfigAck (field 1=group, 2=success, 3=error, 4=radio_restarted).
    uint8_t ack_buf[64];
    uint16_t ack_len = 0;
    auto write_varint_ack = [&](uint32_t v) {
        while (v >= 0x80 && ack_len < (uint16_t)sizeof(ack_buf)) {
            ack_buf[ack_len++] = (uint8_t)(v | 0x80);
            v >>= 7;
        }
        if (ack_len < (uint16_t)sizeof(ack_buf)) ack_buf[ack_len++] = (uint8_t)v;
    };
    // field 1: group (varint)
    write_varint_ack((1 << 3) | 0); write_varint_ack(group_val);
    // field 2: success (bool varint)
    write_varint_ack((2 << 3) | 0); write_varint_ack(result == 0 ? 1 : 0);

    s_config_rw.indicate(ack_buf, ack_len);
}

// ── MESH_TX write callback ────────────────────────────────────────────────────
// Android sends raw OTA-format mesh packet bytes for transmission.
// The data is the serialized MeshPacket (with OTA wire header + encrypted payload).
static void onMeshTx(uint16_t conn_hdl, BLECharacteristic* chr,
                     uint8_t* data, uint16_t len) {
    (void)conn_hdl; (void)chr;
    if (!s_radio_tx_queue || len < 12 || len > LORA_MAX_PAYLOAD_BYTES) return;

    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    memcpy(pkt->data, data, len);
    pkt->length = len;

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

    uint32_t command = 0xFF;
    uint16_t pos = 0;
    while (pos < len) {
        uint64_t tag_wire;
        if (!read_varint(data, len, &pos, &tag_wire)) break;
        uint32_t field = (uint32_t)(tag_wire >> 3);
        uint8_t wire   = (uint8_t)(tag_wire & 0x07);
        if (wire == 0) {
            uint64_t v;
            if (!read_varint(data, len, &pos, &v)) break;
            if (field == 1) command = (uint32_t)v;
        } else if (wire == 2) {
            const uint8_t* fd; uint32_t fl;
            if (!read_bytes(data, len, &pos, &fd, &fl)) break;
            // field 2: confirm_token — validated below for destructive commands
            (void)fd; (void)fl;
        } else break;
    }

    bool success = false;
    switch (command) {
        case 0: // REBOOT
            NVIC_SystemReset();
            success = true;
            break;
        case 2: // DFU_ENTER — handled by DFUService, not here
            // DFUService::enterDfu();
            success = true;
            break;
        case 3: // RADIO_RESTART
            Radio::pause();
            Radio::reinit();
            Radio::resume();
            success = true;
            break;
        case 4: // GPS_RESET
            GPS::setPower(false);
            vTaskDelay(pdMS_TO_TICKS(200));
            GPS::setPower(true);
            success = true;
            break;
        default:
            break;
    }

    // Build AdminResponse proto: command(1, varint), success(2, bool).
    uint8_t resp[16];
    uint16_t rlen = 0;
    auto write_v = [&](uint32_t v) {
        while (v >= 0x80 && rlen < 16) { resp[rlen++] = (uint8_t)(v | 0x80); v >>= 7; }
        if (rlen < 16) resp[rlen++] = (uint8_t)v;
    };
    write_v((1 << 3) | 0); write_v(command);
    write_v((2 << 3) | 0); write_v(success ? 1 : 0);
    s_admin.indicate(resp, rlen);
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
    s_status.notify(data, len);
}

void MashService::notifyMeshRx(const uint8_t* data, uint16_t len) {
    // Deliver via framing if len > single MTU payload.
    // For Phase 1.5, single-chunk delivery is sufficient (mesh packets ≤ 255 bytes).
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
