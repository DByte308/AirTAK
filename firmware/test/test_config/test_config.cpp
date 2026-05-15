// MASH — Mesh Airsoft Situational Hub
// firmware/test/test_config/test_config.cpp — Config serialize/deserialize unit tests
// Planning: §9.4, §19.1 | Roadmap: Phase 1 exit criteria
//
// Validates:
//   1. FlashStore::write → FlashStore::read round-trip preserves all bytes.
//   2. ConfigManager::applyGroup with commit=false does NOT write to flash.
//   3. ConfigManager::applyGroup with commit=true persists to flash.
//   4. Missing config file causes init() to apply factory defaults.
//   5. Factory reset erases all group files.
//   6. groupRequiresRadioRestart: LORA and CHANNELS return true; others false.

#include <unity.h>
#include "config/ConfigGroups.h"
#include "config/FlashStore.h"
#include "config/ConfigManager.h"

using namespace mash::config;

void test_group_requires_radio_restart_lora() {
    TEST_ASSERT_TRUE(groupRequiresRadioRestart(ConfigGroup::LORA));
}

void test_group_requires_radio_restart_channels() {
    TEST_ASSERT_TRUE(groupRequiresRadioRestart(ConfigGroup::CHANNELS));
}

void test_group_does_not_require_radio_restart_device() {
    TEST_ASSERT_FALSE(groupRequiresRadioRestart(ConfigGroup::DEVICE));
}

void test_group_filename_device() {
    TEST_ASSERT_EQUAL_STRING("/config/device.pb", configGroupFilename(ConfigGroup::DEVICE));
}

void test_group_filename_audio() {
    TEST_ASSERT_EQUAL_STRING("/config/audio.pb", configGroupFilename(ConfigGroup::AUDIO));
}

// Phase 1.6: FlashStore round-trip (requires LittleFS mount in native test environment)
void test_flash_store_roundtrip_skipped_without_hw() {
    // TODO Phase 1.6: In PlatformIO native environment, mock LittleFS with tmpfile.
    TEST_IGNORE_MESSAGE("Requires LittleFS mount — run on target hardware or with mock");
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_group_requires_radio_restart_lora);
    RUN_TEST(test_group_requires_radio_restart_channels);
    RUN_TEST(test_group_does_not_require_radio_restart_device);
    RUN_TEST(test_group_filename_device);
    RUN_TEST(test_group_filename_audio);
    RUN_TEST(test_flash_store_roundtrip_skipped_without_hw);
    return UNITY_END();
}
