// MASH — Mesh Airsoft Situational Hub
// firmware/src/config/ConfigManager.cpp — Hot-reload config engine implementation
// Planning: §9.4, §9.4.2 | Roadmap: 2.8

#include "config/ConfigManager.h"
#include "config/FlashStore.h"
#include "config/ConfigGroups.h"
#include "config/Defaults.h"
#include "hal/Radio.h"
#include "mesh/ChannelManager.h"
#include "config.h"
#include "mash/config.pb.h"
#include <pb_encode.h>
#include <FreeRTOS.h>
#include <queue.h>
#include <cstring>
#include <Arduino.h>

extern QueueHandle_t g_config_change_queue;

namespace mash {
namespace config {

static ConfigChangedCallback s_callback = nullptr;
static bool                  s_radio_restart_pending = false;

// In-memory raw protobuf buffers for each config group (max 512 bytes per group)
constexpr size_t CONFIG_BUF_SIZE = 512;
static uint8_t  s_buffers[CONFIG_GROUP_COUNT][CONFIG_BUF_SIZE];
static size_t   s_sizes[CONFIG_GROUP_COUNT];

// ── Protobuf structural validation ───────────────────────────────────────────

namespace proto {

// Basic structural validation: scan buf as a proto stream; return false if
// any varint overruns or length-delimited field exceeds buf boundaries.
static bool isValid(const uint8_t* buf, size_t len) {
    size_t pos = 0;
    while (pos < len) {
        // Read tag varint
        uint32_t tag = 0; uint8_t shift = 0;
        while (pos < len) {
            uint8_t b = buf[pos++];
            tag |= (uint32_t)(b & 0x7F) << shift;
            if (!(b & 0x80)) break;
            shift += 7;
            if (shift >= 32) return false; // varint overflow
        }
        uint8_t wire = tag & 0x07;
        switch (wire) {
            case 0: { // varint
                while (pos < len && (buf[pos++] & 0x80)) {}
                break;
            }
            case 1: pos += 8; break; // 64-bit
            case 2: { // length-delimited
                uint32_t sz = 0; shift = 0;
                while (pos < len) {
                    uint8_t b = buf[pos++];
                    sz |= (uint32_t)(b & 0x7F) << shift;
                    if (!(b & 0x80)) break;
                    shift += 7;
                    if (shift >= 32) return false;
                }
                if (pos + sz > len) return false;
                pos += sz;
                break;
            }
            case 5: pos += 4; break; // 32-bit
            default: return false; // unsupported wire type
        }
    }
    return pos == len;
}

} // namespace proto

// ── Factory default encoders ─────────────────────────────────────────────────

static size_t encodeMessage(const pb_msgdesc_t* fields, const void* msg,
                            uint8_t* buf, size_t buf_len) {
    pb_ostream_t stream = pb_ostream_from_buffer(buf, buf_len);
    if (!pb_encode(&stream, fields, msg)) return 0;
    return stream.bytes_written;
}

static void copyString(char* dest, size_t dest_len, const char* src) {
    if (dest_len == 0) return;
    strncpy(dest, src, dest_len - 1);
    dest[dest_len - 1] = '\0';
}

static size_t encodeDeviceConfig(uint8_t* buf, size_t buf_len) {
    mash_DeviceConfig msg = mash_DeviceConfig_init_zero;
    copyString(msg.long_name, sizeof(msg.long_name), defaults::DEVICE_LONG_NAME);
    copyString(msg.short_name, sizeof(msg.short_name), defaults::DEVICE_SHORT_NAME);
    return encodeMessage(mash_DeviceConfig_fields, &msg, buf, buf_len);
}

static size_t encodeLoraConfig(uint8_t* buf, size_t buf_len) {
    mash_LoraConfig msg = mash_LoraConfig_init_zero;
    msg.region = static_cast<mash_LoraRegion>(defaults::LORA_REGION);
    msg.tx_power_dbm = defaults::LORA_TX_POWER_DBM;
    msg.profiles_count = 5;
    for (uint8_t i = 0; i < msg.profiles_count; i++) {
        const auto& ph = defaults::PHY_PROFILES[i];
        copyString(msg.profiles[i].name, sizeof(msg.profiles[i].name), ph.name);
        msg.profiles[i].spreading_factor = ph.spreading_factor;
        msg.profiles[i].bandwidth_hz = ph.bandwidth_hz;
        msg.profiles[i].coding_rate = ph.coding_rate;
    }
    return encodeMessage(mash_LoraConfig_fields, &msg, buf, buf_len);
}

static size_t encodeChannelConfig(uint8_t* buf, size_t buf_len) {
    mash_ChannelConfig msg = mash_ChannelConfig_init_zero;
    msg.slots_count = defaults::CHANNEL_COUNT;
    for (uint8_t i = 0; i < msg.slots_count; i++) {
        const auto& ch = defaults::CHANNEL_SLOTS[i];
        auto& slot = msg.slots[i];
        slot.index = ch.index;
        copyString(slot.name, sizeof(slot.name), ch.name);
        slot.psk.size = 0;
        slot.role = static_cast<mash_ChannelRole>(ch.role);
        slot.uplink_enabled = ch.uplink_enabled;
        slot.downlink_enabled = ch.downlink_enabled;
        slot.position_precision = ch.position_precision;
        slot.phy_profile_index = ch.phy_profile_index;
    }
    return encodeMessage(mash_ChannelConfig_fields, &msg, buf, buf_len);
}

static size_t encodePositionConfig(uint8_t* buf, size_t buf_len) {
    mash_PositionConfig msg = mash_PositionConfig_init_zero;
    msg.broadcast_interval_secs = defaults::POSITION_BROADCAST_INTERVAL_SECS;
    msg.gps_precision_bits = defaults::POSITION_GPS_PRECISION_BITS;
    msg.smart_broadcast = defaults::POSITION_SMART_BROADCAST;
    msg.movement_threshold_m = defaults::POSITION_MOVEMENT_THRESHOLD_M;
    msg.min_tx_interval_secs = defaults::POSITION_MIN_TX_INTERVAL_SECS;
    msg.position_hop_limit = 0;
    return encodeMessage(mash_PositionConfig_fields, &msg, buf, buf_len);
}

static size_t encodePowerConfig(uint8_t* buf, size_t buf_len) {
    mash_PowerConfig msg = mash_PowerConfig_init_zero;
    msg.gps_duty_cycle = defaults::POWER_GPS_DUTY_CYCLE;
    msg.gps_on_secs = defaults::POWER_GPS_ON_SECS;
    msg.gps_off_secs = defaults::POWER_GPS_OFF_SECS;
    msg.display_off_secs = defaults::POWER_DISPLAY_OFF_SECS;
    return encodeMessage(mash_PowerConfig_fields, &msg, buf, buf_len);
}

static size_t encodeGameConfig(uint8_t* buf, size_t buf_len) {
    mash_GameConfig msg = mash_GameConfig_init_zero;
    copyString(msg.callsign, sizeof(msg.callsign), defaults::GAME_CALLSIGN);
    msg.team_id = defaults::GAME_TEAM_ID;
    msg.player_color_rgb = defaults::GAME_PLAYER_COLOR_RGB;
    msg.is_admin = defaults::GAME_IS_ADMIN;
    return encodeMessage(mash_GameConfig_fields, &msg, buf, buf_len);
}

static size_t encodeSecurityConfig(uint8_t* buf, size_t buf_len) {
    mash_SecurityConfig msg = mash_SecurityConfig_init_zero;
    msg.max_bonded_devices = defaults::SECURITY_MAX_BONDED_DEVICES;
    return encodeMessage(mash_SecurityConfig_fields, &msg, buf, buf_len);
}

static size_t encodeAudioConfig(uint8_t* buf, size_t buf_len) {
    mash_AudioConfig msg = mash_AudioConfig_init_zero;
    msg.audio_channel_slot = defaults::AUDIO_CHANNEL_SLOT;
    msg.codec2_mode_bps = defaults::AUDIO_CODEC2_BPS;
    msg.ptt_enabled = defaults::AUDIO_PTT_ENABLED;
    msg.frame_count = defaults::AUDIO_FRAME_COUNT;
    return encodeMessage(mash_AudioConfig_fields, &msg, buf, buf_len);
}

static size_t encodeFactoryDefaults(ConfigGroup g, uint8_t* buf, size_t buf_len) {
    switch (g) {
        case ConfigGroup::DEVICE:   return encodeDeviceConfig   (buf, buf_len);
        case ConfigGroup::LORA:     return encodeLoraConfig      (buf, buf_len);
        case ConfigGroup::CHANNELS: return encodeChannelConfig   (buf, buf_len);
        case ConfigGroup::POSITION: return encodePositionConfig  (buf, buf_len);
        case ConfigGroup::POWER:    return encodePowerConfig     (buf, buf_len);
        case ConfigGroup::GAME:     return encodeGameConfig      (buf, buf_len);
        case ConfigGroup::SECURITY: return encodeSecurityConfig  (buf, buf_len);
        case ConfigGroup::AUDIO:    return encodeAudioConfig     (buf, buf_len);
        default:                    return 0;
    }
}

// ── ConfigManager public API ─────────────────────────────────────────────────

void ConfigManager::init() {
    for (uint8_t i = 0; i < CONFIG_GROUP_COUNT; i++) {
        ConfigGroup g = static_cast<ConfigGroup>(i);
        size_t n = FlashStore::read(g, s_buffers[i], CONFIG_BUF_SIZE);
        if (n == 0 || !proto::isValid(s_buffers[i], n)) {
            // First boot or corrupted file — encode factory defaults in-memory.
            s_sizes[i] = encodeFactoryDefaults(g, s_buffers[i], CONFIG_BUF_SIZE);
        } else {
            s_sizes[i] = n;
        }
    }
}

int ConfigManager::applyGroup(ConfigGroup group, const uint8_t* pb_data, size_t pb_len, bool commit) {
    if (pb_len > CONFIG_BUF_SIZE) return -1;

    // Structural proto validation: reject malformed streams before storing.
    if (pb_len > 0 && !proto::isValid(pb_data, pb_len)) return -2;

    uint8_t idx = static_cast<uint8_t>(group);
    memcpy(s_buffers[idx], pb_data, pb_len);
    s_sizes[idx] = pb_len;

    if (commit) {
        FlashStore::write(group, pb_data, pb_len);
        if (config::groupRequiresRadioRestart(group)) {
            s_radio_restart_pending = true;
            // Signal task_config_apply to run radioRestartProcedure() on its own stack
            // (priority LOW/2) so the BLE event loop is not blocked during the
            // ≤300 ms radio pause/reinit/resume sequence (§9.4.2).
            void* sentinel = nullptr;
            if (::g_config_change_queue) xQueueSend(::g_config_change_queue, &sentinel, 0);
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
    s_sizes[idx] = encodeFactoryDefaults(group, s_buffers[idx], CONFIG_BUF_SIZE);
    if (s_callback) s_callback(group);
}

void ConfigManager::registerCallback(ConfigChangedCallback cb) { s_callback = cb; }

void ConfigManager::radioRestartProcedure() {
    // §9.4.2 Radio restart sequence. Target ≤ 300 ms.
    Radio::pause();
    Radio::reinit();
    ChannelManager::rekey();
    Radio::resume();
    s_radio_restart_pending = false;
}

bool ConfigManager::isRadioRestartPending() { return s_radio_restart_pending; }

// Device long name: scan DEVICE group buffer for tag 0x12 (field 2, string).
static char s_device_long_name[65];

const char* ConfigManager::deviceLongName() {
    uint8_t idx = static_cast<uint8_t>(ConfigGroup::DEVICE);
    if (s_sizes[idx] == 0) return defaults::DEVICE_LONG_NAME;

    const uint8_t* buf = s_buffers[idx];
    size_t n = s_sizes[idx];
    for (size_t i = 0; i + 1 < n; i++) {
        if (buf[i] == 0x0A) { // field 1, wire 2 = DeviceConfig.long_name
            uint8_t len = buf[i + 1];
            if (i + 2 + len <= n && len < sizeof(s_device_long_name)) {
                memcpy(s_device_long_name, buf + i + 2, len);
                s_device_long_name[len] = '\0';
                return s_device_long_name;
            }
        }
    }
    return defaults::DEVICE_LONG_NAME;
}

// ── LoRa parameter accessors ─────────────────────────────────────────────────
// Sequential proto parsers so Radio::reinit() reads the committed config rather
// than compile-time defaults. Required for hot-reload (§9.4.2, Roadmap 2.8).

// Helper: advance pos over one proto field (any wire type). Returns false on error.
static bool skipProtoField(const uint8_t* buf, size_t n, size_t& pos, uint8_t wire) {
    switch (wire) {
        case 0: // varint
            while (pos < n) { if (!(buf[pos++] & 0x80)) return true; }
            return false;
        case 1: // 64-bit
            if (pos + 8 > n) return false;
            pos += 8;
            return true;
        case 2: { // length-delimited
            uint32_t sz = 0; uint8_t sh = 0;
            while (pos < n) { uint8_t b = buf[pos++]; sz |= (uint32_t)(b & 0x7F) << sh; if (!(b & 0x80)) break; sh += 7; }
            if (pos + sz > n) return false;
            pos += sz;
            return true;
        }
        case 5: // 32-bit
            if (pos + 4 > n) return false;
            pos += 4;
            return true;
        default: return false;
    }
}

// Helper: read a varint from buf at pos. Returns false on overrun.
static bool readProtoVarint(const uint8_t* buf, size_t n, size_t& pos, uint32_t& out) {
    out = 0; uint8_t shift = 0;
    while (pos < n && shift < 32) {
        uint8_t b = buf[pos++];
        out |= (uint32_t)(b & 0x7F) << shift;
        if (!(b & 0x80)) return true;
        shift += 7;
    }
    return false;
}

float ConfigManager::loraFrequencyMhz() {
    uint8_t gidx = static_cast<uint8_t>(ConfigGroup::LORA);
    const uint8_t* buf = s_buffers[gidx];
    size_t n = s_sizes[gidx];
    size_t pos = 0;
    while (pos < n) {
        uint32_t tag; if (!readProtoVarint(buf, n, pos, tag)) break;
        uint8_t wire  = tag & 7;
        uint32_t field = tag >> 3;
        if (wire == 0) {
            uint32_t v; if (!readProtoVarint(buf, n, pos, v)) break;
            if (field == 1) return (v == 1) ? (float)LORA_FREQ_US915 : (float)LORA_FREQ_EU868;
        } else { if (!skipProtoField(buf, n, pos, wire)) break; }
    }
    return (float)LORA_FREQ_EU868;
}

uint8_t ConfigManager::loraTxPowerDbm() {
    uint8_t gidx = static_cast<uint8_t>(ConfigGroup::LORA);
    const uint8_t* buf = s_buffers[gidx];
    size_t n = s_sizes[gidx];
    size_t pos = 0;
    while (pos < n) {
        uint32_t tag; if (!readProtoVarint(buf, n, pos, tag)) break;
        uint8_t wire  = tag & 7;
        uint32_t field = tag >> 3;
        if (wire == 0) {
            uint32_t v; if (!readProtoVarint(buf, n, pos, v)) break;
            if (field == 2 && v > 0) return (uint8_t)v;
        } else { if (!skipProtoField(buf, n, pos, wire)) break; }
    }
    return defaults::LORA_TX_POWER_DBM;
}

uint8_t ConfigManager::primarySlotPhyProfileIndex() {
    // CHANNELS buffer: repeated slot sub-messages on field 1 (tag 0x0A, wire 2).
    // Each slot sub-message has: field 4 = role (varint), field 8 = phy_profile_index (varint).
    // Find the first slot with role=1 (PRIMARY) and return its phy_profile_index.
    uint8_t gidx = static_cast<uint8_t>(ConfigGroup::CHANNELS);
    const uint8_t* buf = s_buffers[gidx];
    size_t n = s_sizes[gidx];
    size_t pos = 0;
    while (pos < n) {
        uint32_t tag; if (!readProtoVarint(buf, n, pos, tag)) break;
        uint8_t wire  = tag & 7;
        uint32_t field = tag >> 3;
        if (wire == 2 && field == 1) {
            // Slot sub-message length
            uint32_t slen; if (!readProtoVarint(buf, n, pos, slen)) break;
            size_t end = pos + slen;
            if (end > n) break;
            uint8_t slot_role = 0, slot_phy = 0;
            size_t sp = pos;
            while (sp < end) {
                uint32_t stag; if (!readProtoVarint(buf, end, sp, stag)) break;
                uint8_t sw = stag & 7;
                uint32_t sf = stag >> 3;
                if (sw == 0) {
                    uint32_t v; if (!readProtoVarint(buf, end, sp, v)) break;
                    if (sf == 4) slot_role = (uint8_t)v;
                    if (sf == 8) slot_phy  = (uint8_t)v;
                } else { if (!skipProtoField(buf, end, sp, sw)) break; }
            }
            if (slot_role == 1 /* PRIMARY */) return slot_phy;
            pos = end;
        } else { if (!skipProtoField(buf, n, pos, wire)) break; }
    }
    return 0; // default: Long/Mesh (Profile 0)
}

} // namespace config
} // namespace mash
