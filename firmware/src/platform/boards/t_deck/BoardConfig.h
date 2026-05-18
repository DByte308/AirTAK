// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/t_deck/BoardConfig.h — LilyGo T-Deck pin map
// Pin mapping derived from Xinyuan-LilyGO/T-Deck examples/UnitTest/utilities.h, verified 2026-05-16.
//
// Hardware: ESP32-S3-WROOM-1-N16R8 (16 MB flash, 8 MB OPI PSRAM)
// Display:  2.8″ TFT 320×240 ST7789 (shared SPI bus with SX1262)
// Radio:    SX1262 (shared SPI bus with TFT)
// GPS:      Optional — T-Deck-Plus only; UC6580 on Serial1 (GPIO 43/44)
// Input:    BOOT button (GPIO 0), trackball (GPIO 1/2/3/15), QWERTY keyboard (I2C)
//
// NOTE: The SPI bus (SCK=40, MOSI=41, MISO=38) is SHARED between the TFT and the
// SX1262 radio.  Both peripherals use this bus with separate CS lines (TFT=12,
// Radio=9).  SPI.begin() is called once in platform::initBoard(); both TFT_eSPI and
// RadioLib use the default SPI instance.  Concurrent SPI access from separate RTOS
// tasks (display LOW-1, radio HIGH-4) is mediated by task priority: the radio task
// will preempt a display transaction only at the next RTOS tick boundary.  For a
// mission-critical deployment, add a binary SPI mutex shared by both tasks.

#pragma once

#define AIRTAK_BOARD_ID                    "t_deck"
#define AIRTAK_BOARD_NAME                  "LilyGo T-Deck"
#define AIRTAK_PLATFORM_ESP32              1
#define AIRTAK_BOARD_HAS_BLE_DFU           0   // No Nordic DFU; ESP32 USB flashing
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     0   // Uses internal ESP32-S3 flash
#define AIRTAK_BOARD_HAS_TFT_DISPLAY       1
#define AIRTAK_BOARD_HAS_GPS               1   // T-Deck-Plus; define pins so code builds

// Peripheral power rail — must be HIGH before using any peripheral
#define AIRTAK_PIN_POWER_EN                10

// SX1262 LoRa radio (shared SPI bus)
#define AIRTAK_PIN_SX1262_SCK              40
#define AIRTAK_PIN_SX1262_MISO             38
#define AIRTAK_PIN_SX1262_MOSI             41
#define AIRTAK_PIN_SX1262_NSS               9
#define AIRTAK_PIN_SX1262_RESET            17
#define AIRTAK_PIN_SX1262_DIO1             45
#define AIRTAK_PIN_SX1262_BUSY             13
#define AIRTAK_SX1262_TCXO_VOLTAGE         1.8   // SX1262 TCXO reference voltage

// ST7789 TFT display (shared SPI bus with radio)
#define AIRTAK_PIN_TFT_SCK                 40   // shared with radio
#define AIRTAK_PIN_TFT_MOSI                41   // shared with radio
#define AIRTAK_PIN_TFT_MISO                38   // shared with radio
#define AIRTAK_PIN_TFT_CS                  12
#define AIRTAK_PIN_TFT_DC                  11
#define AIRTAK_PIN_TFT_RST                 -1   // tied to system reset
#define AIRTAK_PIN_TFT_BL                  42   // backlight, active HIGH

// TFT display geometry (landscape orientation, rotation 1)
#define AIRTAK_TFT_WIDTH                  320
#define AIRTAK_TFT_HEIGHT                 240

// GPS UART (T-Deck-Plus GPS shield)
// GPS_TX_MCU = pin the MCU transmits on (goes to GPS RX)
// GPS_RX_MCU = pin the MCU receives on (comes from GPS TX)
#define AIRTAK_PIN_GPS_TX_MCU              43
#define AIRTAK_PIN_GPS_RX_MCU              44
#define AIRTAK_PIN_GPS_PPS                 -1   // not exposed on T-Deck
#define AIRTAK_PIN_GPS_STANDBY             -1
#define AIRTAK_PIN_GPS_RESET               -1
#define AIRTAK_PIN_GPS_ENABLE              -1
#define AIRTAK_GPS_SERIAL                  Serial1
// Explicit RX/TX pins for ESP32 HardwareSerial.begin()
#define AIRTAK_GPS_SERIAL_RX_PIN           AIRTAK_PIN_GPS_RX_MCU
#define AIRTAK_GPS_SERIAL_TX_PIN           AIRTAK_PIN_GPS_TX_MCU
#define GPS_BAUD_INITIAL                   38400

// Battery ADC
#define AIRTAK_PIN_VBAT_SENSE               4   // voltage divider output, 12-bit ADC
#define AIRTAK_PIN_CHG_STAT                -1   // charge status not broken out
#define AIRTAK_ADC_MULTIPLIER               1.10f  // calibration: actual V / reported V at 4.2 V

// User button (BOOT button, active low)
#define AIRTAK_PIN_USER_BUTTON              0

// I2C bus (keyboard, touchpad)
#define AIRTAK_PIN_I2C_SDA                 18
#define AIRTAK_PIN_I2C_SCL                  8

// Touchscreen (I2C CST328 or GT911, INT on GPIO 16)
#define AIRTAK_PIN_TOUCH_INT                16
#define AIRTAK_TOUCH_I2C_ADDR_CST328        0x15
#define AIRTAK_TOUCH_I2C_ADDR_GT911         0x5D

// Trackball (GPIO matrix)
#define AIRTAK_PIN_TBALL_UP                 3
#define AIRTAK_PIN_TBALL_DOWN              15
#define AIRTAK_PIN_TBALL_LEFT               1
#define AIRTAK_PIN_TBALL_RIGHT              2
