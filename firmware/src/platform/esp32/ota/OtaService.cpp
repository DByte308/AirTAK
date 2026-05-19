// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/ota/OtaService.cpp — ESP32 OTA via esp_ota_* APIs
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Implements wireless firmware updates using ESP-IDF's native OTA APIs:
// esp_ota_begin / esp_ota_write / esp_ota_end / esp_ota_set_boot_partition.
// CRC32 validation is deferred to the Android app which computes CRC of the
// entire firmware file and passes it in OtaFinish as a final gate.

#include "ota/OtaService.h"
#include <esp_ota_ops.h>
#include <esp_partition.h>
#include <esp_system.h>

namespace airtak {
namespace ota {

static esp_ota_handle_t       s_otaHandle     = 0;
static const esp_partition_t* s_otaPart       = nullptr;
static size_t                 s_bytesWritten  = 0;
static size_t                 s_totalSize     = 0;
static uint32_t               s_expectedCrc   = 0;
static State                  s_state         = State::Idle;
static uint32_t               s_lastError     = 0;

bool OtaService::begin(size_t imageSize, uint32_t expectedCrc) {
    s_totalSize     = imageSize;
    s_expectedCrc   = expectedCrc;
    s_bytesWritten  = 0;
    s_lastError     = 0;

    s_otaPart = esp_ota_get_next_update_partition(nullptr);
    if (!s_otaPart) {
        s_lastError = 0xFFFFFFFF;
        s_state     = State::Error;
        return false;
    }

    esp_err_t err = esp_ota_begin(s_otaPart, imageSize, &s_otaHandle);
    if (err != ESP_OK) {
        s_lastError = static_cast<uint32_t>(err);
        s_state     = State::Error;
        return false;
    }

    s_state = State::Receiving;
    return true;
}

bool OtaService::writeChunk(const uint8_t* data, size_t len) {
    if (s_state != State::Receiving) return false;

    esp_err_t err = esp_ota_write(s_otaHandle, data, len);
    if (err != ESP_OK) {
        s_lastError = static_cast<uint32_t>(err);
        s_state     = State::Error;
        return false;
    }
    s_bytesWritten += len;
    return true;
}

bool OtaService::finish(uint32_t expectedCrc) {
    if (expectedCrc != s_expectedCrc) {
        s_lastError = 0xFFFFFFFF;
        s_state     = State::Error;
        return false;
    }

    s_state = State::Verifying;

    esp_err_t err = esp_ota_end(s_otaHandle);
    s_otaHandle = 0;
    if (err != ESP_OK) {
        s_lastError = static_cast<uint32_t>(err);
        s_state     = State::Error;
        return false;
    }

    s_state = State::Writing;

    err = esp_ota_set_boot_partition(s_otaPart);
    if (err != ESP_OK) {
        s_lastError = static_cast<uint32_t>(err);
        s_state     = State::Error;
        return false;
    }

    s_state = State::Complete;
    // NOTE: Do NOT esp_restart() here. The caller (OtaCommandCallbacks)
    // first sends OtaStatus(COMPLETE) over BLE so the Android app can
    // distinguish a real successful update from an unrelated disconnect,
    // then explicitly calls reboot() below.
    return true;
}

void OtaService::reboot() {
    esp_restart();
}

void OtaService::cancel() {
    if (s_otaHandle) {
        esp_ota_end(s_otaHandle);
        s_otaHandle = 0;
    }
    s_state        = State::Idle;
    s_bytesWritten = 0;
    s_lastError    = 0;
}

State OtaService::state()         { return s_state; }
size_t OtaService::bytesWritten() { return s_bytesWritten; }
size_t OtaService::totalSize()    { return s_totalSize; }
uint32_t OtaService::lastError()  { return s_lastError; }
const char* OtaService::errorText() { return ""; }

} // namespace ota
} // namespace airtak
