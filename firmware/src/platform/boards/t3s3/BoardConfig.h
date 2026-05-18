// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/t3s3/BoardConfig.h — LilyGo T-LoRa T3 S3 pin map
// Pin mapping derived from meshtastic/firmware variants/esp32s3/tlora_t3s3_v1/variant.h
// on 2026-05-18.
//
// Hardware: ESP32-S3 (dual-core, WiFi+BLE, OPI PSRAM)
// Display:  0.96" SSD1306 OLED 128×64 (I2C)
// Radio:    Multi-variant — SX1262 / SX1276 / SX1280 / LR1121 on shared SPI bus
//           AirTAK auto-detects the installed radio at boot via SPI ID probing.
// Storage:  SD card on SPI1 + internal 16 MB flash for LittleFS
// GPS:      Not present on this board
//
// Radio variant handling strategy:
//   • All variants share the same SPI bus (SCK/MISO/MOSI/CS/RESET).
//   • Only DIO/BUSY/rf-switch pins differ between variants.
//   • Radio::init() probes each supported radio type in order; first to respond
//     becomes the active radio for the session.
//   • Frequency band is determined by which radio was detected:
//       SX1262 / SX1276 → sub-GHz (EU868/US915/… via LoraConfig)
//       SX1280          → 2.4 GHz ISM (2400–2480 MHz)
//       LR1121          → dual-band (sub-GHz or 2.4 GHz)

#pragma once

#define AIRTAK_BOARD_ID                    "t3s3"
#define AIRTAK_BOARD_NAME                  "LilyGo T-LoRa T3 S3"
#define AIRTAK_PLATFORM_ESP32              1
#define AIRTAK_BOARD_HAS_BLE_DFU           0
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     0
#define AIRTAK_BOARD_HAS_OLED_DISPLAY      1
#define AIRTAK_BOARD_HAS_GPS               0

// ── Shared SPI bus (all radio variants use these same pins) ──────────────────
#define AIRTAK_PIN_LORA_SCK                 5
#define AIRTAK_PIN_LORA_MISO                3
#define AIRTAK_PIN_LORA_MOSI                6
#define AIRTAK_PIN_LORA_CS                  7
#define AIRTAK_PIN_LORA_RESET               8

// ── SX1262 sub-GHz LoRa (868/915 MHz) ────────────────────────────────────────
#define AIRTAK_PIN_SX1262_DIO1             33
#define AIRTAK_PIN_SX1262_BUSY             34
#define AIRTAK_PIN_SX1262_DIO2             -1   // internal rf-switch
#define AIRTAK_PIN_SX1262_TCXO_VOLTAGE     1.8f

// ── SX1276 sub-GHz LoRa (868/915 MHz) ────────────────────────────────────────
#define AIRTAK_PIN_SX1276_DIO0              9
#define AIRTAK_PIN_SX1276_DIO1             33
#define AIRTAK_PIN_SX1276_DIO2             34
#define AIRTAK_PIN_SX1276_RXEN             21
#define AIRTAK_PIN_SX1276_TXEN             10

// ── SX1280 2.4 GHz LoRa / FLRC ───────────────────────────────────────────────
#define AIRTAK_PIN_SX1280_DIO1              9
#define AIRTAK_PIN_SX1280_DIO2             33
#define AIRTAK_PIN_SX1280_DIO3             34
#define AIRTAK_PIN_SX1280_BUSY             36
#define AIRTAK_PIN_SX1280_RXEN             21
#define AIRTAK_PIN_SX1280_TXEN             10
#define AIRTAK_PIN_SX1280_MAX_POWER         3   // 0–3 (0 = min, 3 = max)

// ── LR1121 sub-GHz + 2.4 GHz dual-band ───────────────────────────────────────
#define AIRTAK_PIN_LR1121_IRQ              36
#define AIRTAK_PIN_LR1121_BUSY             34
#define AIRTAK_PIN_LR1121_TCXO_VOLTAGE     3.0f

// ── SX1262-compatible fallback pins (used by Radio.cpp for single-radio init) ─
// These map the primary/default radio (SX1262 variant).  When runtime detection
// is active, these are used for the SX1262 probe pass only.
#define AIRTAK_PIN_SX1262_SCK              AIRTAK_PIN_LORA_SCK
#define AIRTAK_PIN_SX1262_MISO             AIRTAK_PIN_LORA_MISO
#define AIRTAK_PIN_SX1262_MOSI             AIRTAK_PIN_LORA_MOSI
#define AIRTAK_PIN_SX1262_NSS              AIRTAK_PIN_LORA_CS
#define AIRTAK_PIN_SX1262_RESET            AIRTAK_PIN_LORA_RESET
#define AIRTAK_SX1262_TCXO_VOLTAGE         AIRTAK_PIN_SX1262_TCXO_VOLTAGE

// ── SSD1306 OLED display (I2C, 128×64) ───────────────────────────────────────
#define AIRTAK_PIN_OLED_SDA                18
#define AIRTAK_PIN_OLED_SCL                17
#define AIRTAK_OLED_WIDTH                 128
#define AIRTAK_OLED_HEIGHT                 64
// SSD1306 I2C address — usually 0x3C; some modules use 0x3D
#define AIRTAK_OLED_I2C_ADDRESS           0x3C

// ── Battery ADC ───────────────────────────────────────────────────────────────
#define AIRTAK_PIN_VBAT_SENSE               1   // GPIO1, ADC1_CH1
#define AIRTAK_PIN_CHG_STAT                -1
#define AIRTAK_ADC_CHANNEL                ADC1_GPIO1_CHANNEL

// ── User button ───────────────────────────────────────────────────────────────
#define AIRTAK_PIN_USER_BUTTON              0   // BOOT button, active low

// ── Status LED ────────────────────────────────────────────────────────────────
#define AIRTAK_PIN_LED_POWER               37   // power indicator LED

// ── SD card (SPI1, separate from radio SPI) ───────────────────────────────────
#define AIRTAK_PIN_SD_CS                   13
#define AIRTAK_PIN_SPI1_SCK                14
#define AIRTAK_PIN_SPI1_MOSI               11
#define AIRTAK_PIN_SPI1_MISO                2

// ── Peripheral power (not needed; board powers peripherals directly) ──────────
#define AIRTAK_PIN_POWER_EN                -1
