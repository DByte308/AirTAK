// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/boards/t_echo_plus/BoardConfig.h - LilyGo T-Echo Plus map
// Pin mapping aligned with the upstream Meshtastic T-Echo Plus variant on 2026-05-15.

#pragma once

#define AIRTAK_BOARD_ID                    "t_echo_plus"
#define AIRTAK_BOARD_NAME                  "LilyGo T-Echo Plus"
#define AIRTAK_PLATFORM_NRF52              1
#define AIRTAK_BOARD_HAS_BLE_DFU           1
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     1
#define AIRTAK_BOARD_HAS_EINK_DISPLAY      1
#define AIRTAK_BOARD_HAS_GPS               1

// SX1262 LoRa radio
#define AIRTAK_PIN_SX1262_SCK              19   // P0.19
#define AIRTAK_PIN_SX1262_MISO             23   // P0.23
#define AIRTAK_PIN_SX1262_MOSI             22   // P0.22
#define AIRTAK_PIN_SX1262_NSS              24   // P0.24
#define AIRTAK_PIN_SX1262_RESET            25   // P0.25
#define AIRTAK_PIN_SX1262_DIO1             20   // P0.20
#define AIRTAK_PIN_SX1262_BUSY             17   // P0.17
#define AIRTAK_SX1262_TCXO_VOLTAGE         1.8

// SSD1681 E-Ink display
#define AIRTAK_PIN_EINK_SCK                31   // P0.31
#define AIRTAK_PIN_EINK_MOSI               29   // P0.29
#define AIRTAK_PIN_EINK_CS                 30   // P0.30
#define AIRTAK_PIN_EINK_DC                 28   // P0.28
#define AIRTAK_PIN_EINK_RST                 2   // P0.02
#define AIRTAK_PIN_EINK_BUSY                3   // P0.03
#define AIRTAK_PIN_EINK_EN                 43   // P1.11
#define AIRTAK_EINK_SPI_INSTANCE           NRF_SPIM2
#define EINK_MIN_REFRESH_MS              60000

// L76K GPS
#define AIRTAK_PIN_GPS_RX_MCU              41   // P1.09
#define AIRTAK_PIN_GPS_TX_MCU              40   // P1.08
#define AIRTAK_PIN_GPS_PPS                 36   // P1.04
#define AIRTAK_PIN_GPS_STANDBY             34   // P1.02
#define AIRTAK_PIN_GPS_RESET               37   // P1.05
#define AIRTAK_PIN_GPS_ENABLE              AIRTAK_PIN_GPS_STANDBY
#define AIRTAK_GPS_SERIAL                  Serial1

// Battery and charging
#define AIRTAK_PIN_VBAT_SENSE               4   // P0.04 AIN2
#define AIRTAK_PIN_CHG_STAT                -1   // not exposed in upstream pin map

// User button
#define AIRTAK_PIN_USER_BUTTON             42   // P1.10, active low

// NFC pins are reserved and must not be driven as GPIO.
#define AIRTAK_PIN_NFC1                     9   // P0.09
#define AIRTAK_PIN_NFC2                    10   // P0.10

// On-board QSPI NOR flash
#define AIRTAK_PIN_QSPI_SCK                46   // P1.14
#define AIRTAK_PIN_QSPI_CS                 47   // P1.15
#define AIRTAK_PIN_QSPI_IO0                44   // P1.12
#define AIRTAK_PIN_QSPI_IO1                45   // P1.13
#define AIRTAK_PIN_QSPI_IO2                 7   // P0.07
#define AIRTAK_PIN_QSPI_IO3                 5   // P0.05

// Shared peripheral rail for LoRa, GPS, display, and sensors.
#define AIRTAK_PIN_POWER_EN                12   // P0.12
