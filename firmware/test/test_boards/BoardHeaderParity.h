// Include after exactly one legacy BoardConfig.h, in an isolated translation unit.
#pragma once
#include <unity.h>
#include "platform/BoardDescriptor.h"

static void assertLegacyBoardHeaderParity() {
    const auto& d = airtak::board(AIRTAK_BOARD_ID);
    TEST_ASSERT_EQUAL_STRING(AIRTAK_BOARD_ID, d.boardId);
    TEST_ASSERT_EQUAL_STRING(AIRTAK_BOARD_NAME, d.boardName);
#define CHECK_PIN(member, value) TEST_ASSERT_EQUAL_INT(value, d.member)
    CHECK_PIN(powerEnablePin, AIRTAK_PIN_POWER_EN);
    CHECK_PIN(vbatPin, AIRTAK_PIN_VBAT_SENSE);
    CHECK_PIN(buttonPin, AIRTAK_PIN_USER_BUTTON);
    CHECK_PIN(radioSck, AIRTAK_PIN_SX1262_SCK);
    CHECK_PIN(radioMiso, AIRTAK_PIN_SX1262_MISO);
    CHECK_PIN(radioMosi, AIRTAK_PIN_SX1262_MOSI);
    CHECK_PIN(radioNss, AIRTAK_PIN_SX1262_NSS);
    CHECK_PIN(radioRst, AIRTAK_PIN_SX1262_RESET);
    CHECK_PIN(radioDio1, AIRTAK_PIN_SX1262_DIO1);
    CHECK_PIN(radioBusy, AIRTAK_PIN_SX1262_BUSY);
    TEST_ASSERT_FLOAT_WITHIN(0.0001f, AIRTAK_SX1262_TCXO_VOLTAGE, d.radioTcxoVoltage);
#ifdef AIRTAK_POWER_EN_ACTIVE_LOW
    TEST_ASSERT_TRUE(d.powerEnableActiveLow);
#else
    TEST_ASSERT_FALSE(d.powerEnableActiveLow);
#endif
#ifdef AIRTAK_PIN_ADC_CTRL
    CHECK_PIN(adcCtrlPin, AIRTAK_PIN_ADC_CTRL);
#else
    CHECK_PIN(adcCtrlPin, -1);
#endif
#ifdef AIRTAK_ADC_CTRL_ACTIVE_HIGH
    TEST_ASSERT_TRUE(d.adcCtrlActiveHigh);
#else
    TEST_ASSERT_FALSE(d.adcCtrlActiveHigh);
#endif
#ifdef AIRTAK_ADC_MULTIPLIER
    TEST_ASSERT_FLOAT_WITHIN(0.0001f, AIRTAK_ADC_MULTIPLIER, d.adcMultiplier);
#else
    TEST_ASSERT_EQUAL_FLOAT(1.0f, d.adcMultiplier);
#endif
#ifdef AIRTAK_ADC_ATTENUATION
    // Portable stand-ins for Arduino's enum; compare the actual header token.
    enum { ADC_0db, ADC_2_5db, ADC_6db, ADC_11db };
    TEST_ASSERT_EQUAL_INT(ADC_2_5db, AIRTAK_ADC_ATTENUATION);
    TEST_ASSERT_EQUAL(airtak::AdcAttenuationDb::Db2_5, d.adcAttenuation);
#else
    TEST_ASSERT_EQUAL(airtak::AdcAttenuationDb::None, d.adcAttenuation);
#endif
#ifdef AIRTAK_PIN_LED_POWER
    CHECK_PIN(ledPin, AIRTAK_PIN_LED_POWER);
#else
    CHECK_PIN(ledPin, -1);
#endif
#if defined(AIRTAK_BOARD_HAS_OLED_DISPLAY) && AIRTAK_BOARD_HAS_OLED_DISPLAY
    TEST_ASSERT_EQUAL(airtak::DisplayKind::Oled, d.display);
    CHECK_PIN(oledSda, AIRTAK_PIN_OLED_SDA);
    CHECK_PIN(oledScl, AIRTAK_PIN_OLED_SCL);
    CHECK_PIN(oledWidth, AIRTAK_OLED_WIDTH);
    CHECK_PIN(oledHeight, AIRTAK_OLED_HEIGHT);
    CHECK_PIN(oledI2cAddr, AIRTAK_OLED_I2C_ADDRESS);
#ifdef AIRTAK_PIN_OLED_RST
    CHECK_PIN(oledRst, AIRTAK_PIN_OLED_RST);
#else
    CHECK_PIN(oledRst, -1);
#endif
#else
    CHECK_PIN(oledSda, -1);
    CHECK_PIN(oledScl, -1);
    CHECK_PIN(oledRst, -1);
#endif
#if defined(AIRTAK_BOARD_HAS_TFT_DISPLAY) && AIRTAK_BOARD_HAS_TFT_DISPLAY
    TEST_ASSERT_EQUAL(airtak::DisplayKind::Tft, d.display);
    CHECK_PIN(tftSck, AIRTAK_PIN_TFT_SCK);
    CHECK_PIN(tftMosi, AIRTAK_PIN_TFT_MOSI);
    CHECK_PIN(tftMiso, AIRTAK_PIN_TFT_MISO);
    CHECK_PIN(tftCs, AIRTAK_PIN_TFT_CS);
    CHECK_PIN(tftDc, AIRTAK_PIN_TFT_DC);
    CHECK_PIN(tftRst, AIRTAK_PIN_TFT_RST);
    CHECK_PIN(tftBl, AIRTAK_PIN_TFT_BL);
    CHECK_PIN(tftWidth, AIRTAK_TFT_WIDTH);
    CHECK_PIN(tftHeight, AIRTAK_TFT_HEIGHT);
#else
    CHECK_PIN(tftSck, -1);
    CHECK_PIN(tftMosi, -1);
    CHECK_PIN(tftMiso, -1);
    CHECK_PIN(tftCs, -1);
    CHECK_PIN(tftDc, -1);
    CHECK_PIN(tftRst, -1);
    CHECK_PIN(tftBl, -1);
#endif
    TEST_ASSERT_EQUAL_INT(AIRTAK_BOARD_HAS_GPS, d.hasGps);
#if AIRTAK_BOARD_HAS_GPS
    CHECK_PIN(gpsTx, AIRTAK_PIN_GPS_TX_MCU);
    CHECK_PIN(gpsRx, AIRTAK_PIN_GPS_RX_MCU);
#else
    CHECK_PIN(gpsTx, -1);
    CHECK_PIN(gpsRx, -1);
#endif
#ifdef AIRTAK_PIN_FEM_VFEM
    TEST_ASSERT_TRUE(d.hasFem);
    CHECK_PIN(femVfem, AIRTAK_PIN_FEM_VFEM);
    CHECK_PIN(femCsd, AIRTAK_PIN_FEM_CSD);
    CHECK_PIN(femCtx, AIRTAK_PIN_FEM_CTX);
#else
    TEST_ASSERT_FALSE(d.hasFem);
    CHECK_PIN(femVfem, -1);
    CHECK_PIN(femCsd, -1);
    CHECK_PIN(femCtx, -1);
#endif
#ifdef AIRTAK_PIN_SX1276_DIO0
    TEST_ASSERT_TRUE(d.multiRadio);
#else
    TEST_ASSERT_FALSE(d.multiRadio);
#endif
#undef CHECK_PIN
}
