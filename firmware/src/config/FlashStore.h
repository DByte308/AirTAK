// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/FlashStore.h — LittleFS persistent config storage
// Planning: §9.4.4 | Roadmap: 1.6
//
// Wraps LittleFS on the GD25Q32C SPI NOR flash (4 MB).
// Each ConfigGroup is serialized with nanopb and stored as a flat binary file.
// Files are written atomically (write-to-tmp, rename) to prevent corruption on power loss.

#pragma once

#include <cstdint>
#include <cstddef>
#include "config/ConfigGroups.h"

namespace mash {
namespace config {

class FlashStore {
public:
    // Phase 1.6: Initialize LittleFS on GD25Q32C SPI NOR flash.
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
} // namespace mash
// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/FlashStore.h — LittleFS SPI NOR flash persistence
// Planning: §9.4.4 | Roadmap: 1.6
//
// Manages the LittleFS filesystem on the GD25Q32C (4 MB SPI NOR flash).
// Each config group stored as a nanopb-encoded binary protobuf under /config/.
// On first boot (files absent): write factory defaults from Defaults.h.

#pragma once

#include <cstdint>
#include <cstddef>
#include "config/ConfigGroups.h"

namespace mash {

class FlashStore {
public:
    // Phase 1.6: Mount LittleFS on GD25Q32C using correct SPI pins from config.h.
    // If filesystem is corrupted, reformat and write factory defaults.
    // Returns true on success.
    static bool mount();

    // Unmount LittleFS (call before power-off or hardware update).
    static void unmount();

    // Phase 1.6: Read a config group from flash. Decodes nanopb into buf (max buf_len bytes).
    // Returns bytes decoded, or 0 on failure.
    static size_t readGroup(ConfigGroup group, uint8_t* buf, size_t buf_len);

    // Phase 1.6: Write a config group to flash. Encodes from buf (len bytes) via nanopb.
    // Performs atomic write: write to /config/tmp.pb, then rename to final path.
    // Returns true on success.
    static bool writeGroup(ConfigGroup group, const uint8_t* buf, size_t len);

    // Phase 1.6: Erase a config group file (causes next readGroup to load factory default).
    static bool eraseGroup(ConfigGroup group);

    // Phase 1.6: Erase ALL config files and reset to factory defaults (§9.4 factory reset).
    // Called in response to AdminCommand::FACTORY_RESET.
    static bool factoryReset();

    // Returns true if the filesystem is mounted.
    static bool isMounted();
};

} // namespace mash
