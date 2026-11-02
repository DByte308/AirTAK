#include <unity.h>
#include <vector>
#include <cstdint>
#include <pb_encode.h>
#include "ui/OledPanelPower.h"
#include "ui/DisplayIdleTimer.h"
#include "ui/DisplayWakeInputGate.h"
#include "ui/PairingDisplayActivity.h"
#include "config/PowerDisplayConfig.h"

using airtak::ui::OledPanelPower;
using airtak::ui::DisplayIdleTimer;
using Action = DisplayIdleTimer::Action;
static OledPanelPower panel;
static std::vector<uint8_t> commands;
static void send(uint8_t value) { commands.push_back(value); }
void setUp() { panel.begin(false); commands.clear(); airtak::ui::consumePairingDisplayActivity(); }
void tearDown() {}

static void test_uninitialised_panel_is_never_touched() {
    panel.setBrightness(50, send); panel.sleep(send); panel.wake(send);
    TEST_ASSERT_TRUE(commands.empty());
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_percentage_maps_to_oled_contrast() {
    panel.begin(true); panel.setBrightness(50, send);
    TEST_ASSERT_EQUAL_UINT(3, commands.size());
    TEST_ASSERT_EQUAL_HEX8(0x81, commands[0]);
    TEST_ASSERT_EQUAL_UINT8(64, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAF, commands[2]);
    TEST_ASSERT_TRUE(panel.canRender());
}
static void test_brightness_is_clamped_to_100() {
    panel.begin(true); panel.setBrightness(255, send);
    TEST_ASSERT_EQUAL_UINT8(255, commands[1]);
}
static void test_zero_brightness_really_turns_panel_off() {
    panel.begin(true); panel.setBrightness(0, send);
    TEST_ASSERT_EQUAL_HEX8(0xAE, commands.back());
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_sleep_sends_off_command_once() {
    panel.begin(true); panel.sleep(send); panel.sleep(send);
    TEST_ASSERT_EQUAL_UINT(1, commands.size());
    TEST_ASSERT_EQUAL_HEX8(0xAE, commands[0]);
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_brightness_changes_do_not_wake_sleeping_panel() {
    panel.begin(true); panel.sleep(send); commands.clear(); panel.setBrightness(25, send);
    TEST_ASSERT_EQUAL_UINT(2, commands.size());
    TEST_ASSERT_EQUAL_HEX8(0x81, commands[0]);
    TEST_ASSERT_EQUAL_UINT8(16, commands[1]);
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_wake_restores_latest_brightness() {
    panel.begin(true); panel.sleep(send); panel.setBrightness(25, send); commands.clear(); panel.wake(send);
    TEST_ASSERT_EQUAL_UINT(3, commands.size());
    TEST_ASSERT_EQUAL_UINT8(16, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAF, commands[2]);
    TEST_ASSERT_TRUE(panel.canRender());
}
static void test_zero_brightness_stays_off_after_wake() {
    panel.begin(true); panel.setBrightness(0, send); panel.sleep(send); commands.clear(); panel.wake(send);
    TEST_ASSERT_EQUAL_HEX8(0xAE, commands.back());
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_failed_reinitialisation_disables_panel_io() {
    panel.begin(true); panel.begin(false); panel.wake(send);
    TEST_ASSERT_TRUE(commands.empty());
    TEST_ASSERT_FALSE(panel.ready());
}
static void test_timeout_boundary_and_one_shot_sleep() {
    DisplayIdleTimer timer(100, 1);
    TEST_ASSERT_TRUE(timer.update(1099, false, 1) == Action::None);
    TEST_ASSERT_TRUE(timer.update(1100, false, 1) == Action::Sleep);
    TEST_ASSERT_TRUE(timer.update(2000, false, 1) == Action::None);
    TEST_ASSERT_TRUE(timer.asleep());
}
static void test_button_or_touch_wakes_and_restarts_interval() {
    DisplayIdleTimer timer(0, 1);
    timer.update(1000, false, 1);
    TEST_ASSERT_TRUE(timer.update(1500, true, 1) == Action::Wake);
    TEST_ASSERT_FALSE(timer.asleep());
    TEST_ASSERT_TRUE(timer.update(2499, false, 1) == Action::None);
    TEST_ASSERT_TRUE(timer.update(2500, false, 1) == Action::Sleep);
}
static void test_zero_timeout_never_sleeps() {
    DisplayIdleTimer timer(0, 0);
    TEST_ASSERT_TRUE(timer.update(UINT32_MAX, false, 0) == Action::None);
    TEST_ASSERT_FALSE(timer.asleep());
}
static void test_disabling_timeout_wakes_sleeping_panel() {
    DisplayIdleTimer timer(0, 1); timer.update(1000, false, 1);
    TEST_ASSERT_TRUE(timer.update(2000, false, 0) == Action::Wake);
    TEST_ASSERT_FALSE(timer.asleep());
    TEST_ASSERT_TRUE(timer.update(100000, false, 0) == Action::None);
}
static void test_live_timeout_change_restarts_interval() {
    DisplayIdleTimer timer(0, 1);
    TEST_ASSERT_TRUE(timer.update(900, false, 2) == Action::None);
    TEST_ASSERT_TRUE(timer.update(2899, false, 2) == Action::None);
    TEST_ASSERT_TRUE(timer.update(2900, false, 2) == Action::Sleep);
}
static void test_millis_wraparound_is_safe() {
    DisplayIdleTimer timer(UINT32_MAX - 499, 1);
    TEST_ASSERT_TRUE(timer.update(499, false, 1) == Action::None);
    TEST_ASSERT_TRUE(timer.update(500, false, 1) == Action::Sleep);
}
static void test_large_timeout_does_not_overflow_to_milliseconds() {
    DisplayIdleTimer timer(0, 4294968);
    TEST_ASSERT_TRUE(timer.update(1000, false, 4294968) == Action::None);
    TEST_ASSERT_FALSE(timer.asleep());
}
static void test_held_button_keeps_panel_awake() {
    DisplayIdleTimer timer(0, 1);
    for (uint32_t now = 500; now < 10000; now += 500)
        TEST_ASSERT_TRUE(timer.update(now, true, 1) == Action::None);
    TEST_ASSERT_FALSE(timer.asleep());
}
static void test_passkey_is_visible_at_zero_then_restores_off() {
    panel.begin(true); panel.setBrightness(0, send); panel.sleep(send); commands.clear();
    panel.showPrompt(send); panel.wake(send);
    TEST_ASSERT_EQUAL_HEX8(0xAF, commands.back());
    TEST_ASSERT_TRUE(panel.canRender());
    commands.clear(); panel.endPrompt(send);
    TEST_ASSERT_EQUAL_UINT8(0, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAE, commands.back());
    TEST_ASSERT_FALSE(panel.canRender());
}
static void test_brightness_edit_during_prompt_is_restored_after_prompt() {
    panel.begin(true); panel.showPrompt(send); commands.clear(); panel.setBrightness(75, send);
    TEST_ASSERT_TRUE(commands.empty());
    panel.endPrompt(send);
    TEST_ASSERT_EQUAL_UINT8(143, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAF, commands.back());
}
static void test_sleep_clears_prompt_override() {
    panel.begin(true); panel.setBrightness(50, send); panel.showPrompt(send); panel.sleep(send);
    commands.clear(); panel.wake(send);
    TEST_ASSERT_EQUAL_UINT8(64, commands[1]);
}
static void test_prompt_does_not_touch_failed_panel() {
    panel.showPrompt(send); panel.endPrompt(send);
    TEST_ASSERT_TRUE(commands.empty());
}
static void test_pairing_activity_requests_are_consumed_once() {
    TEST_ASSERT_FALSE(airtak::ui::consumePairingDisplayActivity());
    airtak::ui::requestPairingDisplayActivity(); airtak::ui::requestPairingDisplayActivity();
    TEST_ASSERT_TRUE(airtak::ui::consumePairingDisplayActivity());
    TEST_ASSERT_FALSE(airtak::ui::consumePairingDisplayActivity());
}
static void test_awake_button_gestures_are_unchanged() {
    airtak::ui::DisplayWakeInputGate gate;
    TEST_ASSERT_TRUE(gate.update(false, true, false).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, true, true).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, false, true).dispatchButton);
}
static void test_wake_press_is_consumed_until_release() {
    airtak::ui::DisplayWakeInputGate gate;
    TEST_ASSERT_FALSE(gate.update(true, true, false).dispatchButton);
    TEST_ASSERT_FALSE(gate.update(false, true, false).dispatchButton);
    TEST_ASSERT_FALSE(gate.update(false, false, true).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, true, false).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, false, true).dispatchButton);
}
static void test_release_only_wake_event_is_consumed() {
    airtak::ui::DisplayWakeInputGate gate;
    TEST_ASSERT_FALSE(gate.update(true, false, true).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, false, true).dispatchButton);
}
static void test_long_wake_hold_does_not_become_hit_or_shutdown() {
    airtak::ui::DisplayWakeInputGate gate;
    TEST_ASSERT_FALSE(gate.update(true, true, false).dispatchButton);
    for (int event = 0; event < 5; ++event)
        TEST_ASSERT_FALSE(gate.update(false, true, true).dispatchButton);
    TEST_ASSERT_FALSE(gate.update(false, false, true).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, true, true).dispatchButton);
}
static void test_release_without_gesture_does_not_latch_the_gate() {
    airtak::ui::DisplayWakeInputGate gate;
    gate.update(true, true, false);
    TEST_ASSERT_FALSE(gate.update(false, false, false).dispatchButton);
    TEST_ASSERT_TRUE(gate.update(false, false, true).dispatchButton);
}
static void test_wake_touch_does_not_navigate() {
    airtak::ui::DisplayWakeInputGate gate;
    TEST_ASSERT_FALSE(gate.update(true, false, false).dispatchTouch);
    TEST_ASSERT_TRUE(gate.update(false, false, false).dispatchTouch);
}
static void test_idle_timer_and_gate_keep_page_until_second_press() {
    DisplayIdleTimer timer(0, 1);
    airtak::ui::DisplayWakeInputGate gate;
    timer.update(1000, false, 1);
    int page = 2;
    const auto press = gate.update(timer.asleep(), true, false);
    TEST_ASSERT_FALSE(press.dispatchButton);
    TEST_ASSERT_TRUE(timer.update(1200, true, 1) == Action::Wake);
    const auto release = gate.update(timer.asleep(), false, true);
    if (release.dispatchButton) ++page;
    TEST_ASSERT_EQUAL_INT(2, page);
    gate.update(timer.asleep(), true, false);
    const auto secondRelease = gate.update(timer.asleep(), false, true);
    if (secondRelease.dispatchButton) ++page;
    TEST_ASSERT_EQUAL_INT(3, page);
}
static void test_low_brightness_is_darker_without_changing_endpoints() {
    panel.begin(true); panel.setBrightness(25, send);
    TEST_ASSERT_EQUAL_UINT8(16, commands[1]);
    commands.clear(); panel.setBrightness(100, send);
    TEST_ASSERT_EQUAL_UINT8(255, commands[1]);
    commands.clear(); panel.setBrightness(0, send);
    TEST_ASSERT_EQUAL_UINT8(0, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAE, commands.back());
}
static void test_positive_minimum_contrast_does_not_power_off() {
    panel.begin(true); panel.setBrightness(1, send);
    TEST_ASSERT_EQUAL_UINT8(0, commands[1]);
    TEST_ASSERT_EQUAL_HEX8(0xAF, commands.back());
    TEST_ASSERT_TRUE(panel.canRender());
}
static void test_contrast_curve_is_monotonic() {
    panel.begin(true);
    uint8_t previous = 0;
    for (uint16_t percent = 0; percent <= 100; ++percent) {
        commands.clear(); panel.setBrightness(static_cast<uint8_t>(percent), send);
        TEST_ASSERT_GREATER_OR_EQUAL_UINT8(previous, commands[1]);
        previous = commands[1];
    }
    TEST_ASSERT_EQUAL_UINT8(255, previous);
}
static uint8_t encoded[64];
static size_t encodedSize;
static void encodeBrightness(uint32_t brightness) {
    airtak_PowerConfig power = airtak_PowerConfig_init_zero;
    power.brightness_pct = brightness;
    power.display_off_secs = 15;
    pb_ostream_t out = pb_ostream_from_buffer(encoded, sizeof(encoded));
    TEST_ASSERT_TRUE(pb_encode(&out, airtak_PowerConfig_fields, &power));
    encodedSize = out.bytes_written;
}
static void test_proto_omitted_zero_brightness_is_not_factory_100() {
    encodeBrightness(0);
    TEST_ASSERT_EQUAL_UINT8(0, airtak::config::brightnessFromPowerConfig(encoded, encodedSize, 100));
}
static void test_proto_empty_power_snapshot_has_zero_brightness() {
    TEST_ASSERT_EQUAL_UINT8(0, airtak::config::brightnessFromPowerConfig(encoded, 0, 100));
}
static void test_proto_regular_brightness_is_used() {
    encodeBrightness(75);
    TEST_ASSERT_EQUAL_UINT8(75, airtak::config::brightnessFromPowerConfig(encoded, encodedSize, 100));
}
static void test_proto_excess_brightness_is_clamped() {
    encodeBrightness(1000);
    TEST_ASSERT_EQUAL_UINT8(100, airtak::config::brightnessFromPowerConfig(encoded, encodedSize, 100));
}
static void test_proto_malformed_power_uses_fallback() {
    const uint8_t invalid[] = {0x28, 0x80};
    TEST_ASSERT_EQUAL_UINT8(73, airtak::config::brightnessFromPowerConfig(invalid, sizeof(invalid), 73));
}
static void test_proto_null_nonempty_buffer_is_rejected() {
    TEST_ASSERT_EQUAL_UINT8(73, airtak::config::brightnessFromPowerConfig(nullptr, 1, 73));
}
int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_uninitialised_panel_is_never_touched);
    RUN_TEST(test_percentage_maps_to_oled_contrast);
    RUN_TEST(test_brightness_is_clamped_to_100);
    RUN_TEST(test_zero_brightness_really_turns_panel_off);
    RUN_TEST(test_sleep_sends_off_command_once);
    RUN_TEST(test_brightness_changes_do_not_wake_sleeping_panel);
    RUN_TEST(test_wake_restores_latest_brightness);
    RUN_TEST(test_zero_brightness_stays_off_after_wake);
    RUN_TEST(test_failed_reinitialisation_disables_panel_io);
    RUN_TEST(test_timeout_boundary_and_one_shot_sleep);
    RUN_TEST(test_button_or_touch_wakes_and_restarts_interval);
    RUN_TEST(test_zero_timeout_never_sleeps);
    RUN_TEST(test_disabling_timeout_wakes_sleeping_panel);
    RUN_TEST(test_live_timeout_change_restarts_interval);
    RUN_TEST(test_millis_wraparound_is_safe);
    RUN_TEST(test_large_timeout_does_not_overflow_to_milliseconds);
    RUN_TEST(test_held_button_keeps_panel_awake);
    RUN_TEST(test_passkey_is_visible_at_zero_then_restores_off);
    RUN_TEST(test_brightness_edit_during_prompt_is_restored_after_prompt);
    RUN_TEST(test_sleep_clears_prompt_override);
    RUN_TEST(test_prompt_does_not_touch_failed_panel);
    RUN_TEST(test_pairing_activity_requests_are_consumed_once);
    RUN_TEST(test_awake_button_gestures_are_unchanged);
    RUN_TEST(test_wake_press_is_consumed_until_release);
    RUN_TEST(test_release_only_wake_event_is_consumed);
    RUN_TEST(test_long_wake_hold_does_not_become_hit_or_shutdown);
    RUN_TEST(test_release_without_gesture_does_not_latch_the_gate);
    RUN_TEST(test_wake_touch_does_not_navigate);
    RUN_TEST(test_idle_timer_and_gate_keep_page_until_second_press);
    RUN_TEST(test_low_brightness_is_darker_without_changing_endpoints);
    RUN_TEST(test_positive_minimum_contrast_does_not_power_off);
    RUN_TEST(test_contrast_curve_is_monotonic);
    RUN_TEST(test_proto_omitted_zero_brightness_is_not_factory_100);
    RUN_TEST(test_proto_empty_power_snapshot_has_zero_brightness);
    RUN_TEST(test_proto_regular_brightness_is_used);
    RUN_TEST(test_proto_excess_brightness_is_clamped);
    RUN_TEST(test_proto_malformed_power_uses_fallback);
    RUN_TEST(test_proto_null_nonempty_buffer_is_rejected);
    return UNITY_END();
}
