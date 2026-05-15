// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/native/config/FlashStore.cpp - native test config storage

#include "config/FlashStore.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace airtak {
namespace config {

static bool s_initialized = false;

static const char* tempDir() {
    const char* tmp = getenv("TEMP");
    if (!tmp || tmp[0] == '\0') tmp = getenv("TMP");
    if (!tmp || tmp[0] == '\0') tmp = ".";
    return tmp;
}

static void nativePath(ConfigGroup group, char* out, size_t out_len) {
    snprintf(out, out_len, "%s\\airtak_flashstore_%u.pb", tempDir(), static_cast<unsigned>(group));
}

bool FlashStore::init() {
    s_initialized = true;
    return true;
}

size_t FlashStore::read(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    if (!s_initialized) return 0;
    char path[260];
    nativePath(group, path, sizeof(path));
    FILE* file = fopen(path, "rb");
    if (!file) return 0;
    size_t n = fread(buf, 1, buf_len, file);
    fclose(file);
    return n;
}

bool FlashStore::write(ConfigGroup group, const uint8_t* data, size_t len) {
    if (!s_initialized) return false;
    char path[260];
    nativePath(group, path, sizeof(path));
    FILE* file = fopen(path, "wb");
    if (!file) return false;
    size_t written = fwrite(data, 1, len, file);
    fclose(file);
    return written == len;
}

bool FlashStore::erase(ConfigGroup group) {
    if (!s_initialized) return false;
    char path[260];
    nativePath(group, path, sizeof(path));
    return remove(path) == 0 || !exists(group);
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
    char path[260];
    nativePath(group, path, sizeof(path));
    FILE* file = fopen(path, "rb");
    if (!file) return false;
    fclose(file);
    return true;
}

size_t FlashStore::freeBytes() {
    return 1024u * 1024u;
}

} // namespace config
} // namespace airtak
