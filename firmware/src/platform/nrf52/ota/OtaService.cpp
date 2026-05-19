// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/nrf52/ota/OtaService.cpp — nRF52 OTA (bootloader delegation)
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// The nRF52 implementation delegates to the existing Nordic DFU bootloader.
// The firmware does not write its own flash — begin()/writeChunk() are no-ops,
// and finish() triggers bootloader entry via DFUService::enterDfu().
// The Android app handles the actual data transfer using Nordic DFU protocol
// after the OTA_ENTER ACK.

#include "ota/OtaService.h"
#include "ble/DFUService.h"

namespace airtak {
namespace ota {

static State      s_state         = State::Idle;
static size_t     s_bytesWritten  = 0;
static size_t     s_totalSize     = 0;
static uint32_t   s_lastError     = 0;

bool OtaService::begin(size_t imageSize, uint32_t expectedCrc) {
    (void)imageSize;
    (void)expectedCrc;
    s_state        = State::Receiving;
    s_bytesWritten = 0;
    s_totalSize    = imageSize;
    s_lastError    = 0;
    return true;
}

bool OtaService::writeChunk(const uint8_t* data, size_t len) {
    (void)data;
    (void)len;
    s_bytesWritten += len;
    return true;
}

bool OtaService::finish(uint32_t expectedCrc) {
    (void)expectedCrc;
    s_state = State::Complete;
    ble::DFUService::enterDfu();
    // enterDfu() never returns — it triggers the softdevice bootloader
    return true;
}

void OtaService::cancel() {
    s_state        = State::Idle;
    s_bytesWritten = 0;
    s_lastError    = 0;
}

State OtaService::state()        { return s_state; }
size_t OtaService::bytesWritten(){ return s_bytesWritten; }
size_t OtaService::totalSize()   { return s_totalSize; }
uint32_t OtaService::lastError() { return s_lastError; }
const char* OtaService::errorText() { return ""; }

} // namespace ota
} // namespace airtak
