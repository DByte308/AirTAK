// ESP32-S3 runtime-family wiring. Feature macros compile the available drivers;
// actual presence and GPIO numbers come from the sealed startup descriptor.
// Never use these dynamic pin macros in a preprocessor #if comparison.
#pragma once
#include "platform/esp32/system/BoardProvisioning.h"

#define AIRTAK_PLATFORM_ESP32 1
#define AIRTAK_BOARD_HAS_BLE_DFU 0
#define AIRTAK_BOARD_HAS_BLE_OTA 1
#define AIRTAK_BOARD_HAS_EXTERNAL_QSPI 0
#define AIRTAK_BOARD_HAS_GPS 1
#define AIRTAK_BOARD_ID (airtak::platform::selectedBoard().boardId)
#define AIRTAK_BOARD_NAME (airtak::platform::selectedBoard().boardName)

#define AIRTAK_PIN_POWER_EN (airtak::platform::selectedBoard().powerEnablePin)
#define AIRTAK_PIN_USER_BUTTON (airtak::platform::selectedBoard().buttonPin)
#define AIRTAK_PIN_LED_POWER (airtak::platform::selectedBoard().ledPin)
#define AIRTAK_PIN_VBAT_SENSE (airtak::platform::selectedBoard().vbatPin)
#define AIRTAK_PIN_CHG_STAT (-1)
#define AIRTAK_PIN_ADC_CTRL (airtak::platform::selectedBoard().adcCtrlPin)
#define AIRTAK_ADC_MULTIPLIER (airtak::platform::selectedBoard().adcMultiplier)

#define AIRTAK_PIN_SX1262_SCK (airtak::platform::selectedBoard().radioSck)
#define AIRTAK_PIN_SX1262_MISO (airtak::platform::selectedBoard().radioMiso)
#define AIRTAK_PIN_SX1262_MOSI (airtak::platform::selectedBoard().radioMosi)
#define AIRTAK_PIN_SX1262_NSS (airtak::platform::selectedBoard().radioNss)
#define AIRTAK_PIN_SX1262_RESET (airtak::platform::selectedBoard().radioRst)
#define AIRTAK_PIN_SX1262_DIO1 (airtak::platform::selectedBoard().radioDio1)
#define AIRTAK_PIN_SX1262_BUSY (airtak::platform::selectedBoard().radioBusy)
#define AIRTAK_SX1262_TCXO_VOLTAGE (airtak::platform::selectedBoard().radioTcxoVoltage)
#define AIRTAK_PIN_LORA_CS AIRTAK_PIN_SX1262_NSS
#define AIRTAK_PIN_LORA_RESET AIRTAK_PIN_SX1262_RESET
#define AIRTAK_PIN_SX1276_DIO0 (airtak::platform::selectedBoard().sx1276Dio0)
#define AIRTAK_PIN_SX1276_DIO1 (airtak::platform::selectedBoard().sx1276Dio1)
#define AIRTAK_PIN_SX1276_DIO2 (airtak::platform::selectedBoard().sx1276Dio2)
#define AIRTAK_PIN_SX1276_RXEN (airtak::platform::selectedBoard().sx1276Rxen)
#define AIRTAK_PIN_SX1276_TXEN (airtak::platform::selectedBoard().sx1276Txen)
#define AIRTAK_PIN_FEM_VFEM (airtak::platform::selectedBoard().femVfem)
#define AIRTAK_PIN_FEM_CSD (airtak::platform::selectedBoard().femCsd)
#define AIRTAK_PIN_FEM_CTX (airtak::platform::selectedBoard().femCtx)

#define AIRTAK_PIN_I2C_SDA (airtak::platform::selectedBoard().i2cSda)
#define AIRTAK_PIN_I2C_SCL (airtak::platform::selectedBoard().i2cScl)
#define AIRTAK_PIN_TOUCH_INT (airtak::platform::selectedBoard().touchInt)
#define AIRTAK_GPS_SERIAL Serial1
#define AIRTAK_GPS_SERIAL_RX_PIN (airtak::platform::selectedBoard().gpsRx)
#define AIRTAK_GPS_SERIAL_TX_PIN (airtak::platform::selectedBoard().gpsTx)
// No current ESP32 profile has dedicated GPS power/reset/PPS controls.
#define AIRTAK_PIN_GPS_ENABLE (-1)
#define AIRTAK_PIN_GPS_RESET (-1)
#define AIRTAK_PIN_GPS_PPS (-1)
