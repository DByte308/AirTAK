// AirTAK - Airsoft Team Awareness Kit
// firmware/src/ble/BLEQueue.h - BLE task queue message tags
//
// Provides the internal queue-tag values that the mesh Router, ConfigManager,
// and BLE Server use to route pooled-packet payloads to the correct GATT
// characteristic handler.

#pragma once

#include <cstdint>

namespace airtak {
namespace ble {

constexpr uint8_t BLE_QUEUE_MESH_RX       = 0x01;
constexpr uint8_t BLE_QUEUE_DEVICE_STATUS = 0x02;
constexpr uint8_t BLE_QUEUE_ADMIN         = 0x03;
constexpr uint8_t BLE_QUEUE_CONFIG        = 0x04;

// Queue an AdminResponse with success=false onto g_ble_tx_queue. Used by both
// the mesh Router (delivery failures, TX drops) and ConfigManager (restart
// timeouts) to notify the BLE client of an asynchronous error condition.
void queueAdminError(const char* error);

} // namespace ble
} // namespace airtak