// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/heltec_t114/hal/Display.cpp — ST7789 TFT display driver
// Planning: §9.6 | Roadmap: 1.4
//
// Implements the board-agnostic Display.h interface using Adafruit ST7789 on the
// Heltec Mesh Node T114's 1.14" 240×135 TFT (portrait, rotation 0).
//
// The ST7789 is connected via SPI1 (SCK=P1.08, MOSI=P1.09), separate from the
// SX1262 radio's SPI0 bus.  No bus sharing, no concurrency concerns.

#include "hal/Display.h"
#include "ui/Screens.h"
#include "hal/Battery.h"
#include "hal/GPS.h"
#include "hal/Radio.h"
#include "ble/BLEServer.h"
#include "mesh/MeshNode.h"
#include "mesh/ChannelManager.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "config.h"
#include <Arduino.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>

namespace airtak {

using namespace ui;

// ── Layout constants (portrait 240×135) ──────────────────────────────────────
constexpr uint16_t TFT_W    = 240;
constexpr uint16_t TFT_H    = 135;
constexpr uint16_t HDR_H    = 14;   // header row height
constexpr uint16_t FTR_H    = 12;   // footer row height
constexpr uint16_t CONTENT_Y = HDR_H + 2;
constexpr uint16_t FOOTER_Y  = TFT_H - FTR_H;

// ── Colour palette ───────────────────────────────────────────────────────────
constexpr uint16_t COL_BG        = ST77XX_BLACK;
constexpr uint16_t COL_HDR_BG    = 0x1082;
constexpr uint16_t COL_TEXT      = ST77XX_WHITE;
constexpr uint16_t COL_ACCENT    = 0x07FF;
constexpr uint16_t COL_OK        = ST77XX_GREEN;
constexpr uint16_t COL_WARN      = ST77XX_YELLOW;
constexpr uint16_t COL_ERR       = ST77XX_RED;
constexpr uint16_t COL_DIM       = 0x7BEF;

// Separate SPI2 instance for the ST7789 (radio uses SPI/SPIM3).
// The nRF52840 SPIClass constructor is:
//   SPIClass(NRF_SPIM_Type *p_spi, uint32_t miso, uint32_t sck, uint32_t mosi)
// MISO is a dummy — the ST7789 uses a 3-wire SPI (MOSI + SCK only).
static SPIClass s_tft_spi(AIRTAK_TFT_SPI_INSTANCE,
                          AIRTAK_PIN_TFT_MISO,
                          AIRTAK_PIN_TFT_SCK,
                          AIRTAK_PIN_TFT_MOSI);

static Adafruit_ST7789 s_tft(&s_tft_spi, AIRTAK_PIN_TFT_CS, AIRTAK_PIN_TFT_DC, AIRTAK_PIN_TFT_RST);

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

static void drawHeader(const char* title) {
    s_tft.fillRect(0, 0, TFT_W, HDR_H, COL_HDR_BG);
    s_tft.setTextColor(COL_ACCENT, COL_HDR_BG);
    s_tft.setTextSize(1);
    s_tft.setCursor(4, 3);
    s_tft.print(title);

    for (uint8_t i = 0; i < SCREEN_COUNT; i++) {
        int16_t x = (int16_t)(TFT_W - 8 - (SCREEN_COUNT - 1 - i) * 10);
        int16_t y = HDR_H / 2;
        if (i == static_cast<uint8_t>(s_current_screen)) {
            s_tft.fillCircle(x, y, 3, COL_ACCENT);
        } else {
            s_tft.drawCircle(x, y, 3, COL_DIM);
        }
    }
}

static void drawFooter() {
    s_tft.fillRect(0, FOOTER_Y, TFT_W, FTR_H, COL_HDR_BG);
    s_tft.setTextSize(1);

    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    uint16_t bat_col = pct > 20 ? COL_OK : COL_ERR;
    s_tft.setTextColor(bat_col, COL_HDR_BG);
    char bat[12];
    snprintf(bat, sizeof(bat), "B:%3d%%%s", pct, Battery::isCharging() ? "+" : " ");
    s_tft.setCursor(2, FOOTER_Y + 2);
    s_tft.print(bat);

    s_tft.setTextColor(ble::BLEServer::isConnected() ? COL_ACCENT : COL_DIM, COL_HDR_BG);
    s_tft.setCursor(TFT_W - 22, FOOTER_Y + 2);
    s_tft.print(ble::BLEServer::isConnected() ? "BLE" : "   ");
}

static void renderMainScreen() {
    drawHeader("AirTAK");

    s_tft.setTextSize(1);
    int16_t y = CONTENT_Y + 2;
    constexpr int16_t LINE_H = 12;

    {
        char namebuf[32];
        const char* longn  = config::ConfigManager::deviceLongName();
        const char* shortn = config::ConfigManager::deviceShortName();
        snprintf(namebuf, sizeof(namebuf), "%.14s (%.6s)", longn, shortn);
        s_tft.setTextColor(COL_ACCENT, COL_BG);
        s_tft.setCursor(2, y); s_tft.print(namebuf);
        y += LINE_H;
    }

    {
        char csbuf[24];
        snprintf(csbuf, sizeof(csbuf), "CS: %.12s", config::ConfigManager::gameCallsign());
        s_tft.setTextColor(COL_TEXT, COL_BG);
        s_tft.setCursor(2, y); s_tft.print(csbuf);
        y += LINE_H;
    }

    {
        const GpsFix& fix = GPS::currentFix();
        char buf[40];
        if (GPS::hasFix()) {
            s_tft.setTextColor(COL_OK, COL_BG);
            snprintf(buf, sizeof(buf), "GPS %.4f %.4f", fix.latitude, fix.longitude);
            s_tft.setCursor(2, y); s_tft.print(buf); y += LINE_H;
            s_tft.setTextColor(COL_TEXT, COL_BG);
            snprintf(buf, sizeof(buf), "  %.0fm SAT:%u HDOP:%.1f",
                     fix.altitude_m, fix.satellites, fix.hdop);
            s_tft.setCursor(2, y); s_tft.print(buf);
        } else {
            uint8_t sats = fix.satellites;
            if (sats > 0) {
                s_tft.setTextColor(COL_WARN, COL_BG);
                snprintf(buf, sizeof(buf), "GPS SRCH  SAT:%u", sats);
            } else {
                s_tft.setTextColor(COL_ERR, COL_BG);
                snprintf(buf, sizeof(buf), "GPS NO SIG");
            }
            s_tft.setCursor(2, y); s_tft.print(buf);
        }
        y += LINE_H + 2;
    }

    s_tft.setTextColor(COL_TEXT, COL_BG);
    {
        const ChannelSlot* primary = ChannelManager::primarySlot();
        if (primary) {
            char buf[30];
            snprintf(buf, sizeof(buf), "CH:%-8s RSSI:%.0f", primary->name, Radio::lastRssi());
            s_tft.setCursor(2, y); s_tft.print(buf);
        }
        y += LINE_H;
    }

    {
        uint32_t n = MeshNode::knownNodeCount();
        char mbuf[20];
        snprintf(mbuf, sizeof(mbuf), "MESH:%lu node%s",
                 static_cast<unsigned long>(n), n == 1 ? "" : "s");
        s_tft.setCursor(2, y); s_tft.print(mbuf);
        y += LINE_H;
    }

    {
        char idbuf[18];
        snprintf(idbuf, sizeof(idbuf), "ID:%08lX", static_cast<unsigned long>(MeshNode::nodeId()));
        s_tft.setTextColor(COL_DIM, COL_BG);
        s_tft.setCursor(2, y); s_tft.print(idbuf);
    }

    drawFooter();
}

static void renderMessagesScreen() {
    drawHeader("MESSAGES");
    s_tft.setTextSize(1);
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(2, CONTENT_Y + 6);
    s_tft.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_tft.setTextSize(1);
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(2, CONTENT_Y + 6);
    s_tft.print("(no players)");
    drawFooter();
}

static void renderRadioScreen() {
    drawHeader("RADIO");

    s_tft.setTextSize(1);
    int16_t y = CONTENT_Y + 2;
    constexpr int16_t LINE_H = 13;

    s_tft.setTextColor(COL_TEXT, COL_BG);
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[30];
        snprintf(buf, sizeof(buf), "Chan: %s", primary->name);
        s_tft.setCursor(2, y); s_tft.print(buf); y += LINE_H;
        snprintf(buf, sizeof(buf), "PHY:  %u", primary->phy_profile_index);
        s_tft.setCursor(2, y); s_tft.print(buf); y += LINE_H;
    }

    char rbuf[30];
    snprintf(rbuf, sizeof(rbuf), "RSSI: %.0f dBm", Radio::lastRssi());
    s_tft.setCursor(2, y); s_tft.print(rbuf); y += LINE_H;
    snprintf(rbuf, sizeof(rbuf), "SNR:  %.1f dB", Radio::lastSnr());
    s_tft.setCursor(2, y); s_tft.print(rbuf); y += LINE_H;
    snprintf(rbuf, sizeof(rbuf), "RX:%-5lu TX:%lu",
             static_cast<unsigned long>(Radio::packetsRx()),
             static_cast<unsigned long>(Radio::packetsTx()));
    s_tft.setCursor(2, y); s_tft.print(rbuf);

    drawFooter();
}

// ── Public API ────────────────────────────────────────────────────────────────

void Display::init() {
    // Enable peripheral power rail (VEXT).
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
    digitalWrite(AIRTAK_PIN_POWER_EN, HIGH);
    delay(50); // allow peripherals to stabilise

    // Enable ADC control pin before reading battery.
    pinMode(AIRTAK_PIN_ADC_CTRL, OUTPUT);
    digitalWrite(AIRTAK_PIN_ADC_CTRL, HIGH);

    // Backlight: PWM-capable GPIO for brightness control
    pinMode(AIRTAK_PIN_TFT_BL, OUTPUT);
    analogWrite(AIRTAK_PIN_TFT_BL, 255);    // full brightness initially

    // Initialize SPI1 for the ST7789 display (separate from radio SPI0).
    s_tft_spi.begin();
    s_tft.init(TFT_W, TFT_H);
    s_tft.setRotation(0);   // portrait
    s_tft.fillScreen(COL_BG);
    s_tft.setTextWrap(false);

    s_last_refresh_ms = 0;
    s_dirty_mask      = DISPLAY_DIRTY_ALL;
}

void Display::refresh(EventGroupHandle_t system_event_group) {
    (void)system_event_group;
    if (s_dirty_mask == 0) return;

    s_tft.fillScreen(COL_BG);

    switch (s_current_screen) {
        case ScreenId::MAIN:     renderMainScreen();     break;
        case ScreenId::MESSAGES: renderMessagesScreen(); break;
        case ScreenId::PLAYERS:  renderPlayersScreen();  break;
        case ScreenId::RADIO:    renderRadioScreen();    break;
    }

    s_last_refresh_ms = millis();
    s_dirty_mask      = 0;
}

void Display::showPasscode(uint32_t passkey) {
    s_tft.fillScreen(COL_BG);

    s_tft.setTextSize(2);
    s_tft.setTextColor(COL_ACCENT, COL_BG);
    s_tft.setCursor(4, 10);
    s_tft.print("Pair code:");

    char buf[8];
    snprintf(buf, sizeof(buf), "%06lu", (unsigned long)passkey);
    s_tft.setTextSize(3);
    s_tft.setTextColor(COL_OK, COL_BG);
    s_tft.setCursor(40, 40);
    s_tft.print(buf);

    s_tft.setTextSize(1);
    s_tft.setTextColor(COL_TEXT, COL_BG);
    s_tft.setCursor(4, 90);
    s_tft.print("Confirm on phone");
    s_tft.setCursor(4, 104);
    s_tft.print("to complete pairing.");

    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

void Display::showRecoveryMode(bool config_erased, bool bonds_cleared) {
    s_tft.fillScreen(COL_BG);

    s_tft.setTextSize(2);
    s_tft.setTextColor(COL_WARN, COL_BG);
    s_tft.setCursor(4, 5);
    s_tft.print("SAFE MODE");

    s_tft.setTextSize(1);
    s_tft.setTextColor(COL_TEXT, COL_BG);
    int16_t y = 35;
    const int16_t LH = 12;

    s_tft.setCursor(4, y); s_tft.print(config_erased ? "Config: ERASED" : "Config: kept"); y += LH;
    s_tft.setCursor(4, y); s_tft.print(bonds_cleared ? "BLE bonds: CLEARED" : "BLE bonds: kept"); y += LH + 2;
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(4, y); s_tft.print("Name: AirTAK Recovery"); y += LH;
    s_tft.setCursor(4, y); s_tft.print("Hold 10s: erase cfg"); y += LH;
    s_tft.setCursor(4, y); s_tft.print("Power cycle to exit.");

    s_dirty_mask = 0;
    s_last_refresh_ms = millis();
}

void Display::nextScreen() {
    uint8_t next = (static_cast<uint8_t>(s_current_screen) + 1) % SCREEN_COUNT;
    s_current_screen = static_cast<ScreenId>(next);
    markDirty(DISPLAY_DIRTY_ALL);
}

void Display::previousScreen() {
    uint8_t prev = static_cast<uint8_t>(s_current_screen);
    prev = (prev == 0) ? (SCREEN_COUNT - 1) : (prev - 1);
    s_current_screen = static_cast<ScreenId>(prev);
    markDirty(DISPLAY_DIRTY_ALL);
}

ui::ScreenId Display::currentScreen()    { return s_current_screen; }
void Display::markDirty(uint16_t mask)   { s_dirty_mask |= mask; }
void Display::clearDirty()               { s_dirty_mask = 0; }
void Display::forceRefresh()             { s_dirty_mask = DISPLAY_DIRTY_ALL; s_last_refresh_ms = 0; }

void Display::setBrightness(uint8_t pct) {
    if (pct > 100) pct = 100;
    uint16_t duty = (uint16_t)pct * 255 / 100;
    analogWrite(AIRTAK_PIN_TFT_BL, duty);
}

void Display::sleep() {
    analogWrite(AIRTAK_PIN_TFT_BL, 0);     // backlight off
    // TFT wake-on-touch; no explicit sleep command on ST7789
}

void Display::wake() {
    uint8_t pct = config::ConfigManager::powerBrightnessPct();
    setBrightness(pct);
}

} // namespace airtak
