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
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_system.h>

namespace airtak {

constexpr uint32_t RECOVERY_BUTTON_HOLD_MS      = 5000;
constexpr uint32_t RECOVERY_CONFIG_ERASE_MS     = 10000;
constexpr uint32_t RECOVERY_POLL_MS             = 10;

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

[[noreturn]] void RecoveryMode::run() {
    Serial.println("AirTAK recovery mode (ESP32): BLE advertising; config kept");

    // Power on peripherals (Display needs the power rail)
#if defined(AIRTAK_PIN_POWER_EN) && AIRTAK_PIN_POWER_EN >= 0
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
#ifdef AIRTAK_POWER_EN_ACTIVE_LOW
    digitalWrite(AIRTAK_PIN_POWER_EN, LOW);
#else
    digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);
#endif
    delay(50);
#endif

    Display::init();
    Display::showRecoveryMode(false, false);

    // Minimal BLE advertising — NimBLE, recovery name only, no AirTAK service
    NimBLEDevice::init("AirTAK Recovery");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->setScanResponse(true);
    pAdv->setName("AirTAK Recovery");
    NimBLEDevice::startAdvertising();

    bool action_armed  = false;
    bool erase_done    = false;
    uint32_t press_started = 0;

    for (;;) {
        bool pressed = userButtonPressed();

        if (!action_armed) {
            if (!pressed) action_armed = true;
            delay(100);
            continue;
        }

        if (pressed && press_started == 0) press_started = millis();

        if (pressed && press_started != 0 && !erase_done &&
            (millis() - press_started) >= RECOVERY_CONFIG_ERASE_MS) {
            bool flash_ok = config::FlashStore::init();
            erase_done = flash_ok && config::FlashStore::eraseAll();
            Display::showRecoveryMode(erase_done, false);
            Serial.println(erase_done
                ? "AirTAK recovery mode: config erased; rebooting"
                : "AirTAK recovery mode: config erase failed");
            if (erase_done) {
                delay(500);
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
