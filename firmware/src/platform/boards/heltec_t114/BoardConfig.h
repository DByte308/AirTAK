// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/heltec_t114/BoardConfig.h — Heltec Mesh Node T114 pin map
// Pin mapping derived from meshtastic/firmware variants/nrf52840/heltec_mesh_node_t114/variant.h
// on 2026-05-18.
//
// Hardware: nRF52840 (Cortex-M4F @ 64 MHz)
// Display:  1.14" TFT 240×135 ST7789 (separate SPI1)
// Radio:    SX1262 (SPI0)
// GPS:      L76K on Serial1
// Storage:  MX25R1635F QSPI NOR flash

#pragma once

#define AIRTAK_BOARD_ID                    "heltec_t114"
#define AIRTAK_BOARD_NAME                  "Heltec Mesh Node T114"
#define AIRTAK_PLATFORM_NRF52              1
#define AIRTAK_BOARD_HAS_BLE_DFU           1
#define AIRTAK_BOARD_HAS_BLE_OTA           1
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     1
#define AIRTAK_BOARD_HAS_TFT_DISPLAY       1
#define AIRTAK_BOARD_HAS_GPS               1

// Peripheral power rail — VEXT on P0.21, active HIGH.
// Must be enabled before GPS and display are usable.
#define AIRTAK_PIN_POWER_EN                21   // P0.21

// ADC control — P0.06 must be driven HIGH before reading VBAT
#define AIRTAK_PIN_ADC_CTRL                 6   // P0.06

// SX1262 LoRa radio (SPI0)
#define AIRTAK_PIN_SX1262_SCK              19   // P0.19
#define AIRTAK_PIN_SX1262_MISO             23   // P0.23
#define AIRTAK_PIN_SX1262_MOSI             22   // P0.22
#define AIRTAK_PIN_SX1262_NSS              24   // P0.24
#define AIRTAK_PIN_SX1262_RESET            25   // P0.25
#define AIRTAK_PIN_SX1262_DIO1             20   // P0.20
#define AIRTAK_PIN_SX1262_BUSY             17   // P0.17
#define AIRTAK_SX1262_TCXO_VOLTAGE         1.8   // centralized: airtak::TCXO_VOLTAGE_DEFAULT (RadioConstants.h)

// ST7789 TFT display (SPI1, separate bus from radio)
#define AIRTAK_PIN_TFT_SCK                 40   // P1.08
#define AIRTAK_PIN_TFT_MOSI                41   // P1.09
// MISO is not connected on this board; pass a dummy pin as required by the nRF52
// SPIClass constructor — RadioLib uses SPI0 only and is not affected.
#define AIRTAK_PIN_TFT_MISO                39   // dummy (P0.39, not physically connected)
#define AIRTAK_PIN_TFT_CS                  11   // P0.11
#define AIRTAK_PIN_TFT_DC                  12   // P0.12
#define AIRTAK_PIN_TFT_RST                  2   // P0.02
#define AIRTAK_PIN_TFT_BL                  15   // P0.15, active HIGH
#define AIRTAK_TFT_SPI_INSTANCE            NRF_SPIM2

// TFT display geometry (portrait 240×135, rotation 0)
// centralized: airtak::DISP_WIDTH / airtak::DISP_HEIGHT (DisplayConstants.h)
#define AIRTAK_TFT_WIDTH                  240
#define AIRTAK_TFT_HEIGHT                 135

// L76K GPS (Serial1)
#define AIRTAK_PIN_GPS_RX_MCU              39   // P1.07 — MCU RX ← GPS TX
#define AIRTAK_PIN_GPS_TX_MCU              37   // P1.05 — MCU TX → GPS RX
#define AIRTAK_PIN_GPS_PPS                 36   // P1.04
#define AIRTAK_PIN_GPS_STANDBY             34   // P1.02
#define AIRTAK_PIN_GPS_RESET               -1
#define AIRTAK_PIN_GPS_ENABLE              AIRTAK_PIN_GPS_STANDBY
#define AIRTAK_GPS_SERIAL                  Serial1
// Serial1 RX/TX are assigned via variant.h PIN_SERIAL1_RX / PIN_SERIAL1_TX

// Battery and charging
#define AIRTAK_PIN_VBAT_SENSE               4   // P0.04 AIN2
#define AIRTAK_PIN_CHG_STAT                -1

// User button
#define AIRTAK_PIN_USER_BUTTON             42   // P1.10, active low

// NeoPixel LED (not driven by firmware — reserved for future use)
#define AIRTAK_PIN_NEOPIXEL_DATA           14   // P0.14

// On-board QSPI NOR flash (same pins as T-Echo Plus)
#define AIRTAK_PIN_QSPI_SCK                46   // P1.14
#define AIRTAK_PIN_QSPI_CS                 47   // P1.15
#define AIRTAK_PIN_QSPI_IO0                44   // P1.12
#define AIRTAK_PIN_QSPI_IO1                45   // P1.13
#define AIRTAK_PIN_QSPI_IO2                 7   // P0.07
#define AIRTAK_PIN_QSPI_IO3                 5   // P0.05
