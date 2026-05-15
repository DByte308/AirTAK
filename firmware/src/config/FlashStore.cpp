// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/FlashStore.cpp — LittleFS persistent config storage implementation
// Planning: §9.4.4 | Roadmap: 1.6

#include "config/FlashStore.h"
#include "firmware/include/config.h"
#include <LittleFS.h>  // Arduino LittleFS wrapper for nRF52
#include <cstring>

namespace mash {
namespace config {

static bool s_initialized = false;

bool FlashStore::init() {
    // Deassert SPI NOR flash CS before any SPI transactions so the GD25Q32C does
    // not interfere with the radio SPI bus during startup (§7.1).
    // PIN_NOR_CS must be verified against the T-Echo Plus schematic.
    pinMode(PIN_NOR_CS, OUTPUT);
    digitalWrite(PIN_NOR_CS, HIGH);

    if (!LittleFS.begin()) {
        // Format on first boot
        LittleFS.format();
        if (!LittleFS.begin()) return false;
    }
    // Ensure /config directory exists
    if (!LittleFS.exists("/config")) {
        LittleFS.mkdir("/config");
    }
    s_initialized = true;
    return true;
}

size_t FlashStore::read(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    if (!s_initialized) return 0;
    const char* path = configGroupFilename(group);
    if (!path) return 0;

    File f = LittleFS.open(path, "r");
    if (!f) return 0;
    size_t n = f.read(buf, buf_len);
    f.close();
    return n;
}

bool FlashStore::write(ConfigGroup group, const uint8_t* data, size_t len) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;

    // Atomic write: write to .tmp file, then rename to destination.
    char tmp_path[48];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path);

    File f = LittleFS.open(tmp_path, "w");
    if (!f) return false;
    size_t written = f.write(data, len);
    f.flush();
    f.close();
    if (written != len) { LittleFS.remove(tmp_path); return false; }

    LittleFS.remove(path);
    LittleFS.rename(tmp_path, path);
    return true;
}

bool FlashStore::erase(ConfigGroup group) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;
    return LittleFS.remove(path);
}

bool FlashStore::eraseAll() {
    if (!s_initialized) return false;
    bool ok = true;
    for (uint8_t i = 0; i < CONFIG_GROUP_COUNT; i++) {
        ok &= erase(static_cast<ConfigGroup>(i));
    }
    return ok;
}

bool FlashStore::exists(ConfigGroup group) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;
    return LittleFS.exists(path);
}

size_t FlashStore::freeBytes() {
    if (!s_initialized) return 0;
    // Adafruit nRF52 BSP LittleFS exposes totalBytes() / usedBytes() directly.
    return LittleFS.totalBytes() - LittleFS.usedBytes();
}

} // namespace config
} // namespace mash
