/* Heltec Mesh Node T114 — Arduino variant pin definitions
 * Derived from meshtastic/firmware variants/nrf52840/heltec_mesh_node_t114/variant.h
 * on 2026-05-18.  AirTAK-only.
 */

#pragma once

#include "WVariant.h"

#ifdef __cplusplus
extern "C" {
#endif

#define HELTEC_MESH_NODE_T114

#define VARIANT_MCK       (64000000ul)
#define USE_LFXO

#define PINS_COUNT           (48)
#define NUM_DIGITAL_PINS     (48)
#define NUM_ANALOG_INPUTS    (1)
#define NUM_ANALOG_OUTPUTS   (0)

/* LEDs */
#define PIN_LED1             (35)   // P1.03 green
#define LED_BUILTIN          PIN_LED1
#define LED_CONN             PIN_LED1
#define LED_BLUE             PIN_LED1
#define LED_GREEN            PIN_LED1
#define LED_STATE_ON         0

/* User button */
#define PIN_BUTTON1          (42)   // P1.10

#define BUTTON_ACTIVE_LOW    true
#define BUTTON_ACTIVE_PULLUP true

/* Battery ADC */
#define PIN_A0               (4)    // P0.04 AIN2
static const uint8_t A0 = PIN_A0;

#define ADC_RESOLUTION               14
#define BATTERY_SENSE_RESOLUTION_BITS 12
#define BATTERY_SENSE_RESOLUTION     4096.0
#undef AREF_VOLTAGE
#define AREF_VOLTAGE 3.0
#define VBAT_AR_INTERNAL AR_INTERNAL_3_0
#define ADC_MULTIPLIER 4.916F

/* GPS on Serial1 */
#define PIN_SERIAL1_RX       (39)   // P1.07 — GPS TX → MCU RX
#define PIN_SERIAL1_TX       (37)   // P1.05 — MCU TX → GPS RX
#define PIN_GPS_PPS          (36)   // P1.04
#define PIN_GPS_STANDBY      (34)   // P1.02

/* QSPI flash (MX25R1635F) */
#define PIN_QSPI_SCK         (46)   // P1.14
#define PIN_QSPI_CS          (47)   // P1.15
#define PIN_QSPI_IO0         (44)   // P1.12
#define PIN_QSPI_IO1         (45)   // P1.13
#define PIN_QSPI_IO2         (7)    // P0.07
#define PIN_QSPI_IO3         (5)    // P0.05

/* SPI0 — LoRa radio */
#define SPI_INTERFACES_COUNT 2
#define PIN_SPI_MISO         (23)   // P0.23
#define PIN_SPI_MOSI         (22)   // P0.22
#define PIN_SPI_SCK          (19)   // P0.19

#define USE_SX1262
#define SX126X_CS            (24)   // P0.24
#define SX126X_DIO1          (20)   // P0.20
#define SX126X_BUSY          (17)   // P0.17
#define SX126X_RESET         (25)   // P0.25
#define SX126X_DIO2_AS_RF_SWITCH
#define SX126X_DIO3_TCXO_VOLTAGE 1.8

static const uint8_t SS   = (24);
static const uint8_t MOSI = PIN_SPI_MOSI;
static const uint8_t MISO = PIN_SPI_MISO;
static const uint8_t SCK  = PIN_SPI_SCK;

/* SPI1 — ST7789 TFT display */
#define PIN_SPI1_MISO        (39)   // unused, but SPI code requires a value
#define PIN_SPI1_MOSI        (41)   // P1.09 — ST7789 SDA
#define PIN_SPI1_SCK         (40)   // P1.08 — ST7789 SCK

#define ST7789_NSS           (11)   // P0.11 CS
#define ST7789_RS            (12)   // P0.12 DC
#define ST7789_RESET          (2)   // P0.02 RST
#define ST7789_BUSY          (-1)   // not connected
#define VTFT_LEDA            (15)   // P0.15 backlight

/* I2C */
#define WIRE_INTERFACES_COUNT 1
#define PIN_WIRE_SDA         (26)   // P0.26
#define PIN_WIRE_SCL         (27)   // P0.27

/* Peripheral power rail */
#define VEXT_ENABLE          (21)   // P0.21

#ifdef __cplusplus
}
#endif
