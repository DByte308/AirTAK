// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/BLEServer.h — GATT server lifecycle and security
// Planning: §9.7, §10.6 | Roadmap: 1.5
//
// Manages the Bluefruit GATT server lifecycle: advertising, bonding, connection
// callbacks, LE Secure Connections + Numeric Comparison pairing, MTU 517 negotiation.
//
// SECURITY NOTES:
//   - Pairing mode: LE Secure Connections (§10.6). Numeric Comparison (passkey shown on E-Ink).
//   - Bonding storage: up to 5 bonded Android devices in SoftDevice bond store.
//   - Only 1 concurrent BLE connection supported (single-connection GATT server).
//   - The MashService and DFU service are both registered before advertising starts.

#pragma once

#include <cstdint>
#include <FreeRTOS.h>
#include <queue.h>

namespace mash {
namespace ble {

class BLEServer {
public:
    // Phase 1.5: Initialize Bluefruit, register MASH and DFU services,
    // configure LE Secure Connections pairing, begin advertising.
    static void init(QueueHandle_t mesh_rx_queue);

    // Returns true if a BLE central is currently connected.
    static bool isConnected();

    // Returns the connection handle of the current connection, or 0xFF if none.
    static uint16_t connectionHandle();

    // Force disconnect the current central (used by AdminCommand::DISCONNECT).
    static void disconnect();

    // Phase 1.5: Tick — should be called from task_ble_server loop to handle
    // pending callbacks, keepalive DEVICE_STATUS notifications (every 5 s).
    static void tick();

    // Passcode displayed on E-Ink for Numeric Comparison pairing (§10.6).
    // Set by pairing callback; shown on display until confirmed or timeout.
    static uint32_t pendingPasscode();
    static bool     hasPendingPasscode();
};

} // namespace ble
} // namespace mash
