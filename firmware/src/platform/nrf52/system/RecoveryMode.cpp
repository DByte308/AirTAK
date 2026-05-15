// MASH - Mesh Airsoft Situational Hub
// firmware/src/platform/nrf52/system/RecoveryMode.cpp - Bluefruit boot recovery

#include "system/RecoveryMode.h"
#include "ble/DFUService.h"
#include "config/FlashStore.h"
#include "hal/Display.h"
#include "config.h"
#include <Arduino.h>
#include <bluefruit.h>

namespace mash {

constexpr uint32_t RECOVERY_BUTTON_HOLD_MS = 5000;
constexpr uint32_t RECOVERY_SHORT_PRESS_MIN_MS = 50;
constexpr uint32_t RECOVERY_USB_SERIAL_HOLD_MS = 5000;
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
    Serial.println("MASH recovery mode: BLE DFU active; config kept");

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

    bool action_armed = false;
    bool erase_done = false;
    uint32_t press_started_ms = 0;

    for (;;) {
        bool pressed = userButtonPressed();

        if (!action_armed) {
            if (!pressed) action_armed = true;
            delay(100);
            continue;
        }

        if (pressed && press_started_ms == 0) {
            press_started_ms = millis();
        }

        if (pressed && press_started_ms != 0 && !erase_done &&
            (millis() - press_started_ms) >= RECOVERY_CONFIG_ERASE_HOLD_MS) {
            bool flash_ok = config::FlashStore::init();
            erase_done = flash_ok && config::FlashStore::eraseAll();
            Display::showRecoveryMode(erase_done, false);
            Serial.println(erase_done ? "MASH recovery mode: config erased" :
                                       "MASH recovery mode: config erase failed");
            action_armed = false;
            press_started_ms = 0;
        } else if (!pressed && press_started_ms != 0) {
            uint32_t held_ms = millis() - press_started_ms;
            press_started_ms = 0;

            if (held_ms >= RECOVERY_USB_SERIAL_HOLD_MS) {
                Serial.println("MASH recovery mode: entering USB serial DFU bootloader");
                delay(250);
                enterSerialDfu();
            } else if (held_ms >= RECOVERY_SHORT_PRESS_MIN_MS) {
                Serial.println("MASH recovery mode: entering USB UF2 bootloader");
                delay(250);
                enterUf2Dfu();
            }
        }
        delay(100);
    }
}

} // namespace mash
