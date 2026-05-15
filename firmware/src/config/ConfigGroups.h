// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/ConfigGroups.h — Config group enumeration and metadata
// Planning: §9.4.1, §14.2 | Roadmap: 2.8
//
// Lists all 8 config groups and their LittleFS file paths.
// Each group is serialized as a nanopb-encoded proto message under /config/ on SPI NOR flash.

#pragma once

#include <cstdint>

namespace mash {

enum class ConfigGroup : uint8_t {
    DEVICE   = 0,   // DeviceConfig         → /config/device.pb
    LORA     = 1,   // LoraConfig           → /config/lora.pb
    CHANNELS = 2,   // ChannelConfig        → /config/channels.pb
    POSITION = 3,   // PositionConfig       → /config/position.pb
    POWER    = 4,   // PowerConfig          → /config/power.pb
    GAME     = 5,   // GameConfig           → /config/game.pb
    SECURITY = 6,   // SecurityConfig       → /config/security.pb
    AUDIO    = 7,   // AudioConfig (Phase 7)→ /config/audio.pb
    _COUNT   = 8,
};

// LittleFS path for each config group (§9.4.4).
inline const char* configGroupPath(ConfigGroup g) {
    switch (g) {
        case ConfigGroup::DEVICE:    return "/config/device.pb";
        case ConfigGroup::LORA:      return "/config/lora.pb";
        case ConfigGroup::CHANNELS:  return "/config/channels.pb";
        case ConfigGroup::POSITION:  return "/config/position.pb";
        case ConfigGroup::POWER:     return "/config/power.pb";
        case ConfigGroup::GAME:      return "/config/game.pb";
        case ConfigGroup::SECURITY:  return "/config/security.pb";
        case ConfigGroup::AUDIO:     return "/config/audio.pb";
        default:                     return "/config/unknown.pb";
    }
}

// Groups that require a radio restart when changed (§9.4.2).
// After a commit of any of these groups, task_config_apply must perform:
// pause → drain → reinit radio → rekey channels → resume (≤ 300 ms).
inline bool groupRequiresRadioRestart(ConfigGroup g) {
    return g == ConfigGroup::LORA || g == ConfigGroup::CHANNELS;
}

} // namespace mash
