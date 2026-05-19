// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ota/OtaService.h — platform-agnostic OTA firmware update
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Called by AirTAKService when an OTA_COMMAND or OTA_DATA write is received
// over BLE.  Platform-specific implementation under src/platform/<arch>/ota/.

#pragma once

#include <cstddef>
#include <cstdint>

namespace airtak {
namespace ota {

// Matches OtaState in ble.proto
enum class State : uint8_t {
    Idle       = 0,
    Receiving  = 1,
    Verifying  = 2,
    Writing    = 3,
    Complete   = 4,
    Error      = 5,
};

class OtaService {
public:
    // Begin OTA session.  Called when OtaStart command is received.
    // Returns false if OTA is not supported or partition is invalid.
    static bool begin(size_t imageSize, uint32_t expectedCrc);

    // Write a chunk of firmware data.  Called for each OTA_DATA write.
    // Returns false on flash write failure.
    static bool writeChunk(const uint8_t* data, size_t len);

    // Finalize OTA.  Validates CRC and writes the boot partition. Returns
    // true on success. Does NOT reboot — callers must explicitly invoke
    // reboot() after notifying the host of the OtaStatus(COMPLETE) result.
    static bool finish(uint32_t expectedCrc);

    // Reboot into the freshly-flashed partition. Never returns.
    static void reboot();

    // Cancel in-progress OTA.  Safe to call from any state.
    static void cancel();

    // Current state and progress.
    static State  state();
    static size_t bytesWritten();
    static size_t totalSize();
    static uint32_t lastError();
    static const char* errorText();
};

} // namespace ota
} // namespace airtak
