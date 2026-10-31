// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/BoardDescriptor.h — portable board descriptor registry
//
// A pure C++17, Arduino-free model of each supported ESP32-S3 board's fixed
// hardware characteristics. It is the runtime counterpart of the per-board
// compile-time BoardConfig.h files: instead of selecting behaviour with
// `#if defined(AIRTAK_BOARD_*)` macros, a single build selects a descriptor by
// board id at runtime.
//
// NOTHING here pulls in Arduino, FreeRTOS, or the board headers. The constants
// are hard-coded copies (with source-provenance comments in BoardDescriptor.cpp)
// so no board macro can leak into the registry or into sibling descriptor files.
//
// C++17 only: no designated initializers. Every descriptor is built by a
// constexpr builder function that starts from a defaulted [BoardDescriptor] and
// assigns the fields that differ. All pins default to -1 (absent) and all
// capability flags default to false, so the safe-unknown descriptor is simply
// the untouched default.
//
// Scope: ESP32-S3 only. No runtime nRF52 claim yet (see lookup()).
//
// Lookup policy: EXACT board-id string match only. There is no probing and no
// "guess the closest board" — an unknown id returns the safe [unknownBoard()]
// descriptor, so callers can boot safely rather than drive pins that may not exist.

#pragma once

#include <stdint.h>

namespace airtak {

// MCU family. Only Esp32S3 is defined today so the model can be extended to
// nRF52 without a breaking schema change; no nRF52 descriptors are declared
// (no runtime nRF52 claim).
enum class BoardPlatform : uint8_t {
    Esp32S3 = 0,
};

// What kind of display the board has. None is the default (safe-unknown and
// the native-unit-test/synthetic board).
enum class DisplayKind : uint8_t {
    None = 0,
    Oled = 1,   // SSD1306 128x64 over I2C
    Tft  = 2,   // ST7789 (T-Deck / T114) over SPI
};

// ADC input attenuation. V3/V4 use ADC_2_5db (ESP32-S3 ADC ~1.1 V ref, ~2.5 dB
// attenuation); the other boards do not override it. None means "no board
// override — use the common/default attenuation" (the registry is not an
// Arduino enum so it stays portable).
enum class AdcAttenuationDb : uint8_t {
    None = 0,   // unset/default
    Db0  = 1,
    Db2_5 = 2,
    Db6  = 3,
    Db11 = 4,
};

// ⚠️ WARNING — AdcAttenuationDb is NOT cast-compatible with the Arduino-ESP32 SDK.
// Its integer values deliberately differ from the SDK's adc_attenuation_t
// (0db=0, 2_5db=1, 6db=2, 11db=3). Specifically: Db2_5 == 2 (SDK ADC_2_5db == 1)
// and Db11 == 4 (SDK ADC_11db == 3). NEVER static_cast<adc_attenuation_t>(value):
// it silently selects the wrong attenuation. Map explicitly (if/switch) in the
// runtime HAL instead.

// Fixed hardware characteristics of one board.
//
// Pin fields use -1 as a sentinel: "this pin/feature does not exist." Booleans
// default false. A consumer must treat any pin < 0 as absent and not drive it.
// The default member initializers below make the untouched value the safe
// (unknown) descriptor.
struct BoardDescriptor {
    const char*  boardId   = "";
    const char*  boardName = "";
    BoardPlatform platform = BoardPlatform::Esp32S3;
    DisplayKind  display   = DisplayKind::None;
    int          hwModel   = -1;

    // ── Peripheral power rail (Vext / rail) ──────────────────────────────
    int  powerEnablePin       = -1;
    bool powerEnableActiveLow = false;   // true: drive LOW to enable (V3/V4); false: HIGH (T-Deck)

    // ── Battery ADC / divider ────────────────────────────────────────────
    int   adcCtrlPin          = -1;
    bool  adcCtrlActiveHigh   = false;  // true: drive HIGH to enable divider (V4); false: LOW (V3)
    int   vbatPin             = -1;
    float adcMultiplier       = 0.0f;   // calibration; 0 = uncalibrated -> use default
    AdcAttenuationDb adcAttenuation = AdcAttenuationDb::None;

    // ── User I/O ─────────────────────────────────────────────────────────
    int buttonPin = -1;
    int ledPin    = -1;

    // ── SX1262 LoRa radio (SPI) ──────────────────────────────────────────
    int   radioSck = -1;
    int   radioMiso = -1;
    int   radioMosi = -1;
    int   radioNss = -1;
    int   radioRst = -1;
    int   radioDio1 = -1;
    int   radioBusy = -1;
    float radioTcxoVoltage = 0.0f;         // V; 0 = no TCXO / not applicable
    bool  multiRadio = false;              // true: retain the existing SX1276 fallback (t3s3)

    // ── SX1276 sub-GHz LoRa (multi-radio boards, e.g. T3 S3) ───────────────
    // Meaningful only when multiRadio is true. Shares the SX1262 SPI pins
    // (radioSck/miso/mosi/nss/rst); these are the SX1276-only DIO and RF-switch
    // (RXEN/TXEN) pins. -1 = this variant is not installed / not wired.
    // SX1280 and LR1121 are intentionally NOT modelled (no driver in the HAL).
    int sx1276Dio0 = -1;
    int sx1276Dio1 = -1;
    int sx1276Dio2 = -1;
    int sx1276Rxen = -1;
    int sx1276Txen = -1;

    // ── SSD1306 OLED (only meaningful when display == Oled) ──────────────
    int oledSda = -1;
    int oledScl = -1;
    int oledRst = -1;                       // -1 if not wired
    int oledWidth = -1;
    int oledHeight = -1;
    int oledI2cAddr = -1;

    // ── ST7789 TFT (only meaningful when display == Tft) ────────────────
    int tftSck = -1;
    int tftMosi = -1;
    int tftMiso = -1;
    int tftCs = -1;
    int tftDc = -1;
    int tftRst = -1;
    int tftBl = -1;
    int tftWidth = -1;
    int tftHeight = -1;

    // ── Shared I2C bus + touchscreen input (only when wired) ─────────────
    // T-Deck: external I2C (keyboard/trackpad, SDA 18 / SCL 8) and touch
    // interrupt (GPIO 16). -1 = not present on this board.
    int i2cSda   = -1;
    int i2cScl   = -1;
    int touchInt = -1;

    // ── GPS (only when hasGps) ───────────────────────────────────────────
    bool hasGps = false;
    int  gpsTx  = -1;
    int  gpsRx  = -1;

    // ── KCT8103L RF FEM (only when hasFem) ──────────────────────────────
    bool hasFem = false;
    int  femVfem = -1;
    int  femCsd  = -1;
    int  femCtx  = -1;
};

// The safe-unknown descriptor (the untouched default). boardId "", display
// None, hwModel -1, every pin -1, every capability false.
const BoardDescriptor& unknownBoard();

// Exact board-id lookup. Returns unknownBoard() when [id] is not an exact,
// case-sensitive match of a known board id. No probing, no fuzzy fallback.
const BoardDescriptor& board(const char* id);

// Number of known (non-unknown) ESP32-S3 descriptors.
int boardCount();

// The i-th known descriptor (0 <= i < boardCount()).
const BoardDescriptor& boardAt(int index);

} // namespace airtak
