// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/nrf52/config/FlashStore.cpp - LittleFS on external QSPI flash

#include "config/FlashStore.h"
#include "config.h"
#include "config/constants/Constants.h"

#include <Arduino.h>
#include <Adafruit_LittleFS.h>
#include <Adafruit_SPIFlashBase.h>
#include <cstdio>
#include <cstring>

#include "nrfx_qspi.h"

namespace airtak {
namespace config {

static bool s_initialized = false;

static Adafruit_FlashTransport_QSPI s_flash_transport(
    AIRTAK_PIN_QSPI_SCK,
    AIRTAK_PIN_QSPI_CS,
    AIRTAK_PIN_QSPI_IO0,
    AIRTAK_PIN_QSPI_IO1,
    AIRTAK_PIN_QSPI_IO2,
    AIRTAK_PIN_QSPI_IO3
);
static Adafruit_SPIFlashBase s_flash(&s_flash_transport);
static Adafruit_LittleFS s_config_fs;
static lfs_config s_lfs_cfg;
static uint8_t s_lfs_read_buffer[FLASH_PROG_SIZE];
static uint8_t s_lfs_prog_buffer[FLASH_PROG_SIZE];
static uint8_t s_lfs_file_buffer[FLASH_PROG_SIZE];
static uint8_t s_lfs_lookahead_buffer[FLASH_LFS_LOOKAHEAD_SIZE];

static const SPIFlash_Device_t s_supported_flash[] = {
    MX25R1635F,
    GD25Q32C,
};

static int flashRead(const lfs_config* c, lfs_block_t block,
                     lfs_off_t off, void* buffer, lfs_size_t size) {
    auto* flash = static_cast<Adafruit_SPIFlashBase*>(c->context);
    uint32_t address = block * FLASH_BLOCK_SIZE + off;
    return flash->readBuffer(address, static_cast<uint8_t*>(buffer), size) == size
        ? LFS_ERR_OK : LFS_ERR_IO;
}

static int flashProg(const lfs_config* c, lfs_block_t block,
                     lfs_off_t off, const void* buffer, lfs_size_t size) {
    auto* flash = static_cast<Adafruit_SPIFlashBase*>(c->context);
    uint32_t address = block * FLASH_BLOCK_SIZE + off;
    return flash->writeBuffer(address, static_cast<const uint8_t*>(buffer), size) == size
        ? LFS_ERR_OK : LFS_ERR_IO;
}

static int flashErase(const lfs_config* c, lfs_block_t block) {
    auto* flash = static_cast<Adafruit_SPIFlashBase*>(c->context);
    return flash->eraseSector(block) ? LFS_ERR_OK : LFS_ERR_IO;
}

static int flashSync(const lfs_config* c) {
    auto* flash = static_cast<Adafruit_SPIFlashBase*>(c->context);
    flash->waitUntilReady();
    return LFS_ERR_OK;
}

static void configureLittleFs() {
    memset(&s_lfs_cfg, 0, sizeof(s_lfs_cfg));
    s_lfs_cfg.context = &s_flash;
    s_lfs_cfg.read = flashRead;
    s_lfs_cfg.prog = flashProg;
    s_lfs_cfg.erase = flashErase;
    s_lfs_cfg.sync = flashSync;
    s_lfs_cfg.read_size = FLASH_PROG_SIZE;
    s_lfs_cfg.prog_size = FLASH_PROG_SIZE;
    s_lfs_cfg.block_size = FLASH_BLOCK_SIZE;
    s_lfs_cfg.block_count = s_flash.size() / FLASH_BLOCK_SIZE;
    s_lfs_cfg.lookahead = FLASH_LFS_LOOKAHEAD_SIZE;
    s_lfs_cfg.read_buffer = s_lfs_read_buffer;
    s_lfs_cfg.prog_buffer = s_lfs_prog_buffer;
    s_lfs_cfg.lookahead_buffer = s_lfs_lookahead_buffer;
    s_lfs_cfg.file_buffer = s_lfs_file_buffer;
}

static int countUsedBlock(void* context, lfs_block_t) {
    size_t* used_blocks = static_cast<size_t*>(context);
    ++(*used_blocks);
    return 0;
}

bool FlashStore::init() {
    // The UF2 bootloader leaves the QSPI peripheral in XIP mode to expose
    // a CIRCUITPY drive on the external flash. The peripheral must be
    // explicitly released before the application can re-own it. Failing to
    // do so causes nrfx_qspi_init() to hang in its blocking ready-wait loop
    // because the peripheral is still configured for the bootloader's context.
    nrfx_qspi_uninit();

    delayMicroseconds(100);

    if (!s_flash.begin(s_supported_flash, sizeof(s_supported_flash) / sizeof(s_supported_flash[0]))) {
        return false;
    }
    configureLittleFs();

    if (!s_config_fs.begin(&s_lfs_cfg)) {
        s_config_fs.format();
        if (!s_config_fs.begin(&s_lfs_cfg)) return false;
    }
    if (!s_config_fs.exists("/config")) {
        s_config_fs.mkdir("/config");
    }
    s_initialized = true;
    return true;
}

size_t FlashStore::read(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    if (!s_initialized) return 0;
    const char* path = configGroupFilename(group);
    if (!path) return 0;

    Adafruit_LittleFS_Namespace::File f = s_config_fs.open(path, Adafruit_LittleFS_Namespace::FILE_O_READ);
    if (!f) return 0;
    size_t n = f.read(buf, buf_len);
    f.close();
    return n;
}

bool FlashStore::write(ConfigGroup group, const uint8_t* data, size_t len) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;

    char tmp_path[FLASH_PATH_MAX_BYTES];
    snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path);

    Adafruit_LittleFS_Namespace::File f = s_config_fs.open(tmp_path, Adafruit_LittleFS_Namespace::FILE_O_WRITE);
    if (!f) return false;
    size_t written = f.write(data, len);
    f.flush();
    f.close();
    if (written != len) { s_config_fs.remove(tmp_path); return false; }

    s_config_fs.remove(path);
    return s_config_fs.rename(tmp_path, path);
}

bool FlashStore::erase(ConfigGroup group) {
    if (!s_initialized) return false;
    const char* path = configGroupFilename(group);
    if (!path) return false;
    return s_config_fs.remove(path) || !s_config_fs.exists(path);
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
    return s_config_fs.exists(path);
}

size_t FlashStore::freeBytes() {
    if (!s_initialized) return 0;
    lfs_t* fs = s_config_fs._getFS();
    size_t used_blocks = 0;
    if (lfs_traverse(fs, countUsedBlock, &used_blocks) < 0) return 0;
    const size_t used = used_blocks * fs->cfg->block_size;
    const size_t total = fs->cfg->block_count * fs->cfg->block_size;
    return (used < total) ? (total - used) : 0;
}

} // namespace config
} // namespace airtak
