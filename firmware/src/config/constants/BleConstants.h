// AirTAK — Airsoft Team Awareness Kit
// BLE Constants — Framing, Queue, Assembly, and Device Name Sizes
#pragma once
#include <cstdint>
#include <cstddef>

namespace airtak {

// ── BLE Framing (§9.5) ──────────────────────────────────────────────────────

/// Size of the BLE frame header: type(1) + request_id(1) + remaining(2) + payload_len(2) = 6
constexpr size_t   BLE_FRAME_HEADER_SIZE      = 6;

/// Maximum BLE MTU negotiated by the stack (§9.5)
constexpr uint16_t BLE_MTU                    = 517;

/// GATT attribute protocol overhead: opcode(1) + handle(2) = 3
constexpr size_t   BLE_ATT_OVERHEAD           = 3;

/// Maximum payload bytes per GATT write: MTU − ATT overhead − safety margin
constexpr size_t   BLE_FRAME_CHUNK_SIZE       = 508;

/// Maximum total BLE frame length usable per write
constexpr size_t   BLE_FRAME_MAX_LEN          = 514;

// ── BLE Queue Type Flags ────────────────────────────────────────────────────

/// Mesh RX frame queue type identifier
constexpr uint8_t  BLE_QUEUE_MESH_RX          = 0x01;

/// Device status notification queue type identifier
constexpr uint8_t  BLE_QUEUE_DEVICE_STATUS    = 0x02;

/// Admin command queue type identifier
constexpr uint8_t  BLE_QUEUE_ADMIN            = 0x03;

/// Configuration change queue type identifier
constexpr uint8_t  BLE_QUEUE_CONFIG           = 0x04;

// ── BLE Assembly Buffer Sizes ───────────────────────────────────────────────

/// Assembly buffer size for incoming ConfigPayload frames
constexpr size_t   BLE_CONFIG_ASSEMBLY_SIZE   = 640;

/// Assembly buffer size for incoming AdminRequest frames
constexpr size_t   BLE_ADMIN_ASSEMBLY_SIZE    = 128;

/// Assembly buffer size for OTA command reassembly
constexpr size_t   BLE_OTA_CMD_ASSEMBLY_SIZE  = 128;

// ── BLE Device Long/Short Name Buffer Sizes ─────────────────────────────────

/// Maximum bytes for the device long name (per ConfigManager)
constexpr size_t   BLE_DEVICE_LONG_NAME_MAX   = 65;

/// Maximum bytes for the device short name (per ConfigManager)
constexpr size_t   BLE_DEVICE_SHORT_NAME_MAX  = 8;

// ── BLE GATT UUIDs (128-bit, Meshtastic-compatible) ──────────────────────
// Service UUID: 4d415348-0000-1000-8000-00805f9b34fb  ("MASH" = 0x4d415348)
constexpr const char* BLE_SERVICE_UUID            = "4d415348-0000-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_CONFIG_RW_UUID     = "4d415348-0001-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_MESH_TX_UUID       = "4d415348-0002-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_MESH_RX_UUID       = "4d415348-0003-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_DEVICE_STATUS_UUID = "4d415348-0004-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_ADMIN_UUID         = "4d415348-0005-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_OTA_COMMAND_UUID   = "4d415348-0006-1000-8000-00805f9b34fb";
constexpr const char* BLE_CHAR_OTA_DATA_UUID      = "4d415348-0007-1000-8000-00805f9b34fb";

// ── BLE Connection Parameters ────────────────────────────────────────────
constexpr uint16_t BLE_CONN_MIN_INTERVAL         = 6;      // ×1.25ms = 7.5ms
constexpr uint16_t BLE_CONN_MAX_INTERVAL         = 24;     // ×1.25ms = 30ms
constexpr uint16_t BLE_CONN_SUPERVISION_TIMEOUT  = 300;    // ×10ms = 3000ms
constexpr uint8_t  BLE_TX_POWER_DBM_NRF52        = 4;      // +4 dBm for nRF52
constexpr uint8_t  BLE_ADV_INTERVAL_MS           = 160;    // ×0.625ms = 100ms advertising interval
constexpr uint8_t  BLE_FAST_ADV_TIMEOUT_S        = 30;     // 30 seconds fast advertising timeout
constexpr uint16_t BLE_ADV_MIN_INTERVAL_RAW      = 0x06;   // ×0.625ms = 3.75ms
constexpr uint16_t BLE_ADV_MAX_INTERVAL_RAW      = 0x12;   // ×0.625ms = 11.25ms
constexpr uint32_t BLE_DEVICE_STATUS_INTERVAL_MS = 5000;   // 5s throttle between device status notifications
constexpr size_t   BLE_STATUS_BUFFER_SIZE        = 256;    // Protobuf device status buffer
constexpr uint8_t  BLE_PASSKEY_DIGIT_COUNT       = 6;      // BLE passkey digit count
constexpr uint8_t  BLE_TX_POWER_ESP32            = 9;      // ESP_PWR_LVL_P9 = +9 dBm (ESP32)

} // namespace airtak
