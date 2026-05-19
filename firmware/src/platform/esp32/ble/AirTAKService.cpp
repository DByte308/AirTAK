// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/ble/AirTAKService.cpp — NimBLE AirTAK GATT service
// Planning: §10.1, §10.3, §10.4, §10.5 | Roadmap: 1.5
//
// ESP32 NimBLE port of the nRF52/Bluefruit AirTAKService.  Implements all five
// GATT characteristics with identical framing, security semantics, and application
// logic as the nRF52 version.
//
// Characteristic security mapping (§10.1):
//   CONFIG_RW      — read+write+indicate, ENC_WITH_MITM on both
//   MESH_TX        — write-without-response, ENC_WITH_MITM write
//   MESH_RX        — notify, ENC_WITH_MITM read
//   DEVICE_STATUS  — read+notify, open (no auth required)
//   ADMIN          — write+indicate, ENC_WITH_MITM on both

#include <Arduino.h>
#include <NimBLEDevice.h>
// NimBLE-Arduino v1.4 compat macros clash with our class names -- undef them.
#ifdef BLECharacteristic
#undef BLECharacteristic
#endif
#ifdef BLEService
#undef BLEService
#endif
#ifdef BLEServer
#undef BLEServer
#endif
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
#include <cstring>
#include <esp_system.h>

#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
#include "ota/OtaService.h"
#endif

extern QueueHandle_t g_ble_tx_queue;
extern EventGroupHandle_t g_radio_event_group;

namespace airtak {
namespace ble {

// ── Static characteristic pointers ───────────────────────────────────────────
static NimBLECharacteristic* s_config_rw = nullptr;
static NimBLECharacteristic* s_mesh_tx   = nullptr;
static NimBLECharacteristic* s_mesh_rx   = nullptr;
static NimBLECharacteristic* s_status    = nullptr;
static NimBLECharacteristic* s_admin     = nullptr;
#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
static NimBLECharacteristic* s_ota_cmd   = nullptr;
static NimBLECharacteristic* s_ota_data  = nullptr;
#endif

static QueueHandle_t    s_mesh_rx_queue   = nullptr;
static QueueHandle_t    s_radio_tx_queue  = nullptr;

// ── Reassembly buffers ────────────────────────────────────────────────────────
static uint8_t  s_config_assembly[640];
static uint64_t s_config_recv_mask        = 0;
static uint8_t  s_mesh_tx_assembly[LORA_MAX_PAYLOAD_BYTES];
static uint64_t s_mesh_tx_recv_mask       = 0;
static uint8_t  s_admin_assembly[128];
static uint64_t s_admin_recv_mask         = 0;
static uint64_t s_config_apply_started_ms = 0;
static volatile bool s_pending_config_restart = false;
static airtak_ConfigGroup s_pending_config_group = airtak_ConfigGroup_DEVICE;
static uint64_t s_pending_config_started_ms = 0;

#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
static uint8_t  s_ota_cmd_assembly[128];
static uint64_t s_ota_cmd_recv_mask = 0;
#endif

// ── Helpers ───────────────────────────────────────────────────────────────────

static const char* configErrorForResult(int result) {
    switch (result) {
        case -1: return "invalid_config_group";
        case -4: return "config_persist_failed";
        case -5: return "radio_restart_queue_failed";
        default: return "invalid_config_payload";
    }
}

static void sendFramed(NimBLECharacteristic* chr, const uint8_t* payload,
                       uint16_t len, bool indicate) {
    if (!chr) return;
    uint16_t chunks = Framing::chunkCount(len);
    uint8_t frame[BLE_FRAME_MAX_LEN];
    for (uint16_t i = 0; i < chunks; i++) {
        size_t frame_len = Framing::encodeChunk(payload, len, i, frame, sizeof(frame));
        if (frame_len == 0) return;
        chr->setValue(frame, static_cast<uint16_t>(frame_len));
        if (indicate) chr->indicate();
        else          chr->notify();
    }
}

static void enqueueConfigAck(airtak_ConfigGroup group, bool success,
                              bool radio_restarted, uint32_t apply_duration_ms,
                              const char* error) {
    airtak_ConfigAck ack = airtak_ConfigAck_init_zero;
    ack.group             = group;
    ack.success           = success;
    ack.radio_restarted   = radio_restarted;
    ack.apply_duration_ms = apply_duration_ms;
    if (!success && error) strncpy(ack.error, error, sizeof(ack.error) - 1);

    uint8_t ack_buf[128];
    pb_ostream_t out_stream = pb_ostream_from_buffer(ack_buf, sizeof(ack_buf));
    if (!pb_encode(&out_stream, airtak_ConfigAck_fields, &ack)) return;

    if (!g_ble_tx_queue) {
        sendFramed(s_config_rw, ack_buf,
                   static_cast<uint16_t>(out_stream.bytes_written), true);
        return;
    }
    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    pkt->data[0] = BLE_QUEUE_CONFIG;
    memcpy(pkt->data + 1, ack_buf, out_stream.bytes_written);
    pkt->length = static_cast<uint16_t>(1 + out_stream.bytes_written);
    if (xQueueSendToBack(g_ble_tx_queue, &pkt, 0) != pdTRUE) PacketPool::free(pkt);
}

// ── Characteristic callbacks ──────────────────────────────────────────────────

class ConfigRwCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr) override {
        std::string val = chr->getValue();
        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        uint16_t len = static_cast<uint16_t>(val.size());

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

        // Read request (empty data, no commit)
        if (request.data.size == 0 && !request.commit) {
            airtak_ConfigPayload response = {};
            response.group = request.group;
            response.data.size = config::ConfigManager::readGroup(
                group, response.data.bytes, sizeof(response.data.bytes));
            response.commit = false;
            uint8_t resp_buf[sizeof(s_config_assembly)];
            pb_ostream_t rs = pb_ostream_from_buffer(resp_buf, sizeof(resp_buf));
            if (pb_encode(&rs, airtak_ConfigPayload_fields, &response)) {
                sendFramed(s_config_rw, resp_buf,
                           static_cast<uint16_t>(rs.bytes_written), true);
            }
            return;
        }

        s_config_apply_started_ms = millis();
        int result = config::ConfigManager::applyGroup(
            group, request.data.bytes, request.data.size, request.commit);

        if (result == 0 && request.commit &&
            config::ConfigManager::isRadioRestartPending()) {
            s_pending_config_restart      = true;
            s_pending_config_group        = request.group;
            s_pending_config_started_ms   = s_config_apply_started_ms;
            return;
        }

        enqueueConfigAck(request.group, result == 0, false,
                          millis() - s_config_apply_started_ms,
                          result == 0 ? nullptr : configErrorForResult(result));
    }
};

class MeshTxCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr) override {
        if (!s_radio_tx_queue) return;
        std::string val = chr->getValue();
        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        uint16_t len = static_cast<uint16_t>(val.size());

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

        // Route through the Router — never bypass to send raw bytes unencrypted
        Router::sendDataPacket(s_mesh_tx_assembly, hdr.total_length, s_radio_tx_queue);
    }
};

class AdminCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr) override {
        std::string val = chr->getValue();
        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        uint16_t len = static_cast<uint16_t>(val.size());

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
                esp_restart();  // ESP32 equivalent of NVIC_SystemReset()
                success = true;
                break;
            case airtak_AdminCommand_DFU_ENTER:
                DFUService::enterDfu();
                success = true;
                break;
#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
            case airtak_AdminCommand_OTA_ENTER:
                // OTA_ENTER via admin channel is a trigger for the ESP32
                // to prepare for OTA.  Actual data transfer uses OTA_COMMAND
                // and OTA_DATA characteristics.  Just ack.
                success = true;
                break;
            case airtak_AdminCommand_OTA_FINISH:
                // Not used — OTA_COMMAND characteristic handles finish().
                // Respond success for protocol compatibility.
                success = true;
                break;
#endif
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
            strncpy(response.error, "unsupported_admin_command",
                    sizeof(response.error) - 1);
        }
        uint8_t resp[128];
        pb_ostream_t out_stream = pb_ostream_from_buffer(resp, sizeof(resp));
        if (pb_encode(&out_stream, airtak_AdminResponse_fields, &response)) {
            sendFramed(s_admin, resp,
                       static_cast<uint16_t>(out_stream.bytes_written), true);
        }
    }
};

#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
class OtaCommandCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr) override {
        std::string val = chr->getValue();
        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        uint16_t len = static_cast<uint16_t>(val.size());

        FrameHeader hdr;
        const uint8_t* chunk_payload;
        size_t chunk_len;
        if (!Framing::decodeChunk(data, len, &hdr, &chunk_payload, &chunk_len)) return;
        if (hdr.total_length > sizeof(s_ota_cmd_assembly)) return;
        if (hdr.chunk_index == 0) {
            memset(s_ota_cmd_assembly, 0, sizeof(s_ota_cmd_assembly));
            s_ota_cmd_recv_mask = 0;
        }
        bool done = Framing::reassemble(hdr, chunk_payload, chunk_len,
                                        s_ota_cmd_assembly, &s_ota_cmd_recv_mask);
        if (!done) return;

        airtak_OtaCommand cmd = airtak_OtaCommand_init_zero;
        pb_istream_t in_stream = pb_istream_from_buffer(s_ota_cmd_assembly, hdr.total_length);
        if (!pb_decode(&in_stream, airtak_OtaCommand_fields, &cmd)) return;

        airtak_OtaStatus status_msg = airtak_OtaStatus_init_zero;

        if (cmd.which_command == airtak_OtaCommand_start_tag) {
            bool ok = ota::OtaService::begin(
                cmd.command.start.image_size,
                cmd.command.start.crc32);
            status_msg.state = ok ? airtak_OtaState_OTA_RECEIVING
                                  : airtak_OtaState_OTA_ERROR;
            status_msg.total_size = cmd.command.start.image_size;
        } else if (cmd.which_command == airtak_OtaCommand_finish_tag) {
            bool ok = ota::OtaService::finish(cmd.command.finish.crc32);
            status_msg.state = ok ? airtak_OtaState_OTA_COMPLETE
                                  : airtak_OtaState_OTA_ERROR;
        } else if (cmd.which_command == airtak_OtaCommand_status_tag) {
            auto s = ota::OtaService::state();
            status_msg.state         = static_cast<airtak_OtaState>(static_cast<uint8_t>(s));
            status_msg.bytes_written = ota::OtaService::bytesWritten();
            status_msg.total_size    = ota::OtaService::totalSize();
        } else if (cmd.which_command == airtak_OtaCommand_cancel_tag) {
            ota::OtaService::cancel();
            status_msg.state = airtak_OtaState_OTA_IDLE;
        }

        uint8_t buf[128];
        pb_ostream_t out = pb_ostream_from_buffer(buf, sizeof(buf));
        if (pb_encode(&out, airtak_OtaStatus_fields, &status_msg)) {
            sendFramed(s_ota_cmd, buf,
                       static_cast<uint16_t>(out.bytes_written), true);
        }
    }
};

class OtaDataCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* chr) override {
        std::string val = chr->getValue();
        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        size_t len = val.size();
        ota::OtaService::writeChunk(data, len);
    }
};
#endif

// ── Public API ────────────────────────────────────────────────────────────────

void AirTAKService::init(QueueHandle_t mesh_rx_queue) {
    s_mesh_rx_queue = mesh_rx_queue;

    NimBLEServer* pServer = NimBLEDevice::getServer();
    NimBLEService* pSvc   = pServer->createService(AIRTAK_SERVICE_UUID);

    // CONFIG_RW — read/write/indicate, authenticated (ENC_WITH_MITM)
    s_config_rw = pSvc->createCharacteristic(
        CHAR_CONFIG_RW_UUID,
        NIMBLE_PROPERTY::READ  | NIMBLE_PROPERTY::READ_AUTHEN  |
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN |
        NIMBLE_PROPERTY::INDICATE);
    s_config_rw->setCallbacks(new ConfigRwCallbacks());

    // MESH_TX — write-without-response, authenticated write
    s_mesh_tx = pSvc->createCharacteristic(
        CHAR_MESH_TX_UUID,
        NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_AUTHEN);
    s_mesh_tx->setCallbacks(new MeshTxCallbacks());

    // MESH_RX — notify, authenticated read
    s_mesh_rx = pSvc->createCharacteristic(
        CHAR_MESH_RX_UUID,
        NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_AUTHEN);

    // DEVICE_STATUS — read/notify, open (no auth per §10.4)
    s_status = pSvc->createCharacteristic(
        CHAR_DEVICE_STATUS_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

    // ADMIN — write/indicate, authenticated
    s_admin = pSvc->createCharacteristic(
        CHAR_ADMIN_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN |
        NIMBLE_PROPERTY::INDICATE);
    s_admin->setCallbacks(new AdminCallbacks());

#if defined(AIRTAK_BOARD_HAS_BLE_OTA) && AIRTAK_BOARD_HAS_BLE_OTA
    // OTA_COMMAND — write/notify, authenticated (ENC_WITH_MITM)
    s_ota_cmd = pSvc->createCharacteristic(
        CHAR_OTA_COMMAND_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN |
        NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_AUTHEN);
    s_ota_cmd->setCallbacks(new OtaCommandCallbacks());

    // OTA_DATA — write-without-response, authenticated write
    s_ota_data = pSvc->createCharacteristic(
        CHAR_OTA_DATA_UUID,
        NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_AUTHEN);
    s_ota_data->setCallbacks(new OtaDataCallbacks());
#endif

    pSvc->start();
}

void AirTAKService::setRadioTxQueue(QueueHandle_t q) { s_radio_tx_queue = q; }

void AirTAKService::notifyDeviceStatus(const uint8_t* data, uint16_t len) {
    if (!s_status) return;
    // Also set a readable value for polling clients
    uint8_t frame[BLE_FRAME_MAX_LEN];
    size_t frame_len = Framing::encodeChunk(data, len, 0, frame, sizeof(frame));
    if (frame_len > 0) s_status->setValue(frame, static_cast<uint16_t>(frame_len));
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
