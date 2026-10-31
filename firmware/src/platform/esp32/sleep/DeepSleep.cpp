// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/sleep/DeepSleep.cpp — ESP32 deep sleep

#include "platform/esp32/sleep/DeepSleep.h"
#include "config.h"
#include <Arduino.h>
#include <esp_sleep.h>
#include <driver/rtc_io.h>
#include <hal/gpio_ll.h>

#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
#include "platform/esp32/system/BoardProvisioning.h"
#endif

namespace airtak {
namespace sleep {

static WakeCause s_wake_cause = WakeCause::NOT_DEEP_SLEEP;

#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
// Runtime path: every pin and polarity comes from the selected board
// descriptor. Only a desc-reported present pin (>= 0) is ever touched, and
// only valid RTC GPIOs (0..21) are isolated/held.
//
// Deep-sleep pin discipline:
//  - All SPI radio pins EXCEPT radioNss are floated + RTC-isolated.
//  - radioNss is driven HIGH (as OUTPUT) and held, so the SX1262 cannot
//    spuriously enable its driver and back-power the board.
//  - On wake, EVERY held/isolated RTC radio pin (and the button) is released
//    via rtc_gpio_hold_dis + rtc_gpio_deinit so initBoard()/Radio can
//    reconfigure the pads — do not leave them latched into deep sleep.
using airtak::platform::selectedBoard;

static bool pinRtc(int pin) { return pin >= 0 && pin <= 21; }

// SPI pads to float/isolate. radioNss is deliberately excluded here (it is
// driven HIGH and held instead, so isolating it would fight the hold).
static int radioFloatPins(int out[6]) {
    const auto& bd = selectedBoard();
    out[0] = bd.radioSck; out[1] = bd.radioMiso; out[2] = bd.radioMosi;
    out[3] = bd.radioRst; out[4] = bd.radioDio1; out[5] = bd.radioBusy;
    return 6;
}

// ALL radio pads (including NSS) — the full set that may have been isolated or
// held, so every one is released on wake.
static int radioHeldPins(int out[7]) {
    const auto& bd = selectedBoard();
    out[0] = bd.radioSck; out[1] = bd.radioMiso; out[2] = bd.radioMosi;
    out[3] = bd.radioRst; out[4] = bd.radioDio1; out[5] = bd.radioBusy;
    out[6] = bd.radioNss;
    return 7;
}

static void disablePeripherals() {
    const auto& bd = selectedBoard();

    if (bd.powerEnablePin >= 0) {
        digitalWrite(bd.powerEnablePin, bd.powerEnableActiveLow ? HIGH : LOW); // disable
        pinMode(bd.powerEnablePin, INPUT);                                     // high-impedance
    }
    if (bd.ledPin >= 0) { digitalWrite(bd.ledPin, LOW); pinMode(bd.ledPin, INPUT); }
    if (bd.oledSda >= 0) pinMode(bd.oledSda, INPUT);
    if (bd.oledScl >= 0) pinMode(bd.oledScl, INPUT);
    if (bd.adcCtrlPin >= 0) pinMode(bd.adcCtrlPin, INPUT);
    // KCT8103L RF FEM (V4): power down the VFEM rail and chip-disable CSD so the
    // PA/LNA cannot draw current overnight. Explicitly NOT a measured-quiescent
    // current claim — this is only the off-state drive.
    if (bd.femVfem >= 0) { pinMode(bd.femVfem, OUTPUT); digitalWrite(bd.femVfem, LOW); }
    if (bd.femCsd  >= 0) { pinMode(bd.femCsd,  OUTPUT); digitalWrite(bd.femCsd,  LOW); }
}

static void isolateGpios() {
    int pins[6];
    const int n = radioFloatPins(pins);
    for (int i = 0; i < n; ++i) {
        if (pins[i] < 0) continue;
        pinMode(pins[i], INPUT);
        if (pinRtc(pins[i])) rtc_gpio_isolate((gpio_num_t)pins[i]);
    }
}

void initDeepSleep() {
    const auto& bd = selectedBoard();
    auto cause = esp_sleep_get_wakeup_cause();
    switch (cause) {
        case ESP_SLEEP_WAKEUP_TIMER: s_wake_cause = WakeCause::TIMER; break;
        case ESP_SLEEP_WAKEUP_EXT1:  s_wake_cause = WakeCause::BUTTON; break;
        case ESP_SLEEP_WAKEUP_UNDEFINED: s_wake_cause = WakeCause::NOT_DEEP_SLEEP; break;
        default: s_wake_cause = WakeCause::UNKNOWN; break;
    }

    // Release EVERY pad that may have been isolated/held on the way into sleep
    // (all radio pads + button), so initBoard()/Radio can reconfigure them.
    int pins[7];
    const int n = radioHeldPins(pins);
    for (int i = 0; i < n; ++i) {
        if (!pinRtc(pins[i])) continue;
        rtc_gpio_hold_dis((gpio_num_t)pins[i]);
        rtc_gpio_deinit((gpio_num_t)pins[i]);
    }
    if (pinRtc(bd.buttonPin)) {
        rtc_gpio_hold_dis((gpio_num_t)bd.buttonPin);
        rtc_gpio_deinit((gpio_num_t)bd.buttonPin);
    }
}

WakeCause wakeCause() { return s_wake_cause; }

[[noreturn]] void enterDeepSleep(uint64_t sleepMs) {
    const auto& bd = selectedBoard();
    disablePeripherals();
    isolateGpios();

    // Hold the button GPIO state so the ext1 wake source stays configured.
    if (pinRtc(bd.buttonPin)) gpio_hold_en((gpio_num_t)bd.buttonPin);

    // Drive radioNss HIGH and hold it so the SX1262 cannot enable its output
    // driver and back-power the board. Set OUTPUT + HIGH BEFORE the hold; NSS is
    // excluded from isolateGpios() so this hold is not fought by isolation.
    if (bd.radioNss >= 0) {
        pinMode(bd.radioNss, OUTPUT);
        digitalWrite(bd.radioNss, HIGH);
        if (pinRtc(bd.radioNss)) gpio_hold_en((gpio_num_t)bd.radioNss);
    }

    // Wake on button press (EXT1 any-low). Only when a valid RTC button pin exists;
    // otherwise the chip sleeps until a hardware reset (no pin driven).
    if (pinRtc(bd.buttonPin)) {
        uint64_t wakeMask = 1ULL << bd.buttonPin;
        esp_sleep_enable_ext1_wakeup(wakeMask, ESP_EXT1_WAKEUP_ANY_LOW);
    }

    // Timer wake: only if a non-zero interval was requested.
    if (sleepMs > 0) esp_sleep_enable_timer_wakeup(sleepMs * 1000ULL);
    esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);
    esp_deep_sleep_start();
}
#else
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

#endif // AIRTAK_RUNTIME_BOARD

} // namespace sleep
} // namespace airtak
