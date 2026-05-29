// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/sleep/DeepSleep.cpp — ESP32 deep sleep

#include "platform/esp32/sleep/DeepSleep.h"
#include "config.h"
#include <Arduino.h>
#include <esp_sleep.h>
#include <driver/rtc_io.h>
#include <hal/gpio_ll.h>

namespace airtak {
namespace sleep {

static WakeCause s_wake_cause = WakeCause::NOT_DEEP_SLEEP;

// ── Helpers ────────────────────────────────────────────────────────────────

static void disablePeripherals() {
    // Kill the Vext / peripheral power rail so the OLED, LoRa boost, and
    // any external sensors are fully off during sleep.  Heltec V3 Vext is
    // active-LOW; T-Deck / T-Echo are active-HIGH.  Float the pin
    // afterwards so the external regulator is not back-driven.
#if AIRTAK_PIN_POWER_EN >= 0
    #ifdef AIRTAK_POWER_EN_ACTIVE_LOW
        digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);  // disable active-low
    #else
        digitalWrite(AIRTAK_PIN_POWER_EN, LOW);   // disable active-high
    #endif
    pinMode(AIRTAK_PIN_POWER_EN, INPUT);          // high-impedance
#endif

    // Turn off status LED.
#if defined(AIRTAK_PIN_LED_POWER) && AIRTAK_PIN_LED_POWER >= 0
    digitalWrite(AIRTAK_PIN_LED_POWER, LOW);
    pinMode(AIRTAK_PIN_LED_POWER, INPUT);
#endif

    // Float I2C/OLED pins to avoid leakage through pull-ups.
#if defined(AIRTAK_PIN_OLED_SDA) && AIRTAK_PIN_OLED_SDA >= 0
    pinMode(AIRTAK_PIN_OLED_SDA, INPUT);
#endif
#if defined(AIRTAK_PIN_OLED_SCL) && AIRTAK_PIN_OLED_SCL >= 0
    pinMode(AIRTAK_PIN_OLED_SCL, INPUT);
#endif

    // Release ADC control to save power.
#if defined(AIRTAK_PIN_ADC_CTRL) && AIRTAK_PIN_ADC_CTRL >= 0
    pinMode(AIRTAK_PIN_ADC_CTRL, INPUT);
#endif
}

static void isolateGpios() {
    // RTC GPIO isolation prevents leakage via the IO pads.
    // Common leak-prone pins on Heltec V3 and T-Deck:
    static constexpr int rtcGpios[] = {
        // LoRa SPI — held HIGH during normal operation; float during sleep
        #if AIRTAK_PIN_SX1262_NSS >= 0
        AIRTAK_PIN_SX1262_NSS,
        #endif
        #if AIRTAK_PIN_SX1262_SCK >= 0
        AIRTAK_PIN_SX1262_SCK,
        #endif
        #if AIRTAK_PIN_SX1262_MOSI >= 0
        AIRTAK_PIN_SX1262_MOSI,
        #endif
        #if AIRTAK_PIN_SX1262_MISO >= 0
        AIRTAK_PIN_SX1262_MISO,
        #endif
        #if AIRTAK_PIN_SX1262_RESET >= 0
        AIRTAK_PIN_SX1262_RESET,
        #endif
        #if AIRTAK_PIN_SX1262_DIO1 >= 0
        AIRTAK_PIN_SX1262_DIO1,
        #endif
        #if AIRTAK_PIN_SX1262_BUSY >= 0
        AIRTAK_PIN_SX1262_BUSY,
        #endif
    };
    for (int pin : rtcGpios) {
        if (pin >= 0 && pin <= 21) {
            pinMode(pin, INPUT);
            rtc_gpio_isolate((gpio_num_t)pin);
        } else if (pin >= 0) {
            pinMode(pin, INPUT);  // float non-RTC pins
        }
    }
}

// ── Public API ─────────────────────────────────────────────────────────────

void initDeepSleep() {
    auto cause = esp_sleep_get_wakeup_cause();
    switch (cause) {
        case ESP_SLEEP_WAKEUP_TIMER:
            s_wake_cause = WakeCause::TIMER;
            break;
        case ESP_SLEEP_WAKEUP_EXT1:
            s_wake_cause = WakeCause::BUTTON;
            break;
        case ESP_SLEEP_WAKEUP_UNDEFINED:
            s_wake_cause = WakeCause::NOT_DEEP_SLEEP;
            break;
        default:
            s_wake_cause = WakeCause::UNKNOWN;
            break;
    }

    // Release any RTC GPIO holds that may have been set before deep sleep.
    // The hold was applied during enterDeepSleep() to prevent floating pins
    // from leaking current; now that we're awake we must release them.
    rtc_gpio_hold_dis((gpio_num_t)AIRTAK_PIN_USER_BUTTON);

    // Disable any remaining RTC GPIO isolation.
    #if AIRTAK_PIN_SX1262_NSS >= 0
    rtc_gpio_hold_dis((gpio_num_t)AIRTAK_PIN_SX1262_NSS);
    #endif
}

WakeCause wakeCause() {
    return s_wake_cause;
}

[[noreturn]] void enterDeepSleep(uint64_t sleepMs) {
    disablePeripherals();
    isolateGpios();

    // Hold the user button GPIO state through deep sleep so the wake
    // source remains configured.  Without gpio_hold_en() the RTC domain
    // may float the pin, causing spurious wakes.
    gpio_hold_en((gpio_num_t)AIRTAK_PIN_USER_BUTTON);

    // Hold LoRa NSS HIGH during sleep to prevent the SX1262 from
    // spuriously enabling its output driver and back-powering the board.
    #if AIRTAK_PIN_SX1262_NSS >= 0
    digitalWrite(AIRTAK_PIN_SX1262_NSS, HIGH);
    gpio_hold_en((gpio_num_t)AIRTAK_PIN_SX1262_NSS);
    #endif

    // Wake on button press — EXT1 (any-low) for ESP32-S3.
    // The Heltec V3 button (GPIO 0) is active-LOW with external pullup.
    uint64_t wakeMask = 1ULL << AIRTAK_PIN_USER_BUTTON;
    esp_sleep_enable_ext1_wakeup(wakeMask, ESP_EXT1_WAKEUP_ANY_LOW);

    // Timer wake: only if a non-zero interval was requested.
    if (sleepMs > 0) {
        esp_sleep_enable_timer_wakeup(sleepMs * 1000ULL);
    }

    // Keep RTC peripherals powered so ext1 wakeup works.
    esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);

    esp_deep_sleep_start();
    // NOTREACHED
}

} // namespace sleep
} // namespace airtak
