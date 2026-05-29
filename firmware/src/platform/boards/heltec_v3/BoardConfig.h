// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/heltec_v3/BoardConfig.h — Heltec WiFi LoRa 32 V3 pin map
// Pin mapping derived from meshtastic/firmware variants/esp32s3/heltec_v3/variant.h
// and Heltec-Aaron-Lee/WiFi_Kit_series pins_arduino.h, verified 2026-05-28.
//
// Hardware: ESP32-S3FN8 (dual-core LX7 @ 240 MHz, 8 MB flash, no PSRAM)
// Display:  0.96" SSD1306 OLED 128×64 (I2C on GPIO 17/18, RST on 21)
// Radio:    SX1262 sub-GHz LoRa (SPI: SCK=9, MISO=11, MOSI=10, CS=8)
//           DIO2 used as internal RF switch (setDio2AsRfSwitch(true))
//           TCXO at 1.8V (DIO3 controls TCXO internally)
// Power:    Vext on GPIO 36 (active LOW) enables OLED + LoRa antenna boost
// Battery:  Voltage divider on GPIO 1 (ADC1_CH1), ADC_CTRL GPIO 37 (active LOW)
// GPS:      Not present on this board — connect external GPS via BLE
// Storage:  Internal 8 MB flash with LittleFS partition (8 MB scheme)

#pragma once

#define AIRTAK_BOARD_ID                    "heltec_v3"
#define AIRTAK_BOARD_NAME                  "Heltec WiFi LoRa 32 V3"
#define AIRTAK_PLATFORM_ESP32              1
#define AIRTAK_BOARD_HAS_BLE_DFU           0
#define AIRTAK_BOARD_HAS_BLE_OTA           1   // Custom BLE OTA via esp_ota_*
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     0
#define AIRTAK_BOARD_HAS_OLED_DISPLAY      1
#define AIRTAK_BOARD_HAS_GPS               0

// ── Peripheral power rail — Vext on GPIO 36, active LOW ────────────────────
// Must be enabled before OLED and LoRa antenna boost are usable.
// Drives LOW to enable, HIGH (or input) to disable.
#define AIRTAK_POWER_EN_ACTIVE_LOW
#define AIRTAK_PIN_POWER_EN                36   // Vext, active LOW

// ── ADC control — GPIO 37 must be driven LOW before reading VBAT ───────────
// The voltage divider (VBAT → 100k → ADC pin → 100k → ADC_CTRL) is only
// active when ADC_CTRL is LOW.  Drive LOW, sample, release to INPUT to save
// power (consistent with heltec_unofficial library pattern).
#define AIRTAK_PIN_ADC_CTRL                37

// ── SX1262 sub-GHz LoRa radio ───────────────────────────────────────────────
#define AIRTAK_PIN_SX1262_SCK              9
#define AIRTAK_PIN_SX1262_MISO             11
#define AIRTAK_PIN_SX1262_MOSI             10
#define AIRTAK_PIN_SX1262_NSS              8
#define AIRTAK_PIN_SX1262_RESET            12
#define AIRTAK_PIN_SX1262_DIO1             14   // IRQ
#define AIRTAK_PIN_SX1262_BUSY             13   // DIO2 used as BUSY; also setDio2AsRfSwitch(true)
#define AIRTAK_PIN_SX1262_DIO2             -1   // internal RF switch — no physical pin
#define AIRTAK_PIN_SX1262_DIO3             -1   // TCXO control — internal, not wired to GPIO
#define AIRTAK_SX1262_TCXO_VOLTAGE         1.8f

// ── SSD1306 OLED display (I2C, 128×64) ─────────────────────────────────────
#define AIRTAK_PIN_OLED_SDA                17
#define AIRTAK_PIN_OLED_SCL                18
#define AIRTAK_PIN_OLED_RST                21
// centralized: airtak::DISP_WIDTH / airtak::DISP_HEIGHT (DisplayConstants.h)
#define AIRTAK_OLED_WIDTH                 128
#define AIRTAK_OLED_HEIGHT                 64
// SSD1306 I2C address — Heltec V3 uses 0x3C
// centralized: airtak::SSD1306_I2C_ADDR_DEFAULT (DisplayConstants.h)
#define AIRTAK_OLED_I2C_ADDRESS           0x3C

// ── Battery ADC ─────────────────────────────────────────────────────────────
#define AIRTAK_PIN_VBAT_SENSE               1   // GPIO1, ADC1_CH1
#define AIRTAK_PIN_CHG_STAT                -1   // no charge-status pin
// Voltage divider: VBAT → 100k → ADC pin → 100k → ADC_CTRL (LOW).
// ESP32-S3 ADC reference ~1.1V internally, attenuated ~2.5 dB (range ~0–1.5V).
// The combined attenuation + divider ratio yields a ~4.189 mV/step scaling
// (calibrated against the Heltec unofficial library's analogRead / 238.7).
// AIRTAK_ADC_MULTIPLIER absorbs the difference between the hard-coded 3300 mV
// reference assumption in Battery.cpp and the actual ADC behaviour.
#define AIRTAK_ADC_ATTENUATION            ADC_2_5db
#define AIRTAK_ADC_MULTIPLIER              2.6f

// ── User button ─────────────────────────────────────────────────────────────
#define AIRTAK_PIN_USER_BUTTON              0   // BOOT button, active LOW

// ── Status LED ──────────────────────────────────────────────────────────────
#define AIRTAK_PIN_LED_POWER               35   // user LED, active HIGH

// ── External I2C bus (SDA=41, SCL=42 on pin header) ───────────────────────
// Available for external sensors but not initialised by the firmware.
// The on-board SSD1306 OLED uses a separate I2C bus on GPIO 17/18
// (initialised by Display::init()).
// Define AIRTAK_PIN_I2C_SDA / AIRTAK_PIN_I2C_SCL only if a peripheral
// needs the shared bus initialised early in initBoard().
