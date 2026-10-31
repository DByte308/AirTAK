// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/BoardDescriptor.cpp — board descriptor registry (ESP32-S3)
//
// Values are hard-coded copies of the compile-time constants in the per-board
// BoardConfig.h (pattern: src/platform/boards/<board>/BoardConfig.h). Every
// value below carries a provenance comment naming the source board header and
// the AIRTAK_* macro it came from, so a reviewer can cross-check against the
// #define it mirrors. Nothing #includes the board headers — the registry must
// not leak a board macro into a sibling descriptor.
//
// C++17: each descriptor is a constexpr builder function that starts from a
// defaulted [BoardDescriptor] (pins -1, capabilities false) and assigns the
// fields that differ. No designated initializers (C++20) used anywhere.
//
// Semantics:
//   - a pin of -1 means "not present on this board" (absent pin / unused feature);
//   - capability flags default false;
//   - the board identifiers here must match the AIRTAK_BOARD_ID strings the
//     firmware advertises in DeviceStatus.board_id ("heltec_v3", etc.).

#include "BoardDescriptor.h"

namespace airtak {

// ── The safe-unknown descriptor ───────────────────────────────────────────────
// Returned for any id that is not an exact match. By construction it is the
// untouched default of [BoardDescriptor] (all pins -1, capabilities false,
// display None, hwModel -1), so the default member initializers ARE the safe
// unknown board.
const BoardDescriptor& unknownBoard() {
    static const BoardDescriptor kUnknown;   // defaulted -> safe unknown
    return kUnknown;
}

// ── heltec_v3 ────────────────────────────────────────────────────────────────
// Source: src/platform/boards/heltec_v3/BoardConfig.h
constexpr BoardDescriptor makeHeltecV3() {
    BoardDescriptor d{};  // defaulted (safe pins -1 / caps false)
    d.boardId   = "heltec_v3";                 // AIRTAK_BOARD_ID
    d.boardName = "Heltec WiFi LoRa 32 V3";    // AIRTAK_BOARD_NAME
    d.display   = DisplayKind::Oled;           // AIRTAK_BOARD_HAS_OLED_DISPLAY
    d.hwModel   = 43;                          // Platform.cpp meshtasticHwModel() HELTEC_V3

    // AIRTAK_PIN_POWER_EN 36, AIRTAK_POWER_EN_ACTIVE_LOW (Vext, active LOW)
    d.powerEnablePin       = 36;
    d.powerEnableActiveLow = true;

    // AIRTAK_PIN_ADC_CTRL 37 (active LOW), AIRTAK_PIN_VBAT_SENSE 1,
    // AIRTAK_ADC_MULTIPLIER 2.6f, AIRTAK_ADC_ATTENUATION ADC_2_5db
    d.adcCtrlPin        = 37;
    d.adcCtrlActiveHigh = false;
    d.vbatPin           = 1;
    d.adcMultiplier     = 2.6f;
    d.adcAttenuation    = AdcAttenuationDb::Db2_5;

    // AIRTAK_PIN_USER_BUTTON 0, AIRTAK_PIN_LED_POWER 35
    d.buttonPin = 0;
    d.ledPin    = 35;

    // AIRTAK_PIN_SX1262_SCK 9 / MISO 11 / MOSI 10 / NSS 8 / RESET 12 / DIO1 14 / BUSY 13,
    // AIRTAK_SX1262_TCXO_VOLTAGE 1.8f
    d.radioSck = 9; d.radioMiso = 11; d.radioMosi = 10;
    d.radioNss = 8; d.radioRst = 12; d.radioDio1 = 14; d.radioBusy = 13;
    d.radioTcxoVoltage = 1.8f;
    d.multiRadio = false;

    // AIRTAK_PIN_OLED_SDA 17 / SCL 18 / RST 21, OLED_WIDTH 128, OLED_HEIGHT 64, I2C 0x3C
    d.oledSda = 17; d.oledScl = 18; d.oledRst = 21;
    d.oledWidth = 128; d.oledHeight = 64; d.oledI2cAddr = 0x3C;

    // No TFT / GPS / FEM (defaults: pins -1, caps false)
    return d;
}

// ── heltec_v4 ────────────────────────────────────────────────────────────────
// Source: src/platform/boards/heltec_v4/BoardConfig.h
// A V3-clone pin map; differences from V3: ADC_CTRL is ACTIVE HIGH, the
// calibration multiplier is 2.56f, and it drives a KCT8103L RF FEM (VFEM 7, CSD 2, CTX 5).
constexpr BoardDescriptor makeHeltecV4() {
    BoardDescriptor d = makeHeltecV3();   // V4 shares the V3 pin map...
    d.boardId   = "heltec_v4";            // AIRTAK_BOARD_ID
    d.boardName = "Heltec WiFi LoRa 32 V4";  // AIRTAK_BOARD_NAME
    d.hwModel   = 43;                     // Platform.cpp meshtasticHwModel() HELTEC_V3 (V4 shares node type)

    // AIRTAK_PIN_ADC_CTRL 37, AIRTAK_ADC_CTRL_ACTIVE_HIGH (differs from V3)
    d.adcCtrlActiveHigh = true;
    // AIRTAK_ADC_MULTIPLIER 2.56f
    d.adcMultiplier = 2.56f;

    // AIRTAK_PIN_FEM_VFEM 7 / FEM_CSD 2 / FEM_CTX 5
    d.hasFem   = true;
    d.femVfem  = 7;
    d.femCsd   = 2;
    d.femCtx   = 5;
    return d;
}

// ── t3s3 ─────────────────────────────────────────────────────────────────────
// Source: src/platform/boards/t3s3/BoardConfig.h
// Multi-radio board (SX1262 / SX1276 / SX1280 / LR1121). The descriptor below
// records the SX1262 primary SPI pins (AIRTAK_PIN_LORA_* / AIRTAK_PIN_SX1262_*).
// [multiRadio] requires retaining Radio.cpp's existing SX1276 fallback after
// the SX1262 probe. SX1280/LR1121 pin definitions exist in the legacy header,
// but their drivers are NOT implemented by the current radio HAL. This registry
// does not add that support. t3s3 has no power rail, ADC_CTRL, or OLED reset pin.
constexpr BoardDescriptor makeT3S3() {
    BoardDescriptor d{};
    d.boardId   = "t3s3";                    // AIRTAK_BOARD_ID
    d.boardName = "LilyGo T-LoRa T3 S3";     // AIRTAK_BOARD_NAME
    d.display   = DisplayKind::Oled;         // AIRTAK_BOARD_HAS_OLED_DISPLAY
    d.hwModel   = 16;                        // Platform.cpp meshtasticHwModel() TLORA_T3_S3

    // AIRTAK_PIN_POWER_EN -1 (no power rail) -> default pin -1, activeLow false
    d.powerEnablePin       = -1;
    d.powerEnableActiveLow = false;

    // No ADC_CTRL (divider always on); AIRTAK_PIN_VBAT_SENSE 1; no AIRTAK_ADC_MULTIPLIER
    // defined -> effective default 1.0f (BatteryConstants.h AIRTAK_ADC_MULTIPLIER_DEFAULT);
    // no AIRTAK_ADC_ATTENUATION -> default
    d.adcCtrlPin        = -1;
    d.adcCtrlActiveHigh = false;
    d.vbatPin           = 1;
    d.adcMultiplier     = 1.0f;
    d.adcAttenuation    = AdcAttenuationDb::None;

    // AIRTAK_PIN_USER_BUTTON 0, AIRTAK_PIN_LED_POWER 37
    d.buttonPin = 0;
    d.ledPin    = 37;

    // SX1262-compatible primary: AIRTAK_PIN_LORA_SCK 5 / MISO 3 / MOSI 6 / CS 7 / RESET 8,
    // AIRTAK_PIN_SX1262_DIO1 33 / BUSY 34, AIRTAK_SX1262_TCXO_VOLTAGE 1.8f
    d.radioSck = 5; d.radioMiso = 3; d.radioMosi = 6;
    d.radioNss = 7; d.radioRst = 8; d.radioDio1 = 33; d.radioBusy = 34;
    d.radioTcxoVoltage = 1.8f;
    // Preserve the existing SX1276 fallback in the runtime wiring.
    d.multiRadio = true;

    // SX1276 variant: AIRTAK_PIN_SX1276_DIO0 9 / DIO1 33 / DIO2 34 / RXEN 21 / TXEN 10.
    // Shares the SX1262 SPI pins above; SX1276 DIO1/DIO2 coincide with
    // radioDio1/radioBusy, so only the variant-unique pins are recorded here.
    // SX1280 / LR1121 are NOT modelled (no driver in the current radio HAL).
    d.sx1276Dio0 = 9;
    d.sx1276Dio1 = 33;
    d.sx1276Dio2 = 34;
    d.sx1276Rxen = 21;
    d.sx1276Txen = 10;

    // AIRTAK_PIN_OLED_SDA 18 / SCL 17 (SWAPPED vs V3/V4); no OLED_RST defined -> -1
    d.oledSda = 18; d.oledScl = 17; d.oledRst = -1;
    d.oledWidth = 128; d.oledHeight = 64; d.oledI2cAddr = 0x3C;

    // No TFT / GPS / FEM (defaults)
    return d;
}

// ── t_deck ───────────────────────────────────────────────────────────────────
// Source: src/platform/boards/t_deck/BoardConfig.h
// TFT (ST7789) + SX1262 on the SAME SPI bus (40/41/38). Peripheral rail is
// active-HIGH (GPIO 10). No status LED, no ADC_CTRL. Optional GPS (T-Deck-Plus).
constexpr BoardDescriptor makeTDeck() {
    BoardDescriptor d{};
    d.boardId   = "t_deck";                  // AIRTAK_BOARD_ID
    d.boardName = "LilyGo T-Deck";           // AIRTAK_BOARD_NAME
    d.display   = DisplayKind::Tft;          // AIRTAK_BOARD_HAS_TFT_DISPLAY
    d.hwModel   = 50;                        // Platform.cpp meshtasticHwModel() T_DECK

    // AIRTAK_PIN_POWER_EN 10 (drive HIGH to enable -> activeLow false)
    d.powerEnablePin       = 10;
    d.powerEnableActiveLow = false;

    // No ADC_CTRL (divider always on); AIRTAK_PIN_VBAT_SENSE 4, AIRTAK_ADC_MULTIPLIER 1.10f
    d.adcCtrlPin        = -1;
    d.adcCtrlActiveHigh = false;
    d.vbatPin           = 4;
    d.adcMultiplier     = 1.10f;
    d.adcAttenuation    = AdcAttenuationDb::None;

    // AIRTAK_PIN_USER_BUTTON 0; no status LED defined -> -1
    d.buttonPin = 0;
    d.ledPin    = -1;

    // AIRTAK_PIN_SX1262_SCK 40 / MISO 38 / MOSI 41 / NSS 9 / RESET 17 / DIO1 45 / BUSY 13,
    // SX1262_TCXO_VOLTAGE 1.8
    d.radioSck = 40; d.radioMiso = 38; d.radioMosi = 41;
    d.radioNss = 9; d.radioRst = 17; d.radioDio1 = 45; d.radioBusy = 13;
    d.radioTcxoVoltage = 1.8f;
    d.multiRadio = false;

    // AIRTAK_PIN_TFT_SCK 40 / MOSI 41 / MISO 38 / CS 12 / DC 11 / RST -1 / BL 42, 320x240
    d.tftSck = 40; d.tftMosi = 41; d.tftMiso = 38;
    d.tftCs = 12; d.tftDc = 11; d.tftRst = -1; d.tftBl = 42;
    d.tftWidth = 320; d.tftHeight = 240;

    // AIRTAK_PIN_GPS_TX_MCU 43 / RX_MCU 44 (T-Deck-Plus)
    d.hasGps = true;
    d.gpsTx  = 43;
    d.gpsRx  = 44;

    // AIRTAK_PIN_I2C_SDA 18 / SCL 8 (keyboard/trackpad I2C), AIRTAK_PIN_TOUCH_INT 16
    d.i2cSda   = 18;
    d.i2cScl   = 8;
    d.touchInt = 16;

    // No OLED / FEM (defaults)
    return d;
}

// Known (non-unknown) ESP32-S3 descriptors, in a stable order.
static const BoardDescriptor kHeltecV3 = makeHeltecV3();
static const BoardDescriptor kHeltecV4 = makeHeltecV4();
static const BoardDescriptor kT3S3     = makeT3S3();
static const BoardDescriptor kTDeck    = makeTDeck();

static const BoardDescriptor* const kKnown[] = {
    &kHeltecV3,
    &kHeltecV4,
    &kT3S3,
    &kTDeck,
};

const BoardDescriptor& board(const char* id) {
    if (id == nullptr) return unknownBoard();
    for (const BoardDescriptor* d : kKnown) {
        // exact, case-sensitive id match only
        const char* a = d->boardId;
        const char* b = id;
        while (*a && *a == *b) { ++a; ++b; }
        if (*a == '\0' && *b == '\0') return *d;
    }
    return unknownBoard();
}

int boardCount() {
    return static_cast<int>(sizeof(kKnown) / sizeof(kKnown[0]));
}

const BoardDescriptor& boardAt(int index) {
    if (index < 0 || index >= boardCount()) return unknownBoard();
    return *kKnown[index];
}

} // namespace airtak
