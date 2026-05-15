#pragma once

#include "WVariant.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VARIANT_MCK       (64000000ul)
#define USE_LFXO

#define PINS_COUNT           (48)
#define NUM_DIGITAL_PINS     (48)
#define NUM_ANALOG_INPUTS    (8)
#define NUM_ANALOG_OUTPUTS   (0)

#define PIN_LED1             (35)
#define LED_BUILTIN          PIN_LED1
#define LED_CONN             PIN_LED1
#define LED_BLUE             PIN_LED1
#define LED_RED              PIN_LED1
#define LED_STATE_ON         1

#define PIN_BUTTON1          (42)

#define PIN_A0               (2)
#define PIN_A1               (3)
#define PIN_A2               (4)
#define PIN_A3               (5)
#define PIN_A4               (28)
#define PIN_A5               (30)
#define PIN_A6               (31)
#define PIN_A7               (29)

static const uint8_t A0 = PIN_A0;
static const uint8_t A1 = PIN_A1;
static const uint8_t A2 = PIN_A2;
static const uint8_t A3 = PIN_A3;
static const uint8_t A4 = PIN_A4;
static const uint8_t A5 = PIN_A5;
static const uint8_t A6 = PIN_A6;
static const uint8_t A7 = PIN_A7;

#define ADC_RESOLUTION       14
#define PIN_AREF             PIN_A7
static const uint8_t AREF = PIN_AREF;

#define PIN_SERIAL1_RX       (41)
#define PIN_SERIAL1_TX       (40)

#define SPI_INTERFACES_COUNT 1
#define PIN_SPI_MISO         (23)
#define PIN_SPI_MOSI         (22)
#define PIN_SPI_SCK          (19)

static const uint8_t SS   = (24);
static const uint8_t MOSI = PIN_SPI_MOSI;
static const uint8_t MISO = PIN_SPI_MISO;
static const uint8_t SCK  = PIN_SPI_SCK;

#define WIRE_INTERFACES_COUNT 1
#define PIN_WIRE_SDA         (12)
#define PIN_WIRE_SCL         (11)

#ifdef __cplusplus
}
#endif