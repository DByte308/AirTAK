// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/FlashStore.h — LittleFS persistent config storage
// Planning: §9.4.4 | Roadmap: 1.6
//
// Wraps the selected platform's persistent config storage.
// Each ConfigGroup is serialized with nanopb and stored as a flat binary file.
// Files are written atomically (write-to-tmp, rename) to prevent corruption on power loss.

#pragma once

#include <cstdint>
#include <cstddef>
#include "config/ConfigGroups.h"

namespace airtak {
namespace config {

class FlashStore {
public:
    // Phase 1.6: Initialize platform config storage.
    // Creates /config/ directory if not present.
    // Must be called before any read/write.
    static bool init();

    // Phase 1.6: Read a config group from flash.
    // Returns number of bytes read, or 0 if the file does not exist.
    // buf must be large enough to hold the serialized proto (max 512 bytes per group).
    static size_t read(ConfigGroup group, uint8_t* buf, size_t buf_len);

    // Phase 1.6: Write a config group to flash atomically.
    // Returns true on success.
    static bool write(ConfigGroup group, const uint8_t* data, size_t len);

    // Phase 1.6: Erase a config group file (reset to factory default trigger).
    static bool erase(ConfigGroup group);

    // Erase all config files (factory reset).
    static bool eraseAll();

    // Returns true if a config group file exists on flash.
    static bool exists(ConfigGroup group);

    // Diagnostic: free bytes on the LittleFS volume.
    static size_t freeBytes();
};

} // namespace config
} // namespace airtak
