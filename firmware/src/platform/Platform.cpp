// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/Platform.cpp - common platform dispatch

#include "platform/Platform.h"

#include <cstring>

#if !defined(AIRTAK_NATIVE_TEST)
#include <Arduino.h>
#endif

#if defined(AIRTAK_PLATFORM_NRF52)
#define NRF_FICR_BASE        0x10000000UL
#define NRF_FICR_DEVICEID0   (*(volatile uint32_t*)(NRF_FICR_BASE + 0x060))
#define NRF_FICR_DEVICEADDR0 (*(volatile uint32_t*)(NRF_FICR_BASE + 0x0A4))
#define NRF_FICR_DEVICEADDR1 (*(volatile uint32_t*)(NRF_FICR_BASE + 0x0A8))
#elif defined(AIRTAK_PLATFORM_ESP32)
#include <esp_efuse.h>
#include <SPI.h>
#endif

namespace airtak {
namespace platform {

const char* boardName() {
    return AIRTAK_BOARD_NAME;
}

const char* mcuFamilyName() {
#if defined(AIRTAK_PLATFORM_NRF52)
    return "nrf52";
#elif defined(AIRTAK_PLATFORM_ESP32)
    return "esp32";
#elif defined(AIRTAK_PLATFORM_NATIVE)
    return "native";
#else
    return "unknown";
#endif
}

void initBoard() {
#if !defined(AIRTAK_NATIVE_TEST)
#if defined(AIRTAK_PIN_POWER_EN) && AIRTAK_PIN_POWER_EN >= 0
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
    digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);
#if defined(AIRTAK_PLATFORM_ESP32)
    delay(100); // peripherals need time to stabilise after the rail comes up
#endif
#endif
#if defined(AIRTAK_PLATFORM_ESP32)
    // Initialise the shared SPI bus for T-Deck (TFT + SX1262 share SCK/MOSI/MISO).
    // Both TFT_eSPI and RadioLib use the default Arduino SPI instance; initialising
    // it here with the correct pins before either library calls begin() is safe and
    // idempotent — subsequent SPI.begin() calls with matching pins are no-ops.
    SPI.begin(AIRTAK_PIN_SX1262_SCK, AIRTAK_PIN_SX1262_MISO,
              AIRTAK_PIN_SX1262_MOSI, AIRTAK_PIN_SX1262_NSS);
#endif
#endif
}

uint32_t nodeId() {
#if defined(AIRTAK_PLATFORM_NRF52)
    return NRF_FICR_DEVICEID0;
#elif defined(AIRTAK_PLATFORM_ESP32)
    // Lower 32 bits of the chip's unique eFuse MAC address.
    uint64_t mac = ESP.getEfuseMac();
    return static_cast<uint32_t>(mac & 0xFFFFFFFFUL);
#elif defined(AIRTAK_PLATFORM_NATIVE)
    return 0x4D415348UL;
#else
#error "Add nodeId() support for this MCU family."
#endif
}

void macAddress(uint8_t out[6]) {
    if (!out) return;
#if defined(AIRTAK_PLATFORM_NRF52)
    uint32_t addr0 = NRF_FICR_DEVICEADDR0;
    uint32_t addr1 = NRF_FICR_DEVICEADDR1;
    memcpy(out + 0, &addr0, 4);
    memcpy(out + 4, &addr1, 2);
#elif defined(AIRTAK_PLATFORM_ESP32)
    esp_efuse_mac_get_default(out);
#elif defined(AIRTAK_PLATFORM_NATIVE)
    const uint8_t native_mac[6] = { 0x4D, 0x41, 0x53, 0x48, 0x00, 0x01 };
    memcpy(out, native_mac, sizeof(native_mac));
#else
#error "Add macAddress() support for this MCU family."
#endif
}

} // namespace platform
} // namespace airtak
