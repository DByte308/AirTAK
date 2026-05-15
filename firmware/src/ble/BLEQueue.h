// AirTAK - Airsoft Team Awareness Kit
// firmware/src/ble/BLEQueue.h - BLE task queue message tags

#pragma once

#include <cstdint>

namespace airtak {
namespace ble {

constexpr uint8_t BLE_QUEUE_MESH_RX       = 0x01;
constexpr uint8_t BLE_QUEUE_DEVICE_STATUS = 0x02;
constexpr uint8_t BLE_QUEUE_ADMIN         = 0x03;
constexpr uint8_t BLE_QUEUE_CONFIG        = 0x04;

} // namespace ble
} // namespace airtak