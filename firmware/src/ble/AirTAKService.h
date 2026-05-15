// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ble/AirTAKService.h — AirTAK GATT service definition
// Planning: §10.1, §10.3, §10.4, §10.5 | Roadmap: 1.5
//
// AirTAK GATT Service UUID: 4d415348-0000-1000-8000-00805f9b34fb
//
// Characteristics:
//   CONFIG_RW    4d415348-0001-…  Read/Write (authenticated), Indicate  — §10.3
//   MESH_TX      4d415348-0002-…  Write without response (authenticated) — §10.3
//   MESH_RX      4d415348-0003-…  Notify (authenticated)                — §10.3
//   DEVICE_STATUS 4d415348-0004-… Read/Notify (open, no auth required)  — §10.4
//   ADMIN        4d415348-0005-…  Write (authenticated), Indicate       — §10.5
//
// Security mode: LE Secure Connections + Numeric Comparison. Write characteristics
// require Security Mode 1, Level 3 (encrypted + authenticated). DEVICE_STATUS is open.

#pragma once

#include <FreeRTOS.h>
#include <queue.h>

namespace airtak {
namespace ble {

// UUIDs (128-bit, little-endian byte order as required by BLEService)
constexpr char AIRTAK_SERVICE_UUID[]         = "4d415348-0000-1000-8000-00805f9b34fb";
constexpr char CHAR_CONFIG_RW_UUID[]       = "4d415348-0001-1000-8000-00805f9b34fb";
constexpr char CHAR_MESH_TX_UUID[]         = "4d415348-0002-1000-8000-00805f9b34fb";
constexpr char CHAR_MESH_RX_UUID[]         = "4d415348-0003-1000-8000-00805f9b34fb";
constexpr char CHAR_DEVICE_STATUS_UUID[]   = "4d415348-0004-1000-8000-00805f9b34fb";
constexpr char CHAR_ADMIN_UUID[]           = "4d415348-0005-1000-8000-00805f9b34fb";

class AirTAKService {
public:
    // Phase 1.5: Register AirTAK GATT service and all 5 characteristics.
    // Sets up write callbacks and security requirements.
    static void init(QueueHandle_t mesh_rx_queue);

    // Phase 1.5: Notify/indicate the DEVICE_STATUS characteristic with current status bytes.
    static void notifyDeviceStatus(const uint8_t* data, uint16_t len);

    // Phase 1.5: Notify/indicate the MESH_RX characteristic with a received mesh packet.
    static void notifyMeshRx(const uint8_t* data, uint16_t len);

    // Phase 1.5: Indicate CONFIG_RW with a config group response payload.
    static void indicateConfigResponse(const uint8_t* data, uint16_t len);

    // Phase 1.5: Indicate ADMIN with an AdminResponse payload.
    static void indicateAdminResponse(const uint8_t* data, uint16_t len);

    // Set the radio TX queue handle so MESH_TX writes can enqueue packets for the Radio task.
    static void setRadioTxQueue(QueueHandle_t q);
};

} // namespace ble
} // namespace airtak
