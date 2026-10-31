// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/esp32/config/FlashStore.cpp — ESP32 LittleFS config storage
// Planning: §9.4.4 | Roadmap: 1.6
//
// ESP32 implementation of FlashStore.h using the built-in LittleFS library
// (esp_littlefs / LITTLEFS component in Arduino-ESP32 ≥ 2.x).
//
// Config files live at /config/<group>.pb on the LittleFS partition.
// Writes use an atomic tmp-file + rename pattern to prevent corruption on
// power loss mid-write (same guarantee as the nRF52/Adafruit implementation).

#include "config/FlashStore.h"
#include "config/ConfigGroups.h"
#include "config.h"
#include "config/constants/Constants.h"

#include <LittleFS.h>
#include <Arduino.h>
#include <cstring>
#include <cstdio>

namespace airtak {
namespace config {

static bool s_initialized = false;

// Build the path to a temporary write target for a given group file.
static void tmpPath(const char* group_path, char* out, size_t out_len) {
    snprintf(out, out_len, "%s%s", group_path, FLASH_TMP_SUFFIX);
}

bool FlashStore::init() {
    if (s_initialized) return true;

    // A runtime-family image may encounter a different existing partition
    // layout. Never erase the user's config merely because mounting failed.
#ifdef AIRTAK_RUNTIME_BOARD
    constexpr bool formatOnFail = false;
#else
    constexpr bool formatOnFail = true;
#endif
    if (!LittleFS.begin(formatOnFail)) {
        return false;
    }

    // Ensure /config directory exists.
    if (!LittleFS.exists("/config")) {
        LittleFS.mkdir("/config");
    }

    s_initialized = true;
    return true;
}

size_t FlashStore::read(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    if (!s_initialized) return 0;
    const char* path = configGroupPath(group);
    File f = LittleFS.open(path, FILE_READ);
    if (!f) return 0;
    size_t n = f.read(buf, buf_len);
    f.close();
    return n;
}

bool FlashStore::write(ConfigGroup group, const uint8_t* data, size_t len) {
    if (!s_initialized) return false;
    const char* path = configGroupPath(group);
    char tmp[64];
    tmpPath(path, tmp, sizeof(tmp));

    // Write to tmp file first
    File f = LittleFS.open(tmp, FILE_WRITE, /*create=*/true);
    if (!f) return false;
    size_t written = f.write(data, len);
    f.close();
    if (written != len) {
        LittleFS.remove(tmp);
        return false;
    }

    // Atomic rename: rename tmp → target.  LittleFS rename() atomically
    // replaces the target if it already exists (littlefs v2 journal guarantees
    // this), so no explicit remove() is needed and there is no window where
    // both files are absent simultaneously.
    if (!LittleFS.rename(tmp, path)) {
        LittleFS.remove(tmp);
        return false;
    }
    return true;
}

bool FlashStore::erase(ConfigGroup group) {
    if (!s_initialized) return false;
    const char* path = configGroupPath(group);
    if (!LittleFS.exists(path)) return true;
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
    return LittleFS.exists(configGroupPath(group));
}

size_t FlashStore::freeBytes() {
    if (!s_initialized) return 0;
    return LittleFS.totalBytes() - LittleFS.usedBytes();
}

} // namespace config
} // namespace airtak
