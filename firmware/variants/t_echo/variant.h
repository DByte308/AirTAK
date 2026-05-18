#pragma once

#include "WVariant.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VARIANT_MCK       (64000000ul)
#define USE_LFXO

#define TTGO_T_ECHO

#define PINS_COUNT           (48)
#define NUM_DIGITAL_PINS     (48)
#define NUM_ANALOG_INPUTS    (1)
#define NUM_ANALOG_OUTPUTS   (0)

#define PIN_LED1             (14)
#define PIN_LED2             (15)
#define PIN_LED3             (13)
#define LED_BUILTIN          PIN_LED1
#define LED_CONN             PIN_LED1
#define LED_RED              PIN_LED3
#define LED_BLUE             PIN_LED1
#define LED_GREEN            PIN_LED2
#define LED_STATE_ON         0

#define PIN_BUTTON1          (42)
#define PIN_BUTTON2          (18)
#define PIN_BUTTON_TOUCH     (11)
#define BUTTON_ACTIVE_LOW    true
#define BUTTON_ACTIVE_PULLUP true
#define BUTTON_TOUCH_ACTIVE_LOW true
#define BUTTON_TOUCH_ACTIVE_PULLUP true
#define BUTTON_CLICK_MS      400
#define BUTTON_TOUCH_MS      200

#define PIN_A0               (4)

static const uint8_t A0 = PIN_A0;

#define BATTERY_PIN          PIN_A0
#define ADC_RESOLUTION       14
#define BATTERY_SENSE_RESOLUTION_BITS 12
#define BATTERY_SENSE_RESOLUTION 4096.0
#undef AREF_VOLTAGE
#define AREF_VOLTAGE 3.0
#define VBAT_AR_INTERNAL AR_INTERNAL_3_0
#define ADC_MULTIPLIER 2.0F

#define PIN_NFC1             (9)
#define PIN_NFC2             (10)

#define SERIAL_PRINT_PORT 0

#define PIN_SERIAL1_RX       (41)
#define PIN_SERIAL1_TX       (40)

#define GPS_L76K
#define PIN_GPS_REINIT       (37)
#define PIN_GPS_STANDBY      (34)
#define PIN_GPS_PPS          (36)
#define GPS_THREAD_INTERVAL  50

#define PIN_QSPI_SCK         (46)
#define PIN_QSPI_CS          (47)
#define PIN_QSPI_IO0         (44)
#define PIN_QSPI_IO1         (45)
#define PIN_QSPI_IO2         (7)
#define PIN_QSPI_IO3         (5)

#define EXTERNAL_FLASH_DEVICES MX25R1635F
#define EXTERNAL_FLASH_USE_QSPI

#define SPI_INTERFACES_COUNT 2
#define PIN_SPI_MISO         (23)
#define PIN_SPI_MOSI         (22)
#define PIN_SPI_SCK          (19)

#define USE_SX1262
#define USE_SX1268
#define SX126X_CS            (24)
#define SX126X_DIO1          (20)
#define SX1262_DIO3          (21)
#define SX126X_BUSY          (17)
#define SX126X_RESET         (25)
#define SX126X_DIO2_AS_RF_SWITCH
#define SX126X_DIO3_TCXO_VOLTAGE 1.8
#define TCXO_OPTIONAL

static const uint8_t SS   = (24);
static const uint8_t MOSI = PIN_SPI_MOSI;
static const uint8_t MISO = PIN_SPI_MISO;
static const uint8_t SCK  = PIN_SPI_SCK;

#define PIN_EINK_EN          (43)
#define PIN_EINK_CS          (30)
#define PIN_EINK_BUSY        (3)
#define PIN_EINK_DC          (28)
#define PIN_EINK_RES         (2)
#define PIN_EINK_SCLK        (31)
#define PIN_EINK_MOSI        (29)

#define PIN_POWER_EN         (12)

#define PIN_SPI1_MISO        (39)
#define PIN_SPI1_MOSI        PIN_EINK_MOSI
#define PIN_SPI1_SCK         PIN_EINK_SCLK

#define WIRE_INTERFACES_COUNT 1
#define PIN_WIRE_SDA         (26)
#define PIN_WIRE_SCL         (27)

#define TP_SER_IO            (11)
#define PIN_RTC_INT          (16)
#define PCF8563_RTC 0x51

#ifdef __cplusplus
}
#endif
