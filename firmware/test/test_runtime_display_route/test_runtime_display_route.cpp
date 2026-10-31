// AirTAK — Airsoft Team Awareness Kit
// test/test_runtime_display_route/test_runtime_display_route.cpp — portable
// runtime display routing predicate tests.
//
// Verifies the pure C++17 route predicates used to pick OLED vs TFT at runtime:
//   - the three known OLED boards (v3/v4/t3s3) route to OLED;
//   - T-Deck and unknown route false for OLED;
//   - incomplete OLED data and wrong OLED size are rejected;
//   - T-Deck matches its compiled TFT wiring, mismatches on any differing pin,
//     and rejects negative/absent geometry and non-TFT boards;
//   - optional TFT reset (-1) is honoured exactly.

#include <unity.h>
#include "platform/RuntimeDisplayRoute.h"
#include "platform/BoardDescriptor.h"

using namespace airtak;

void setUp() {}
void tearDown() {}

static RuntimeTftWiring tdeckWiring() {
    RuntimeTftWiring w;
    w.sck = 40; w.miso = 38; w.mosi = 41;
    w.cs = 12; w.dc = 11; w.rst = -1; w.bl = 42;
    w.width = 320; w.height = 240;
    return w;
}

// ── OLED ────────────────────────────────────────────────────────────────────
static void test_oled_known_boards() {
    TEST_ASSERT_TRUE(runtimeOledDisplayCompatible(board("heltec_v3")));
    TEST_ASSERT_TRUE(runtimeOledDisplayCompatible(board("heltec_v4")));
    TEST_ASSERT_TRUE(runtimeOledDisplayCompatible(board("t3s3")));
}

static void test_oled_tdeck_and_unknown_false() {
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(board("t_deck")));
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(unknownBoard()));
}

static void test_oled_missing_i2c_or_address_false() {
    BoardDescriptor bd = board("heltec_v3");
    bd.oledSda = -1;
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(bd));

    bd = board("heltec_v3");
    bd.oledI2cAddr = -1;
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(bd));
}

static void test_oled_wrong_size_false() {
    BoardDescriptor bd = board("heltec_v3");
    bd.oledWidth = 96;
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(bd));
    bd = board("heltec_v3");
    bd.oledHeight = 48;
    TEST_ASSERT_FALSE(runtimeOledDisplayCompatible(bd));
}

static void test_oled_optional_reset_ok() {
    BoardDescriptor bd = board("heltec_v3");
    bd.oledRst = -1;   // reset may be absent
    TEST_ASSERT_TRUE(runtimeOledDisplayCompatible(bd));
}

// ── TFT ────────────────────────────────────────────────────────────────────
static void test_tft_tdeck_matches_compiled_wiring() {
    TEST_ASSERT_TRUE(runtimeTftDisplayCompatible(board("t_deck"), tdeckWiring()));
}

static void test_tft_mismatched_any_pin_false() {
    RuntimeTftWiring w = tdeckWiring();
    w.cs = 13;
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(board("t_deck"), w));

    w = tdeckWiring();
    w.bl = 41;
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(board("t_deck"), w));
}

static void test_tft_optional_reset_must_match() {
    RuntimeTftWiring w = tdeckWiring();  // rst = -1
    TEST_ASSERT_TRUE(runtimeTftDisplayCompatible(board("t_deck"), w));
    w.rst = 21;                          // compiled reset present -> mismatch
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(board("t_deck"), w));
}

static void test_tft_negative_or_missing_geometry_false() {
    RuntimeTftWiring w = tdeckWiring();
    w.width = -1;                        // compiled wiring width invalid
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(board("t_deck"), w));

    w = tdeckWiring();
    BoardDescriptor bd = board("t_deck");
    bd.tftHeight = -1;                   // descriptor geometry invalid
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(bd, w));
}

static void test_tft_non_tft_and_unknown_false() {
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(board("heltec_v3"), tdeckWiring()));
    TEST_ASSERT_FALSE(runtimeTftDisplayCompatible(unknownBoard(), tdeckWiring()));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_oled_known_boards);
    RUN_TEST(test_oled_tdeck_and_unknown_false);
    RUN_TEST(test_oled_missing_i2c_or_address_false);
    RUN_TEST(test_oled_wrong_size_false);
    RUN_TEST(test_oled_optional_reset_ok);
    RUN_TEST(test_tft_tdeck_matches_compiled_wiring);
    RUN_TEST(test_tft_mismatched_any_pin_false);
    RUN_TEST(test_tft_optional_reset_must_match);
    RUN_TEST(test_tft_negative_or_missing_geometry_false);
    RUN_TEST(test_tft_non_tft_and_unknown_false);
    return UNITY_END();
}
