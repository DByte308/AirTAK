// AirTAK — Airsoft Team Awareness Kit
// firmware/test/test_boards/test_boards.cpp — portable board-registry tests
//
// Verifies the pure C++17 BoardDescriptor registry:
//   - exact-id lookup: known ids resolve, unknown ids resolve to the safe
//     unknownBoard() descriptor (no probing, no fuzzy fallback);
//   - sentinel pins: -1 where a board lacks a pin/feature;
//   - polarities: V3/V4 power-enable LOW, T-Deck HIGH; V3 ADC_CTRL LOW vs V4 HIGH;
//   - known-id values cross-checked against the per-board BoardConfig.h.

#include <unity.h>
#include "platform/BoardDescriptor.h"

using namespace airtak;

void setUp() {}
void tearDown() {}

// ── Safe-unknown descriptor ───────────────────────────────────────────────
static void test_unknown_lookup_returns_safe_descriptor() {
    const BoardDescriptor& u = board("definitely-not-a-real-board");
    TEST_ASSERT_EQUAL_STRING("", u.boardId);
    TEST_ASSERT_EQUAL(DisplayKind::None, u.display);
    TEST_ASSERT_EQUAL(-1, u.hwModel);

    TEST_ASSERT_EQUAL(-1, u.powerEnablePin);
    TEST_ASSERT_FALSE(u.powerEnableActiveLow);
    TEST_ASSERT_EQUAL(-1, u.adcCtrlPin);
    TEST_ASSERT_FALSE(u.adcCtrlActiveHigh);
    TEST_ASSERT_EQUAL(-1, u.vbatPin);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, u.adcMultiplier);
    TEST_ASSERT_EQUAL(-1, u.buttonPin);
    TEST_ASSERT_EQUAL(-1, u.ledPin);

    TEST_ASSERT_EQUAL(-1, u.radioSck);
    TEST_ASSERT_EQUAL(-1, u.radioMiso);
    TEST_ASSERT_EQUAL(-1, u.radioMosi);
    TEST_ASSERT_EQUAL(-1, u.radioNss);
    TEST_ASSERT_EQUAL(-1, u.radioRst);
    TEST_ASSERT_EQUAL(-1, u.radioDio1);
    TEST_ASSERT_EQUAL(-1, u.radioBusy);
    TEST_ASSERT_EQUAL_FLOAT(0.0f, u.radioTcxoVoltage);

    TEST_ASSERT_EQUAL(-1, u.oledSda);
    TEST_ASSERT_EQUAL(-1, u.oledScl);
    TEST_ASSERT_EQUAL(-1, u.oledRst);
    TEST_ASSERT_EQUAL(-1, u.oledWidth);
    TEST_ASSERT_EQUAL(-1, u.oledHeight);
    TEST_ASSERT_EQUAL(-1, u.oledI2cAddr);

    TEST_ASSERT_EQUAL(-1, u.tftSck);
    TEST_ASSERT_EQUAL(-1, u.tftMosi);
    TEST_ASSERT_EQUAL(-1, u.tftMiso);
    TEST_ASSERT_EQUAL(-1, u.tftCs);
    TEST_ASSERT_EQUAL(-1, u.tftDc);
    TEST_ASSERT_EQUAL(-1, u.tftRst);
    TEST_ASSERT_EQUAL(-1, u.tftBl);
    TEST_ASSERT_EQUAL(-1, u.tftWidth);
    TEST_ASSERT_EQUAL(-1, u.tftHeight);
    TEST_ASSERT_FALSE(u.multiRadio);
    TEST_ASSERT_EQUAL(AdcAttenuationDb::None, u.adcAttenuation);

    TEST_ASSERT_FALSE(u.hasGps);
    TEST_ASSERT_EQUAL(-1, u.gpsTx);
    TEST_ASSERT_EQUAL(-1, u.gpsRx);
    TEST_ASSERT_FALSE(u.hasFem);
    TEST_ASSERT_EQUAL(-1, u.femVfem);
    TEST_ASSERT_EQUAL(-1, u.femCsd);
    TEST_ASSERT_EQUAL(-1, u.femCtx);
}

static void test_null_and_empty_id_resolve_to_unknown() {
    TEST_ASSERT_EQUAL(unknownBoard().boardId, board(nullptr).boardId);
    TEST_ASSERT_EQUAL(unknownBoard().boardId, board("").boardId);
    TEST_ASSERT_TRUE(&board("") == &unknownBoard());
}

// ── heltec_v3 ──────────────────────────────────────────────────────────────
static void test_heltec_v3_known_id() {
    const BoardDescriptor& b = board("heltec_v3");
    TEST_ASSERT_EQUAL_STRING("heltec_v3", b.boardId);
    TEST_ASSERT_EQUAL_STRING("Heltec WiFi LoRa 32 V3", b.boardName);
    TEST_ASSERT_EQUAL(BoardPlatform::Esp32S3, b.platform);
    TEST_ASSERT_EQUAL(DisplayKind::Oled, b.display);
    TEST_ASSERT_EQUAL(43, b.hwModel);

    // power rail: Vext GPIO 36 active LOW
    TEST_ASSERT_EQUAL(36, b.powerEnablePin);
    TEST_ASSERT_TRUE(b.powerEnableActiveLow);

    // battery: ADC_CTRL 37 active LOW, VBAT 1, multiplier 2.6, attenuation ADC_2_5db
    TEST_ASSERT_EQUAL(37, b.adcCtrlPin);
    TEST_ASSERT_FALSE(b.adcCtrlActiveHigh);
    TEST_ASSERT_EQUAL(1, b.vbatPin);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 2.6f, b.adcMultiplier);
    TEST_ASSERT_EQUAL(AdcAttenuationDb::Db2_5, b.adcAttenuation);
    TEST_ASSERT_FALSE(b.multiRadio);

    TEST_ASSERT_EQUAL(0, b.buttonPin);
    TEST_ASSERT_EQUAL(35, b.ledPin);

    // SX1262
    TEST_ASSERT_EQUAL(9, b.radioSck);
    TEST_ASSERT_EQUAL(11, b.radioMiso);
    TEST_ASSERT_EQUAL(10, b.radioMosi);
    TEST_ASSERT_EQUAL(8, b.radioNss);
    TEST_ASSERT_EQUAL(12, b.radioRst);
    TEST_ASSERT_EQUAL(14, b.radioDio1);
    TEST_ASSERT_EQUAL(13, b.radioBusy);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.8f, b.radioTcxoVoltage);

    // SSD1306
    TEST_ASSERT_EQUAL(17, b.oledSda);
    TEST_ASSERT_EQUAL(18, b.oledScl);
    TEST_ASSERT_EQUAL(21, b.oledRst);
    TEST_ASSERT_EQUAL(128, b.oledWidth);
    TEST_ASSERT_EQUAL(64, b.oledHeight);
    TEST_ASSERT_EQUAL(0x3C, b.oledI2cAddr);

    // no TFT / GPS / FEM
    TEST_ASSERT_EQUAL(-1, b.tftCs);
    TEST_ASSERT_FALSE(b.hasGps);
    TEST_ASSERT_FALSE(b.hasFem);
}

// ── heltec_v4 ──────────────────────────────────────────────────────────────
static void test_heltec_v4_polarity_and_fem() {
    const BoardDescriptor& b = board("heltec_v4");
    TEST_ASSERT_EQUAL_STRING("heltec_v4", b.boardId);
    TEST_ASSERT_EQUAL(43, b.hwModel);
    TEST_ASSERT_EQUAL(DisplayKind::Oled, b.display);

    // V4 divider enables with ADC_CTRL HIGH (opposite of V3)
    TEST_ASSERT_TRUE(b.adcCtrlActiveHigh);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 2.56f, b.adcMultiplier);
    TEST_ASSERT_EQUAL(AdcAttenuationDb::Db2_5, b.adcAttenuation);

    // KCT8103L RF FEM
    TEST_ASSERT_TRUE(b.hasFem);
    TEST_ASSERT_EQUAL(7, b.femVfem);
    TEST_ASSERT_EQUAL(2, b.femCsd);
    TEST_ASSERT_EQUAL(5, b.femCtx);

    // same pins as V3
    TEST_ASSERT_EQUAL(36, b.powerEnablePin);
    TEST_ASSERT_TRUE(b.powerEnableActiveLow);
    TEST_ASSERT_EQUAL(17, b.oledSda);
    TEST_ASSERT_EQUAL(9, b.radioSck);
}

// ── t3s3 ───────────────────────────────────────────────────────────────────
static void test_t3s3_sentinels_and_pins() {
    const BoardDescriptor& b = board("t3s3");
    TEST_ASSERT_EQUAL_STRING("t3s3", b.boardId);
    TEST_ASSERT_EQUAL(16, b.hwModel);
    TEST_ASSERT_EQUAL(DisplayKind::Oled, b.display);

    // No power rail, no ADC_CTRL (sentinels)
    TEST_ASSERT_EQUAL(-1, b.powerEnablePin);
    TEST_ASSERT_EQUAL(-1, b.adcCtrlPin);
    TEST_ASSERT_FALSE(b.powerEnableActiveLow);

    // Default multiplier (no calibration defined) + default attenuation
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.0f, b.adcMultiplier);
    TEST_ASSERT_EQUAL(AdcAttenuationDb::None, b.adcAttenuation);
    // multi-radio: SX1276/SX1280/LR1121 must still be probed by the runtime wiring
    TEST_ASSERT_TRUE(b.multiRadio);

    // SX1262-compatible SPI (from LORA_* pins) + different I2C pin order
    TEST_ASSERT_EQUAL(5, b.radioSck);
    TEST_ASSERT_EQUAL(3, b.radioMiso);
    TEST_ASSERT_EQUAL(6, b.radioMosi);
    TEST_ASSERT_EQUAL(7, b.radioNss);
    TEST_ASSERT_EQUAL(8, b.radioRst);
    TEST_ASSERT_EQUAL(33, b.radioDio1);
    TEST_ASSERT_EQUAL(34, b.radioBusy);
    TEST_ASSERT_EQUAL(18, b.oledSda);
    TEST_ASSERT_EQUAL(17, b.oledScl);

    // No OLED reset pin on t3s3
    TEST_ASSERT_EQUAL(-1, b.oledRst);

    TEST_ASSERT_EQUAL(37, b.ledPin);
    TEST_ASSERT_FALSE(b.hasGps);
    TEST_ASSERT_FALSE(b.hasFem);
}

// ── t_deck ─────────────────────────────────────────────────────────────────
static void test_t_deck_tft_gps_polarity() {
    const BoardDescriptor& b = board("t_deck");
    TEST_ASSERT_EQUAL_STRING("t_deck", b.boardId);
    TEST_ASSERT_EQUAL(50, b.hwModel);
    TEST_ASSERT_EQUAL(DisplayKind::Tft, b.display);

    // Peripheral rail active HIGH (drive HIGH to enable)
    TEST_ASSERT_EQUAL(10, b.powerEnablePin);
    TEST_ASSERT_FALSE(b.powerEnableActiveLow);

    // No status LED, no ADC_CTRL, default attenuation
    TEST_ASSERT_EQUAL(-1, b.ledPin);
    TEST_ASSERT_EQUAL(-1, b.adcCtrlPin);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 1.10f, b.adcMultiplier);
    TEST_ASSERT_EQUAL(AdcAttenuationDb::None, b.adcAttenuation);
    TEST_ASSERT_FALSE(b.multiRadio);

    // ST7789 TFT (shared SPI with radio) + no OLED
    TEST_ASSERT_EQUAL(40, b.tftSck);
    TEST_ASSERT_EQUAL(41, b.tftMosi);
    TEST_ASSERT_EQUAL(38, b.tftMiso);
    TEST_ASSERT_EQUAL(12, b.tftCs);
    TEST_ASSERT_EQUAL(11, b.tftDc);
    TEST_ASSERT_EQUAL(-1, b.tftRst);
    TEST_ASSERT_EQUAL(42, b.tftBl);
    TEST_ASSERT_EQUAL(320, b.tftWidth);
    TEST_ASSERT_EQUAL(240, b.tftHeight);
    TEST_ASSERT_EQUAL(-1, b.oledScl);

    // SX1262 on shared SPI
    TEST_ASSERT_EQUAL(40, b.radioSck);
    TEST_ASSERT_EQUAL(9, b.radioNss);
    TEST_ASSERT_EQUAL(45, b.radioDio1);

    // Optional GPS (T-Deck-Plus)
    TEST_ASSERT_TRUE(b.hasGps);
    TEST_ASSERT_EQUAL(43, b.gpsTx);
    TEST_ASSERT_EQUAL(44, b.gpsRx);
}

// ── registry enumeration ───────────────────────────────────────────────────
static void test_board_count_and_lookup_roundtrip() {
    TEST_ASSERT_EQUAL(4, boardCount());
    TEST_ASSERT_EQUAL_STRING("heltec_v3", boardAt(0).boardId);
    TEST_ASSERT_EQUAL_STRING("heltec_v4", boardAt(1).boardId);
    TEST_ASSERT_EQUAL_STRING("t3s3", boardAt(2).boardId);
    TEST_ASSERT_EQUAL_STRING("t_deck", boardAt(3).boardId);
    TEST_ASSERT_TRUE(&boardAt(-1) == &unknownBoard());
    TEST_ASSERT_TRUE(&boardAt(99) == &unknownBoard());
}

// ── case sensitivity (exact-id only) ───────────────────────────────────────
static void test_lookup_is_exact_case_sensitive() {
    TEST_ASSERT_TRUE(&board("HELTEC_V3") == &unknownBoard());
    TEST_ASSERT_TRUE(&board("heltec_v3 ") == &unknownBoard());
    TEST_ASSERT_TRUE(&board("t_deckX") == &unknownBoard());
}

void test_heltec_v3_header_parity();
void test_heltec_v4_header_parity();
void test_t3s3_header_parity();
void test_t_deck_header_parity();

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    UNITY_BEGIN();
    RUN_TEST(test_unknown_lookup_returns_safe_descriptor);
    RUN_TEST(test_null_and_empty_id_resolve_to_unknown);
    RUN_TEST(test_heltec_v3_known_id);
    RUN_TEST(test_heltec_v4_polarity_and_fem);
    RUN_TEST(test_t3s3_sentinels_and_pins);
    RUN_TEST(test_t_deck_tft_gps_polarity);
    RUN_TEST(test_board_count_and_lookup_roundtrip);
    RUN_TEST(test_lookup_is_exact_case_sensitive);
    RUN_TEST(test_heltec_v3_header_parity);
    RUN_TEST(test_heltec_v4_header_parity);
    RUN_TEST(test_t3s3_header_parity);
    RUN_TEST(test_t_deck_header_parity);
    return UNITY_END();
}
