// MASH — Mesh Airsoft Situational Hub
// firmware/src/ble/BLEServer.cpp — GATT server lifecycle implementation
// Planning: §9.7, §10.6 | Roadmap: 1.5

#include "ble/BLEServer.h"
#include "ble/MashService.h"
#include "ble/DFUService.h"
#include "hal/Display.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <bluefruit.h>
#include <Arduino.h>

namespace mash {
namespace ble {

static uint16_t s_conn_hdl        = 0xFFFF;
static bool     s_connected       = false;
static uint32_t s_pending_passkey = 0;
static bool     s_has_passkey     = false;

static void onConnect(uint16_t conn_hdl) {
    s_conn_hdl  = conn_hdl;
    s_connected = true;
    Display::markDirty(DISPLAY_DIRTY_BLE);
}

static void onDisconnect(uint16_t conn_hdl, uint8_t reason) {
    (void)reason;
    s_conn_hdl    = 0xFFFF;
    s_connected   = false;
    s_has_passkey = false;
    Display::markDirty(DISPLAY_DIRTY_BLE);
    // Restart advertising after disconnect
    Bluefruit.Advertising.start(0);
}

static void onPasskeyDisplay(uint16_t conn_hdl, uint32_t passkey) {
    (void)conn_hdl;
    s_pending_passkey = passkey;
    s_has_passkey     = true;
    Display::forceRefresh(); // show passkey on E-Ink immediately
}

void BLEServer::init(QueueHandle_t mesh_rx_queue) {
    Bluefruit.begin();
    Bluefruit.setName(BLE_DEVICE_NAME);
    Bluefruit.setTxPower(4); // +4 dBm

    // Request MTU 517 (§10.1)
    Bluefruit.setMtu(BLE_MTU);

    // LE Secure Connections + Numeric Comparison (§10.6)
    Bluefruit.Security.setIOCaps(BLE_GAP_IO_CAPS_DISPLAY_ONLY); // display passkey
    Bluefruit.Security.setMITM(true);
    Bluefruit.Security.setLESC(true);
    Bluefruit.Security.setPasskeyCallback(onPasskeyDisplay);

    Bluefruit.setConnectCallback(onConnect);
    Bluefruit.setDisconnectCallback(onDisconnect);

    // Register services
    MashService::init(mesh_rx_queue);
    DFUService::init();

    // Configure advertising
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addService(/* MASH service UUID */ );
    Bluefruit.Advertising.addName();
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(160, 160); // 100 ms @ 0.625 ms units
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);
}

void BLEServer::tick() {
    // TODO Phase 1.5: Send keepalive DEVICE_STATUS notification every 5 seconds.
}

bool     BLEServer::isConnected()       { return s_connected;       }
uint16_t BLEServer::connectionHandle()  { return s_conn_hdl;        }
uint32_t BLEServer::pendingPasscode()   { return s_pending_passkey; }
bool     BLEServer::hasPendingPasscode(){ return s_has_passkey;     }

void BLEServer::disconnect() {
    if (s_connected) Bluefruit.disconnect(s_conn_hdl);
}

} // namespace ble
} // namespace mash
