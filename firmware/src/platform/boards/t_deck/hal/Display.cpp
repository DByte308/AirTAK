// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/t_deck/hal/Display.cpp — ST7789 TFT display driver
// Planning: §9.6 | Roadmap: 1.4
//
// Implements the board-agnostic Display.h interface using TFT_eSPI on the
// LilyGo T-Deck's 2.8″ 320×240 ST7789 TFT (landscape, rotation 1).
//
// TFT_eSPI is configured entirely through build_flags in platformio.ini
// (USER_SETUP_LOADED, ST7789_DRIVER, pin defines) — no User_Setup.h required.
//
// SPI bus note: TFT (CS=12) and SX1262 (CS=9) share the same SPI bus
// (SCK=40, MOSI=41, MISO=38).  Concurrent access from display (priority 1) and
// radio (priority 4) tasks is avoided in practice by the task-priority scheme;
// see BoardConfig.h for the full rationale.

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
#include <TFT_eSPI.h>

namespace airtak {

using namespace ui;

// ── Layout constants (landscape 320×240) ─────────────────────────────────────
constexpr uint16_t TFT_W    = 320;
constexpr uint16_t TFT_H    = 240;
constexpr uint16_t HDR_H    = 24;   // header row height
constexpr uint16_t FTR_H    = 22;   // footer row height
constexpr uint16_t CONTENT_Y = HDR_H + 2;
constexpr uint16_t FOOTER_Y  = TFT_H - FTR_H;

// ── Colour palette ────────────────────────────────────────────────────────────
constexpr uint16_t COL_BG        = TFT_BLACK;
constexpr uint16_t COL_HDR_BG    = 0x1082;   // dark blue-grey
constexpr uint16_t COL_TEXT      = TFT_WHITE;
constexpr uint16_t COL_ACCENT    = 0x07FF;   // cyan
constexpr uint16_t COL_OK        = TFT_GREEN;
constexpr uint16_t COL_WARN      = TFT_YELLOW;
constexpr uint16_t COL_ERR       = TFT_RED;
constexpr uint16_t COL_DIM       = 0x7BEF;   // mid-grey

static TFT_eSPI s_tft;

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

// ── Private helpers ───────────────────────────────────────────────────────────

static void drawHeader(const char* title) {
    s_tft.fillRect(0, 0, TFT_W, HDR_H, COL_HDR_BG);
    s_tft.setTextColor(COL_ACCENT, COL_HDR_BG);
    s_tft.setTextFont(2);
    s_tft.setCursor(6, 4);
    s_tft.print(title);

    // Screen indicator dots (right side of header)
    for (uint8_t i = 0; i < SCREEN_COUNT; i++) {
        int16_t x = (int16_t)(TFT_W - 10 - (SCREEN_COUNT - 1 - i) * 12);
        int16_t y = HDR_H / 2;
        if (i == static_cast<uint8_t>(s_current_screen)) {
            s_tft.fillCircle(x, y, 4, COL_ACCENT);
        } else {
            s_tft.drawCircle(x, y, 4, COL_DIM);
        }
    }
}

static void drawFooter() {
    s_tft.fillRect(0, FOOTER_Y, TFT_W, FTR_H, COL_HDR_BG);
    s_tft.setTextFont(1);

    // Battery
    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    uint16_t bat_col = pct > 20 ? COL_OK : COL_ERR;
    s_tft.setTextColor(bat_col, COL_HDR_BG);
    char bat[16];
    snprintf(bat, sizeof(bat), "BAT:%3d%%%s", pct, Battery::isCharging() ? "+" : " ");
    s_tft.setCursor(4, FOOTER_Y + 6);
    s_tft.print(bat);

    // BLE status
    s_tft.setTextColor(ble::BLEServer::isConnected() ? COL_ACCENT : COL_DIM, COL_HDR_BG);
    s_tft.setCursor(TFT_W - 36, FOOTER_Y + 6);
    s_tft.print(ble::BLEServer::isConnected() ? "BLE" : "   ");
}

static void renderMainScreen() {
    drawHeader("AirTAK");

    s_tft.setTextFont(2);
    int16_t y = CONTENT_Y + 2;
    constexpr int16_t LINE_H = 18;

    // ── Device identity ───────────────────────────────────────────────────────
    {
        char namebuf[48];
        const char* longn  = config::ConfigManager::deviceLongName();
        const char* shortn = config::ConfigManager::deviceShortName();
        snprintf(namebuf, sizeof(namebuf), "%.20s (%.6s)", longn, shortn);
        s_tft.setTextColor(COL_ACCENT, COL_BG);
        s_tft.setCursor(4, y); s_tft.print(namebuf);
        y += LINE_H;
    }

    // ── Callsign ──────────────────────────────────────────────────────────────
    {
        char csbuf[32];
        snprintf(csbuf, sizeof(csbuf), "CS: %.16s", config::ConfigManager::gameCallsign());
        s_tft.setTextColor(COL_TEXT, COL_BG);
        s_tft.setCursor(4, y); s_tft.print(csbuf);
        y += LINE_H + 2;
    }

    // ── GPS ───────────────────────────────────────────────────────────────────
    {
        const GpsFix& fix = GPS::currentFix();
        char buf[48];

        if (GPS::hasFix()) {
            // Valid fix — show coordinates on one line, detail on next
            s_tft.setTextColor(COL_OK, COL_BG);
            snprintf(buf, sizeof(buf), "GPS %.5f %.5f", fix.latitude, fix.longitude);
            s_tft.setCursor(4, y); s_tft.print(buf); y += LINE_H;
            s_tft.setTextColor(COL_TEXT, COL_BG);
            snprintf(buf, sizeof(buf), "    %.0fm  SAT:%u  HDOP:%.1f",
                     fix.altitude_m, fix.satellites, fix.hdop);
            s_tft.setCursor(4, y); s_tft.print(buf);
        } else {
            // No fix yet — show search status and whatever satellite count we have
            uint8_t sats = fix.satellites; // updated unconditionally in GPS::process()
            if (sats > 0) {
                s_tft.setTextColor(COL_WARN, COL_BG);
                snprintf(buf, sizeof(buf), "GPS SEARCHING  SAT:%u  HDOP:%.1f",
                         sats, fix.hdop > 0 ? fix.hdop : 0.0f);
            } else {
                s_tft.setTextColor(COL_ERR, COL_BG);
                snprintf(buf, sizeof(buf), "GPS NO SIGNAL");
            }
            s_tft.setCursor(4, y); s_tft.print(buf);
        }
        y += LINE_H + 2;
    }

    // ── Radio / channel ───────────────────────────────────────────────────────
    s_tft.setTextColor(COL_TEXT, COL_BG);
    {
        const ChannelSlot* primary = ChannelManager::primarySlot();
        if (primary) {
            char buf[40];
            snprintf(buf, sizeof(buf), "CH: %-10s  RSSI:%.0fdBm",
                     primary->name, Radio::lastRssi());
            s_tft.setCursor(4, y); s_tft.print(buf);
        }
        y += LINE_H;
    }

    // ── Mesh ──────────────────────────────────────────────────────────────────
    {
        uint32_t n = MeshNode::knownNodeCount();
        char mbuf[32];
        snprintf(mbuf, sizeof(mbuf), "MESH: %lu node%s",
                 static_cast<unsigned long>(n), n == 1 ? "" : "s");
        s_tft.setCursor(4, y); s_tft.print(mbuf);
        y += LINE_H;
    }

    // ── Node ID ───────────────────────────────────────────────────────────────
    {
        char idbuf[24];
        snprintf(idbuf, sizeof(idbuf), "ID: %08lX",
                 static_cast<unsigned long>(MeshNode::nodeId()));
        s_tft.setTextColor(COL_DIM, COL_BG);
        s_tft.setCursor(4, y); s_tft.print(idbuf);
    }

    drawFooter();
}

static void renderMessagesScreen() {
    drawHeader("MESSAGES");
    s_tft.setTextFont(2);
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(4, CONTENT_Y + 8);
    s_tft.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_tft.setTextFont(2);
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(4, CONTENT_Y + 8);
    s_tft.print("(no players)");
    drawFooter();
}

static void renderRadioScreen() {
    drawHeader("RADIO");

    s_tft.setTextFont(2);
    int16_t y = CONTENT_Y + 4;
    const int16_t LINE_H = 22;

    s_tft.setTextColor(COL_TEXT, COL_BG);
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[40];
        snprintf(buf, sizeof(buf), "Channel: %s", primary->name);
        s_tft.setCursor(4, y); s_tft.print(buf); y += LINE_H;
        snprintf(buf, sizeof(buf), "PHY profile: %u", primary->phy_profile_index);
        s_tft.setCursor(4, y); s_tft.print(buf); y += LINE_H;
    }

    char rbuf[40];
    snprintf(rbuf, sizeof(rbuf), "RSSI: %.0f dBm", Radio::lastRssi());
    s_tft.setCursor(4, y); s_tft.print(rbuf); y += LINE_H;
    snprintf(rbuf, sizeof(rbuf), "SNR:  %.1f dB", Radio::lastSnr());
    s_tft.setCursor(4, y); s_tft.print(rbuf); y += LINE_H;
    snprintf(rbuf, sizeof(rbuf), "RX: %-6lu TX: %lu",
             static_cast<unsigned long>(Radio::packetsRx()),
             static_cast<unsigned long>(Radio::packetsTx()));
    s_tft.setCursor(4, y); s_tft.print(rbuf);

    drawFooter();
}

// ── Public API ────────────────────────────────────────────────────────────────

void Display::init() {
    // Backlight on
    pinMode(AIRTAK_PIN_TFT_BL, OUTPUT);
    digitalWrite(AIRTAK_PIN_TFT_BL, HIGH);

    s_tft.init();
    s_tft.setRotation(1);   // landscape: 320 wide, 240 tall
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

    s_tft.setTextFont(4);
    s_tft.setTextColor(COL_ACCENT, COL_BG);
    s_tft.setCursor(10, 20);
    s_tft.print("Pairing — confirm code:");

    char buf[8];
    snprintf(buf, sizeof(buf), "%06lu", (unsigned long)passkey);
    s_tft.setTextFont(7);
    s_tft.setTextColor(COL_OK, COL_BG);
    s_tft.setCursor(40, 70);
    s_tft.print(buf);

    s_tft.setTextFont(2);
    s_tft.setTextColor(COL_TEXT, COL_BG);
    s_tft.setCursor(10, 180);
    s_tft.print("Approve on your phone to complete pairing.");

    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

void Display::showRecoveryMode(bool config_erased, bool bonds_cleared) {
    s_tft.fillScreen(COL_BG);

    s_tft.setTextFont(4);
    s_tft.setTextColor(COL_WARN, COL_BG);
    s_tft.setCursor(10, 10);
    s_tft.print("SAFE MODE");

    s_tft.setTextFont(2);
    s_tft.setTextColor(COL_TEXT, COL_BG);
    int16_t y = 60;
    const int16_t LH = 20;

    s_tft.setCursor(10, y); s_tft.print(config_erased ? "Config: ERASED" : "Config: kept"); y += LH;
    s_tft.setCursor(10, y); s_tft.print(bonds_cleared ? "BLE bonds: CLEARED" : "BLE bonds: kept"); y += LH + 4;
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(10, y); s_tft.print("BLE advertising as 'AirTAK Recovery'"); y += LH;
    s_tft.setCursor(10, y); s_tft.print("Hold 10 s: erase config + reboot"); y += LH;
    s_tft.setCursor(10, y); s_tft.print("Power cycle to exit safe mode."); y += LH;

    s_dirty_mask = 0;
    s_last_refresh_ms = millis();
}

void Display::nextScreen() {
    uint8_t next = (static_cast<uint8_t>(s_current_screen) + 1) % SCREEN_COUNT;
    s_current_screen = static_cast<ScreenId>(next);
    markDirty(DISPLAY_DIRTY_ALL);
}

ui::ScreenId Display::currentScreen()    { return s_current_screen; }
void Display::markDirty(uint16_t mask)   { s_dirty_mask |= mask; }
void Display::clearDirty()               { s_dirty_mask = 0; }
void Display::forceRefresh()             { s_dirty_mask = DISPLAY_DIRTY_ALL; s_last_refresh_ms = 0; }

} // namespace airtak
