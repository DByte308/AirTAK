// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/boards/native/BoardConfig.h - native unit test board

#pragma once

#define AIRTAK_BOARD_ID                    "native"
#define AIRTAK_BOARD_NAME                  "Native Test"
#define AIRTAK_PLATFORM_NATIVE             1
#define AIRTAK_BOARD_HAS_BLE_DFU           0
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI     0
#define AIRTAK_BOARD_HAS_EINK_DISPLAY      0
#define AIRTAK_BOARD_HAS_GPS               0

#define AIRTAK_PIN_SX1262_SCK              -1
#define AIRTAK_PIN_SX1262_MISO             -1
#define AIRTAK_PIN_SX1262_MOSI             -1
#define AIRTAK_PIN_SX1262_NSS              -1
#define AIRTAK_PIN_SX1262_RESET            -1
#define AIRTAK_PIN_SX1262_DIO1             -1
#define AIRTAK_PIN_SX1262_BUSY             -1
#define AIRTAK_SX1262_TCXO_VOLTAGE         0.0

#define AIRTAK_PIN_EINK_SCK                -1
#define AIRTAK_PIN_EINK_MOSI               -1
#define AIRTAK_PIN_EINK_CS                 -1
#define AIRTAK_PIN_EINK_DC                 -1
#define AIRTAK_PIN_EINK_RST                -1
#define AIRTAK_PIN_EINK_BUSY               -1
#define AIRTAK_PIN_EINK_EN                 -1
#define EINK_MIN_REFRESH_MS              60000

#define AIRTAK_PIN_GPS_RX_MCU              -1
#define AIRTAK_PIN_GPS_TX_MCU              -1
#define AIRTAK_PIN_GPS_PPS                 -1
#define AIRTAK_PIN_GPS_STANDBY             -1
#define AIRTAK_PIN_GPS_RESET               -1
#define AIRTAK_PIN_GPS_ENABLE              -1

#define AIRTAK_PIN_VBAT_SENSE              -1
#define AIRTAK_PIN_CHG_STAT                -1
#define AIRTAK_PIN_USER_BUTTON             -1
#define AIRTAK_PIN_NFC1                    -1
#define AIRTAK_PIN_NFC2                    -1

#define AIRTAK_PIN_QSPI_SCK                -1
#define AIRTAK_PIN_QSPI_CS                 -1
#define AIRTAK_PIN_QSPI_IO0                -1
#define AIRTAK_PIN_QSPI_IO1                -1
#define AIRTAK_PIN_QSPI_IO2                -1
#define AIRTAK_PIN_QSPI_IO3                -1
#define AIRTAK_PIN_POWER_EN                -1
