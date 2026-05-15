// MASH — Mesh Airsoft Situational Hub
// firmware/src/system/RecoveryMode.cpp — Boot-time safe mode and recovery

#include "system/RecoveryMode.h"
#include "ble/DFUService.h"
#include "config/FlashStore.h"
#include "hal/Display.h"
#include "config.h"
#include <Arduino.h>
#include <bluefruit.h>

namespace mash {

constexpr uint32_t RECOVERY_BUTTON_HOLD_MS = 5000;
constexpr uint32_t RECOVERY_CONFIG_ERASE_HOLD_MS = 10000;
constexpr uint32_t RECOVERY_POLL_MS = 10;

static bool userButtonPressed() {
    return digitalRead(MASH_PIN_USER_BUTTON) == LOW;
}

bool RecoveryMode::requestedAtBoot() {
    pinMode(MASH_PIN_USER_BUTTON, INPUT_PULLUP);
    if (!userButtonPressed()) return false;

    uint32_t started_ms = millis();
    while ((millis() - started_ms) < RECOVERY_BUTTON_HOLD_MS) {
        if (!userButtonPressed()) return false;
        delay(RECOVERY_POLL_MS);
    }
    return true;
}

[[noreturn]] void RecoveryMode::run() {
    Serial.println("MASH recovery mode: DFU BLE only; config kept");

    Display::init();
    Display::showRecoveryMode(false, false);

    Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
    Bluefruit.begin();
    Bluefruit.setName("MASH Recovery");
    Bluefruit.setTxPower(4);

    ble::DFUService::init();

    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.ScanResponse.addName();
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(160, 160);
    Bluefruit.Advertising.setFastTimeout(30);
    Bluefruit.Advertising.start(0);

    bool erase_armed = false;
    bool erase_done = false;
    uint32_t erase_press_started_ms = 0;

    for (;;) {
        bool pressed = userButtonPressed();
        if (!pressed) {
            erase_armed = true;
            erase_press_started_ms = 0;
        } else if (erase_armed && !erase_done) {
            if (erase_press_started_ms == 0) erase_press_started_ms = millis();
            if ((millis() - erase_press_started_ms) >= RECOVERY_CONFIG_ERASE_HOLD_MS) {
                bool flash_ok = config::FlashStore::init();
                erase_done = flash_ok && config::FlashStore::eraseAll();
                Display::showRecoveryMode(erase_done, false);
                Serial.println(erase_done ? "MASH recovery mode: config erased" :
                                           "MASH recovery mode: config erase failed");
            }
        }
        delay(100);
    }
}

} // namespace mash
