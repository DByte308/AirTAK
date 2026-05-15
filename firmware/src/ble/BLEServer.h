// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ble/BLEServer.h — GATT server lifecycle and security
// Planning: §9.7, §10.6 | Roadmap: 1.5

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>
#include "mesh/PacketPool.h"

namespace airtak {
namespace ble {

class BLEServer {
public:
    // Phase 1.5: Initialize the platform BLE stack, register AirTAK and DFU services,
    // configure LE Secure Connections pairing, begin advertising.
    static void init(QueueHandle_t mesh_rx_queue);

    // Phase 1.5: Main BLE task loop body — drain BLE TX queue and run tick().
    static void process(QueueHandle_t ble_tx_queue, QueueHandle_t config_queue);

    // Phase 1.5: Tick — called from process(); sends DEVICE_STATUS every 5 s.
    static void tick();

    // Returns true if a BLE central is currently connected.
    static bool isConnected();

    // Returns the connection handle of the current connection, or 0xFF if none.
    static uint16_t connectionHandle();

    // Force disconnect the current central (used by AdminCommand::DISCONNECT).
    static void disconnect();

    // Passcode displayed on E-Ink for Numeric Comparison pairing (§10.6).
    static uint32_t pendingPasscode();
    static bool     hasPendingPasscode();
};

} // namespace ble
} // namespace airtak
