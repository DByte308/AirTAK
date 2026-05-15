// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/ConfigManager.cpp — Hot-reload config engine implementation
// Planning: §9.4, §9.4.2 | Roadmap: 2.8

#include "config/ConfigManager.h"
#include "config/FlashStore.h"
#include "config/Defaults.h"
#include "hal/Radio.h"
#include "mesh/ChannelManager.h"
#include <cstring>
#include <Arduino.h>

namespace mash {
namespace config {

static ConfigChangedCallback s_callback = nullptr;
static bool                  s_radio_restart_pending = false;

// In-memory raw protobuf buffers for each config group (max 512 bytes per group)
constexpr size_t CONFIG_BUF_SIZE = 512;
static uint8_t  s_buffers[CONFIG_GROUP_COUNT][CONFIG_BUF_SIZE];
static size_t   s_sizes[CONFIG_GROUP_COUNT];

void ConfigManager::init() {
    for (uint8_t i = 0; i < CONFIG_GROUP_COUNT; i++) {
        ConfigGroup g = static_cast<ConfigGroup>(i);
        size_t n = FlashStore::read(g, s_buffers[i], CONFIG_BUF_SIZE);
        if (n == 0) {
            // Apply factory defaults — TODO Phase 2.8: nanopb-encode defaults
            s_sizes[i] = 0;
        } else {
            s_sizes[i] = n;
        }
    }
}

int ConfigManager::applyGroup(ConfigGroup group, const uint8_t* pb_data, size_t pb_len, bool commit) {
    if (pb_len > CONFIG_BUF_SIZE) return -1; // buffer overflow

    // TODO Phase 2.8: nanopb decode pb_data into the appropriate proto struct to validate it.

    // Update in-memory buffer
    uint8_t idx = static_cast<uint8_t>(group);
    memcpy(s_buffers[idx], pb_data, pb_len);
    s_sizes[idx] = pb_len;

    if (commit) {
        FlashStore::write(group, pb_data, pb_len);
        if (groupRequiresRadioRestart(group)) {
            s_radio_restart_pending = true;
            radioRestartProcedure();
        }
        if (s_callback) s_callback(group);
    }
    return 0;
}

size_t ConfigManager::readGroup(ConfigGroup group, uint8_t* buf, size_t buf_len) {
    uint8_t idx = static_cast<uint8_t>(group);
    size_t  n   = s_sizes[idx];
    if (n == 0 || n > buf_len) return 0;
    memcpy(buf, s_buffers[idx], n);
    return n;
}

void ConfigManager::resetGroup(ConfigGroup group) {
    uint8_t idx = static_cast<uint8_t>(group);
    FlashStore::erase(group);
    s_sizes[idx] = 0;
    // TODO Phase 2.8: nanopb-encode factory defaults into s_buffers[idx]
    if (s_callback) s_callback(group);
}

void ConfigManager::registerCallback(ConfigChangedCallback cb) { s_callback = cb; }

void ConfigManager::radioRestartProcedure() {
    // §9.4.2 Radio restart sequence. Target ≤ 300 ms.
    Radio::pause();          // signal radio task to pause
    // drain TX queue (TODO: block until Radio::isPaused() within 100 ms timeout)
    Radio::reinit();         // re-apply new PHY parameters
    ChannelManager::rekey(); // derive new AES keys from updated PSKs
    Radio::resume();         // signal radio task to resume
    s_radio_restart_pending = false;
}

bool ConfigManager::isRadioRestartPending() { return s_radio_restart_pending; }

} // namespace config
} // namespace mash
