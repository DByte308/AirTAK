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

} // namespace airtak
