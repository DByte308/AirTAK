// Exercise the actual runtime Battery.cpp consumer with recording GPIO/ADC
// functions. Deliberately scrambled SDK-enum stand-ins detect unsafe enum casts.
#include <unity.h>
#include "Arduino.h"
#include <vector>
#include "platform/BoardDescriptor.h"

#ifndef INPUT
#define INPUT 2
#endif
struct PinCall { int pin; int value; };
static std::vector<PinCall> modes, writes;
static std::vector<int> reads;
static int resolutionCalls = 0;
static float overrideMultiplier = 0.0f;
enum adc_attenuation_t { ADC_0db = 11, ADC_2_5db = 41, ADC_6db = 22, ADC_11db = 73 };
static int attenuation = -1;
static const airtak::BoardDescriptor* profile = &airtak::unknownBoard();
static void recordMode(int pin, int value) { modes.push_back({pin, value}); }
static void recordWrite(int pin, int value) { writes.push_back({pin, value}); }
static int recordRead(int pin) { reads.push_back(pin); return 1000; }
static void recordResolution(int) { ++resolutionCalls; }
static void recordAttenuation(adc_attenuation_t value) { attenuation = value; }

#define AIRTAK_RUNTIME_BOARD 1
#define pinMode recordMode
#define digitalWrite recordWrite
#define analogRead recordRead
#define analogReadResolution recordResolution
#define analogSetAttenuation recordAttenuation
#include "hal/Battery.cpp"
#undef pinMode
#undef digitalWrite
#undef analogRead
#undef analogReadResolution
#undef analogSetAttenuation

namespace airtak::platform {
const BoardDescriptor& selectedBoard() { return *profile; }
}
namespace airtak::config {
float ConfigManager::powerAdcMultiplier() { return overrideMultiplier; }
}
namespace airtak {
void Display::markDirty(uint16_t) {}
}
void setUp() {
    modes.clear(); writes.clear(); reads.clear();
    resolutionCalls = 0; attenuation = -1; overrideMultiplier = 0.0f;
    profile = &airtak::unknownBoard();
}
void tearDown() {}
static void test_unknown_profile_touches_no_gpio_or_adc() {
    airtak::Battery::init();
    airtak::Battery::sample(nullptr);
    TEST_ASSERT_TRUE(modes.empty());
    TEST_ASSERT_TRUE(writes.empty());
    TEST_ASSERT_TRUE(reads.empty());
    TEST_ASSERT_EQUAL_INT(0, resolutionCalls);
    TEST_ASSERT_EQUAL_INT(-1, attenuation);
}
static void test_v4_divider_is_active_high_with_correct_attenuation() {
    profile = &airtak::board("heltec_v4");
    airtak::Battery::init();
    TEST_ASSERT_EQUAL_INT(ADC_2_5db, attenuation);
    TEST_ASSERT_EQUAL_UINT(1, writes.size());
    TEST_ASSERT_EQUAL_INT(37, writes[0].pin);
    TEST_ASSERT_EQUAL_INT(HIGH, writes[0].value);
}
static void test_v3_divider_is_active_low_with_correct_attenuation() {
    profile = &airtak::board("heltec_v3");
    airtak::Battery::init();
    TEST_ASSERT_EQUAL_INT(ADC_2_5db, attenuation);
    TEST_ASSERT_EQUAL_UINT(1, writes.size());
    TEST_ASSERT_EQUAL_INT(37, writes[0].pin);
    TEST_ASSERT_EQUAL_INT(LOW, writes[0].value);
}
static void test_default_attenuation_and_absent_divider_do_not_drive_sentinel() {
    for (const char* id : {"t3s3", "t_deck"}) {
        modes.clear(); writes.clear();
        profile = &airtak::board(id);
        airtak::Battery::init();
        TEST_ASSERT_EQUAL_INT(ADC_11db, attenuation);
        TEST_ASSERT_TRUE(writes.empty());
        TEST_ASSERT_EQUAL_UINT(1, modes.size());
        TEST_ASSERT_EQUAL_INT(profile->vbatPin, modes[0].pin);
    }
}
static void test_every_portable_attenuation_maps_by_meaning_not_integer_value() {
    using airtak::AdcAttenuationDb;
    airtak::BoardDescriptor synthetic = airtak::board("heltec_v4");
    const AdcAttenuationDb portable[] = {AdcAttenuationDb::None, AdcAttenuationDb::Db0,
        AdcAttenuationDb::Db2_5, AdcAttenuationDb::Db6, AdcAttenuationDb::Db11};
    const int expected[] = {ADC_11db, ADC_0db, ADC_2_5db, ADC_6db, ADC_11db};
    profile = &synthetic;
    for (unsigned i = 0; i < 5; ++i) {
        synthetic.adcAttenuation = portable[i];
        airtak::Battery::init();
        TEST_ASSERT_EQUAL_INT(expected[i], attenuation);
    }
}
static void test_sample_uses_selected_adc_and_manual_multiplier_override() {
    profile = &airtak::board("t_deck");
    overrideMultiplier = 2.0f;
    airtak::Battery::init();
    airtak::Battery::sample(nullptr);
    TEST_ASSERT_EQUAL_UINT(airtak::BATTERY_ADC_OVERSAMPLE, reads.size());
    for (int pin : reads) TEST_ASSERT_EQUAL_INT(4, pin);
    const uint32_t expected = uint32_t(1000.0f * 3300.0f * 2.0f * 2.0f / 4095.0f);
    TEST_ASSERT_EQUAL_UINT32(expected, airtak::Battery::voltageMillivolts());
}
int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_unknown_profile_touches_no_gpio_or_adc);
    RUN_TEST(test_v4_divider_is_active_high_with_correct_attenuation);
    RUN_TEST(test_v3_divider_is_active_low_with_correct_attenuation);
    RUN_TEST(test_default_attenuation_and_absent_divider_do_not_drive_sentinel);
    RUN_TEST(test_every_portable_attenuation_maps_by_meaning_not_integer_value);
    RUN_TEST(test_sample_uses_selected_adc_and_manual_multiplier_override);
    return UNITY_END();
}
