// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/system/RecoveryMode.cpp — ESP32 boot recovery
// Planning: §9.2 | Roadmap: 1.7
//
// ESP32/T-Deck safe-mode recovery path.
//
// The BOOT button (GPIO 0, active low) is held during power-on.  Once requestedAtBoot()
// returns true, run() takes over and never returns.
//
// Recovery mode behaviour on T-Deck (mirrors the nRF52 safe-mode UX):
//   • Shows "SAFE MODE" on the TFT display.
//   • Advertises over BLE as "AirTAK Recovery" (NimBLE, without the AirTAK service).
//   • Button hold ≥ 10 s: erases all LittleFS config files and reboots.
//   • Any other press / no press: just powers off (user power-cycles to exit).
//
// Unlike the nRF52 version, there is no Nordic DFU service.  Firmware updates on
// the T-Deck are done by holding the trackball center + pressing RESET to enter
// ESP32 USB download mode.

#include "system/RecoveryMode.h"
#include "config/FlashStore.h"
#include "hal/Display.h"
#include "config.h"
#include "config/constants/Constants.h"
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_system.h>

#if defined(AIRTAK_RUNTIME_BOARD)
#include "platform/esp32/system/BoardProvisioning.h"
#endif

namespace airtak {

#if defined(AIRTAK_RUNTIME_BOARD)
// Runtime path: button and peripheral-rail polarity come from the selected
// board descriptor. An unknown board (buttonPin < 0) never triggers recovery.
using airtak::platform::selectedBoard;

static bool userButtonPressed() {
    const auto& bd = selectedBoard();
    if (bd.buttonPin < 0) return false;
    return digitalRead(bd.buttonPin) == LOW;
}

bool RecoveryMode::requestedAtBoot() {
    const auto& bd = selectedBoard();
    // No trusted boot button on an unknown board: do not manufacture recovery.
    if (bd.buttonPin < 0) return false;
    pinMode(bd.buttonPin, INPUT_PULLUP);
    if (!userButtonPressed()) return false;

    uint32_t started = millis();
    while ((millis() - started) < RECOVERY_BUTTON_HOLD_MS) {
        if (!userButtonPressed()) return false;
        delay(RECOVERY_POLL_MS);
    }
    return true;
}
#else
static bool userButtonPressed() {
    return digitalRead(AIRTAK_PIN_USER_BUTTON) == LOW;
}

bool RecoveryMode::requestedAtBoot() {
    pinMode(AIRTAK_PIN_USER_BUTTON, INPUT_PULLUP);
    if (!userButtonPressed()) return false;

    uint32_t started = millis();
    while ((millis() - started) < RECOVERY_BUTTON_HOLD_MS) {
        if (!userButtonPressed()) return false;
        delay(RECOVERY_POLL_MS);
    }
    return true;
}
#endif

[[noreturn]] void RecoveryMode::run() {
    Serial.println("AirTAK recovery mode (ESP32): BLE advertising; config kept");

#if defined(AIRTAK_RUNTIME_BOARD)
    // Power on peripherals (Display needs the power rail) — descriptor polarity.
    const auto& bd = selectedBoard();
    if (bd.powerEnablePin >= 0) {
        pinMode(bd.powerEnablePin, OUTPUT);
        digitalWrite(bd.powerEnablePin, bd.powerEnableActiveLow ? LOW : HIGH); // enable
        delay(RECOVERY_PERIPH_SETTLE_MS);
    }
#else
    // Power on peripherals (Display needs the power rail)
#if defined(AIRTAK_PIN_POWER_EN) && AIRTAK_PIN_POWER_EN >= 0
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
#ifdef AIRTAK_POWER_EN_ACTIVE_LOW
    digitalWrite(AIRTAK_PIN_POWER_EN, LOW);
#else
    digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);
#endif
    delay(RECOVERY_PERIPH_SETTLE_MS);
#endif
#endif

    Display::init();
    Display::showRecoveryMode(false, false);

    // Minimal BLE advertising — NimBLE, recovery name only, no AirTAK service
    NimBLEDevice::init(RECOVERY_BLE_NAME);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->setScanResponse(true);
    pAdv->setName(RECOVERY_BLE_NAME);
    NimBLEDevice::startAdvertising();

    bool action_armed  = false;
    bool erase_done    = false;
    uint32_t press_started = 0;

    for (;;) {
        bool pressed = userButtonPressed();

        if (!action_armed) {
            if (!pressed) action_armed = true;
            delay(RECOVERY_DEBOUNCE_POLL_MS);
            continue;
        }

        if (pressed && press_started == 0) press_started = millis();

        if (pressed && press_started != 0 && !erase_done &&
            (millis() - press_started) >= RECOVERY_CONFIG_ERASE_HOLD_MS) {
            bool flash_ok = config::FlashStore::init();
            erase_done = flash_ok && config::FlashStore::eraseAll();
            Display::showRecoveryMode(erase_done, false);
            Serial.println(erase_done
                ? "AirTAK recovery mode: config erased; rebooting"
                : "AirTAK recovery mode: config erase failed");
            if (erase_done) {
                delay(RECOVERY_RESTART_DELAY_MS);
                esp_restart();
            }
        }

        if (!pressed && press_started != 0) {
            press_started = 0; // button released before 10 s — do nothing
        }

        delay(RECOVERY_POLL_MS);
    }
}

} // namespace airtak
