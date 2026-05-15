// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/FlashStore.cpp — LittleFS persistent config storage implementation
// Planning: §9.4.4 | Roadmap: 1.6

#include "config/FlashStore.h"
#include "config.h"

#ifdef MASH_NATIVE_TEST

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace mash {
namespace config {

static bool s_initialized = false;

static const char* tempDir() {
    const char* tmp = getenv("TEMP");
    if (!tmp || tmp[0] == '\0') tmp = getenv("TMP");
    if (!tmp || tmp[0] == '\0') tmp = ".";
    return tmp;
}

static void nativePath(ConfigGroup group, char* out, size_t out_len) {
    snprintf(out, out_len, "%s\\mash_flashstore_%u.pb", tempDir(), static_cast<unsigned>(group));
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
} // namespace mash

#else

#include <Arduino.h>
#include <InternalFileSystem.h>
#include <cstdio>
#include <cstring>

namespace mash {
namespace config {

static bool s_initialized = false;

static int countUsedBlock(void* context, lfs_block_t) {
    size_t* used_blocks = static_cast<size_t*>(context);
    ++(*used_blocks);
    return 0;
}

bool FlashStore::init() {
    if (!InternalFS.begin()) {
        InternalFS.format();
        if (!InternalFS.begin()) return false;
    }
    // Ensure /config directory exists
    if (!InternalFS.exists("/config")) {
        InternalFS.mkdir("/config");
    }
    s_initialized = true;
    return true;
}

size_t FlashStore::read(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    if (!s_initialized) return 0;
    const char* path = configGroupFilename(group);
    if (!path) return 0;

    Adafruit_LittleFS_Namespace::File f = InternalFS.open(path, Adafruit_LittleFS_Namespace::FILE_O_READ);
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

    Adafruit_LittleFS_Namespace::File f = InternalFS.open(tmp_path, Adafruit_LittleFS_Namespace::FILE_O_WRITE);
    if (!f) return false;
    size_t written = f.write(data, len);
    f.flush();
    f.close();
    if (written != len) { InternalFS.remove(tmp_path); return false; }

    InternalFS.remove(path);
    InternalFS.rename(tmp_path, path);
    return true;
}

bool FlashStore::erase(ConfigGroup group) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;
    return InternalFS.remove(path);
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
    return InternalFS.exists(path);
}

size_t FlashStore::freeBytes() {
    if (!s_initialized) return 0;
    lfs_t* fs = InternalFS._getFS();
    size_t used_blocks = 0;
    if (lfs_traverse(fs, countUsedBlock, &used_blocks) < 0) return 0;
    const size_t used = used_blocks * fs->cfg->block_size;
    const size_t total = fs->cfg->block_count * fs->cfg->block_size;
    return (used < total) ? (total - used) : 0;
}

} // namespace config
} // namespace mash

#endif // MASH_NATIVE_TEST
