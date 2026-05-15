// MASH - Mesh Airsoft Situational Hub
// firmware/test/test_config/test_config.cpp - Config persistence and nanopb tests
// Planning: §9.4, §19.1 | Roadmap: Phase 1 exit criteria

#include <unity.h>

#include "config/ConfigGroups.h"
#include "config/FlashStore.h"
#include "config/Defaults.h"
#include "mash/config.pb.h"

#include <pb_encode.h>
#include <pb_decode.h>

#include <cstring>

using namespace mash;
using namespace mash::config;

static uint8_t s_encoded[512];
static uint8_t s_readback[512];

void setUp() {
    FlashStore::init();
    FlashStore::eraseAll();
    memset(s_encoded, 0, sizeof(s_encoded));
    memset(s_readback, 0, sizeof(s_readback));
}

void tearDown() {
    FlashStore::eraseAll();
}

template <typename Message>
static size_t encodeMessage(const pb_msgdesc_t* fields, const Message& message) {
    pb_ostream_t stream = pb_ostream_from_buffer(s_encoded, sizeof(s_encoded));
    TEST_ASSERT_TRUE(pb_encode(&stream, fields, &message));
    TEST_ASSERT_GREATER_THAN_UINT32(0, stream.bytes_written);
    return stream.bytes_written;
}

template <typename Message>
static void decodeMessage(const pb_msgdesc_t* fields, const uint8_t* data, size_t len, Message& message) {
    pb_istream_t stream = pb_istream_from_buffer(data, len);
    TEST_ASSERT_TRUE(pb_decode(&stream, fields, &message));
}

static void test_device_roundtrip() {
    mash_DeviceConfig out = mash_DeviceConfig_init_zero;
    strncpy(out.long_name, defaults::DEVICE_LONG_NAME, sizeof(out.long_name) - 1);
    strncpy(out.short_name, defaults::DEVICE_SHORT_NAME, sizeof(out.short_name) - 1);

    size_t len = encodeMessage(mash_DeviceConfig_fields, out);
    mash_DeviceConfig in = mash_DeviceConfig_init_zero;
    decodeMessage(mash_DeviceConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL_STRING(defaults::DEVICE_LONG_NAME, in.long_name);
    TEST_ASSERT_EQUAL_STRING(defaults::DEVICE_SHORT_NAME, in.short_name);
}

static void test_lora_roundtrip() {
    mash_LoraConfig out = mash_LoraConfig_init_zero;
    out.region = static_cast<mash_LoraRegion>(defaults::LORA_REGION);
    out.tx_power_dbm = defaults::LORA_TX_POWER_DBM;
    out.profiles_count = 5;
    for (pb_size_t i = 0; i < out.profiles_count; i++) {
        strncpy(out.profiles[i].name, defaults::PHY_PROFILES[i].name, sizeof(out.profiles[i].name) - 1);
        out.profiles[i].spreading_factor = defaults::PHY_PROFILES[i].spreading_factor;
        out.profiles[i].bandwidth_hz = defaults::PHY_PROFILES[i].bandwidth_hz;
        out.profiles[i].coding_rate = defaults::PHY_PROFILES[i].coding_rate;
    }

    size_t len = encodeMessage(mash_LoraConfig_fields, out);
    mash_LoraConfig in = mash_LoraConfig_init_zero;
    decodeMessage(mash_LoraConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL(defaults::LORA_REGION, in.region);
    TEST_ASSERT_EQUAL(defaults::LORA_TX_POWER_DBM, in.tx_power_dbm);
    TEST_ASSERT_EQUAL(5, in.profiles_count);
    TEST_ASSERT_EQUAL(11, in.profiles[0].spreading_factor);
    TEST_ASSERT_EQUAL(250000, in.profiles[0].bandwidth_hz);
    TEST_ASSERT_EQUAL(5, in.profiles[0].coding_rate);
    TEST_ASSERT_EQUAL_STRING("Medium/Slow", in.profiles[3].name);
}

static void test_channels_roundtrip() {
    mash_ChannelConfig out = mash_ChannelConfig_init_zero;
    out.slots_count = defaults::CHANNEL_COUNT;
    for (pb_size_t i = 0; i < out.slots_count; i++) {
        const auto& def = defaults::CHANNEL_SLOTS[i];
        out.slots[i].index = def.index;
        strncpy(out.slots[i].name, def.name, sizeof(out.slots[i].name) - 1);
        out.slots[i].role = static_cast<mash_ChannelRole>(def.role);
        out.slots[i].uplink_enabled = def.uplink_enabled;
        out.slots[i].downlink_enabled = def.downlink_enabled;
        out.slots[i].position_precision = def.position_precision;
        out.slots[i].phy_profile_index = def.phy_profile_index;
    }

    size_t len = encodeMessage(mash_ChannelConfig_fields, out);
    mash_ChannelConfig in = mash_ChannelConfig_init_zero;
    decodeMessage(mash_ChannelConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL(defaults::CHANNEL_COUNT, in.slots_count);
    TEST_ASSERT_EQUAL(mash_ChannelRole_PRIMARY, in.slots[0].role);
    TEST_ASSERT_EQUAL_STRING("MASH", in.slots[0].name);
    TEST_ASSERT_EQUAL(mash_ChannelRole_SECONDARY, in.slots[1].role);
    TEST_ASSERT_EQUAL_STRING("Audio", in.slots[2].name);
    TEST_ASSERT_EQUAL(1, in.slots[2].phy_profile_index);
}

static void test_position_roundtrip() {
    mash_PositionConfig out = mash_PositionConfig_init_zero;
    out.broadcast_interval_secs = defaults::POSITION_BROADCAST_INTERVAL_SECS;
    out.gps_precision_bits = defaults::POSITION_GPS_PRECISION_BITS;
    out.smart_broadcast = defaults::POSITION_SMART_BROADCAST;
    out.movement_threshold_m = defaults::POSITION_MOVEMENT_THRESHOLD_M;
    out.min_tx_interval_secs = defaults::POSITION_MIN_TX_INTERVAL_SECS;

    size_t len = encodeMessage(mash_PositionConfig_fields, out);
    mash_PositionConfig in = mash_PositionConfig_init_zero;
    decodeMessage(mash_PositionConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL(defaults::POSITION_BROADCAST_INTERVAL_SECS, in.broadcast_interval_secs);
    TEST_ASSERT_EQUAL(defaults::POSITION_GPS_PRECISION_BITS, in.gps_precision_bits);
    TEST_ASSERT_TRUE(in.smart_broadcast);
    TEST_ASSERT_EQUAL(defaults::POSITION_MOVEMENT_THRESHOLD_M, in.movement_threshold_m);
    TEST_ASSERT_EQUAL(defaults::POSITION_MIN_TX_INTERVAL_SECS, in.min_tx_interval_secs);
}

static void test_power_roundtrip() {
    mash_PowerConfig out = mash_PowerConfig_init_zero;
    out.gps_duty_cycle = defaults::POWER_GPS_DUTY_CYCLE;
    out.gps_on_secs = defaults::POWER_GPS_ON_SECS;
    out.gps_off_secs = defaults::POWER_GPS_OFF_SECS;
    out.display_off_secs = defaults::POWER_DISPLAY_OFF_SECS;

    size_t len = encodeMessage(mash_PowerConfig_fields, out);
    mash_PowerConfig in = mash_PowerConfig_init_zero;
    decodeMessage(mash_PowerConfig_fields, s_encoded, len, in);

    TEST_ASSERT_FALSE(in.gps_duty_cycle);
    TEST_ASSERT_EQUAL(defaults::POWER_GPS_ON_SECS, in.gps_on_secs);
    TEST_ASSERT_EQUAL(defaults::POWER_GPS_OFF_SECS, in.gps_off_secs);
}

static void test_game_roundtrip() {
    mash_GameConfig out = mash_GameConfig_init_zero;
    strncpy(out.callsign, defaults::GAME_CALLSIGN, sizeof(out.callsign) - 1);
    out.team_id = defaults::GAME_TEAM_ID;
    out.player_color_rgb = defaults::GAME_PLAYER_COLOR_RGB;
    out.is_admin = defaults::GAME_IS_ADMIN;

    size_t len = encodeMessage(mash_GameConfig_fields, out);
    mash_GameConfig in = mash_GameConfig_init_zero;
    decodeMessage(mash_GameConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL_STRING(defaults::GAME_CALLSIGN, in.callsign);
    TEST_ASSERT_EQUAL(defaults::GAME_TEAM_ID, in.team_id);
    TEST_ASSERT_EQUAL(defaults::GAME_PLAYER_COLOR_RGB, in.player_color_rgb);
    TEST_ASSERT_FALSE(in.is_admin);
}

static void test_security_roundtrip() {
    mash_SecurityConfig out = mash_SecurityConfig_init_zero;
    out.max_bonded_devices = defaults::SECURITY_MAX_BONDED_DEVICES;

    size_t len = encodeMessage(mash_SecurityConfig_fields, out);
    mash_SecurityConfig in = mash_SecurityConfig_init_zero;
    decodeMessage(mash_SecurityConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL(defaults::SECURITY_MAX_BONDED_DEVICES, in.max_bonded_devices);
}

static void test_audio_roundtrip() {
    mash_AudioConfig out = mash_AudioConfig_init_zero;
    out.audio_channel_slot = defaults::AUDIO_CHANNEL_SLOT;
    out.codec2_mode_bps = defaults::AUDIO_CODEC2_BPS;
    out.ptt_enabled = defaults::AUDIO_PTT_ENABLED;
    out.frame_count = defaults::AUDIO_FRAME_COUNT;

    size_t len = encodeMessage(mash_AudioConfig_fields, out);
    mash_AudioConfig in = mash_AudioConfig_init_zero;
    decodeMessage(mash_AudioConfig_fields, s_encoded, len, in);

    TEST_ASSERT_EQUAL(defaults::AUDIO_CHANNEL_SLOT, in.audio_channel_slot);
    TEST_ASSERT_EQUAL(defaults::AUDIO_CODEC2_BPS, in.codec2_mode_bps);
    TEST_ASSERT_FALSE(in.ptt_enabled);
    TEST_ASSERT_EQUAL(defaults::AUDIO_FRAME_COUNT, in.frame_count);
}

static void test_flash_store_roundtrip_all_phase1_groups() {
    const uint8_t payload[] = {0x0A, 0x04, 'M', 'A', 'S', 'H'};
    const ConfigGroup groups[] = {
        ConfigGroup::DEVICE,
        ConfigGroup::LORA,
        ConfigGroup::CHANNELS,
        ConfigGroup::POSITION,
        ConfigGroup::POWER,
        ConfigGroup::GAME,
        ConfigGroup::SECURITY,
        ConfigGroup::AUDIO,
    };

    for (ConfigGroup group : groups) {
        TEST_ASSERT_TRUE(FlashStore::write(group, payload, sizeof(payload)));
        TEST_ASSERT_TRUE(FlashStore::exists(group));
        size_t len = FlashStore::read(group, s_readback, sizeof(s_readback));
        TEST_ASSERT_EQUAL(sizeof(payload), len);
        TEST_ASSERT_EQUAL_MEMORY(payload, s_readback, sizeof(payload));
        TEST_ASSERT_TRUE(FlashStore::erase(group));
        TEST_ASSERT_FALSE(FlashStore::exists(group));
    }
}

static void test_group_paths_and_restart_metadata() {
    TEST_ASSERT_EQUAL_STRING("/config/device.pb", configGroupPath(ConfigGroup::DEVICE));
    TEST_ASSERT_EQUAL_STRING("/config/lora.pb", configGroupPath(ConfigGroup::LORA));
    TEST_ASSERT_EQUAL_STRING("/config/security.pb", configGroupPath(ConfigGroup::SECURITY));
    TEST_ASSERT_EQUAL_STRING("/config/audio.pb", configGroupPath(ConfigGroup::AUDIO));
    TEST_ASSERT_TRUE(groupRequiresRadioRestart(ConfigGroup::LORA));
    TEST_ASSERT_TRUE(groupRequiresRadioRestart(ConfigGroup::CHANNELS));
    TEST_ASSERT_FALSE(groupRequiresRadioRestart(ConfigGroup::DEVICE));
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_device_roundtrip);
    RUN_TEST(test_lora_roundtrip);
    RUN_TEST(test_channels_roundtrip);
    RUN_TEST(test_position_roundtrip);
    RUN_TEST(test_power_roundtrip);
    RUN_TEST(test_game_roundtrip);
    RUN_TEST(test_security_roundtrip);
    RUN_TEST(test_audio_roundtrip);
    RUN_TEST(test_flash_store_roundtrip_all_phase1_groups);
    RUN_TEST(test_group_paths_and_restart_metadata);
    return UNITY_END();
}
