/* Heltec Mesh Node T114 — Arduino variant init
 * Derived from meshtastic/firmware variants/nrf52840/heltec_mesh_node_t114/variant.cpp
 * on 2026-05-18.
 */

#include "variant.h"

#include "wiring_constants.h"
#include "wiring_digital.h"
#include "nrf.h"

const uint32_t g_ADigitalPinMap[] = {
    0xff, 0xff,  2,  3,  4,  5,  6,  7,
      8,   9,   10, 11, 12, 13, 14, 15,
     16,  17,  18, 19, 20, 21, 22, 23,
     24,  25,  26, 27, 28, 29, 30, 31,
     32,  33,  34, 35, 36, 37, 38, 39,
     40,  41,  42, 43, 44, 45, 46, 47
};

void initVariant() {
    pinMode(PIN_LED1, OUTPUT);
    digitalWrite(PIN_LED1, LED_STATE_ON == 0 ? HIGH : LOW); // off
}
