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
#include "config/constants/Constants.h"
#include <Arduino.h>
#include <SPI.h>
#include <TFT_eSPI.h>

namespace airtak {

using namespace ui;

static TFT_eSPI s_tft;

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;
static bool       s_bl_asleep       = false;

// ── Private helpers ───────────────────────────────────────────────────────────

static void drawHeader(const char* title) {
    s_tft.fillRect(0, 0, DISP_WIDTH, DISP_HEADER_HEIGHT, COL_HDR_BG);
    s_tft.setTextColor(COL_ACCENT, COL_HDR_BG);
    s_tft.setTextFont(2);
    s_tft.setCursor(6, 4);
    s_tft.print(title);

    // Screen indicator dots (right side of header)
    for (uint8_t i = 0; i < SCREEN_COUNT; i++) {
        int16_t x = (int16_t)(DISP_WIDTH - 10 - (SCREEN_COUNT - 1 - i) * 12);
        int16_t y = DISP_HEADER_HEIGHT / 2;
        if (i == static_cast<uint8_t>(s_current_screen)) {
            s_tft.fillCircle(x, y, 4, COL_ACCENT);
        } else {
            s_tft.drawCircle(x, y, 4, COL_DIM);
        }
    }
}

static void drawFooter() {
    s_tft.fillRect(0, DISP_FOOTER_Y, DISP_WIDTH, DISP_FOOTER_HEIGHT, COL_HDR_BG);
    s_tft.setTextFont(1);

    // Battery
    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    uint16_t bat_col = pct > 20 ? COL_OK : COL_ERR;
    s_tft.setTextColor(bat_col, COL_HDR_BG);
    char bat[16];
    snprintf(bat, sizeof(bat), "BAT:%3d%%%s", pct, Battery::isCharging() ? "+" : " ");
    s_tft.setCursor(4, DISP_FOOTER_Y + 6);
    s_tft.print(bat);

    // BLE status
    s_tft.setTextColor(ble::BLEServer::isConnected() ? COL_ACCENT : COL_DIM, COL_HDR_BG);
    s_tft.setCursor(DISP_WIDTH - 36, DISP_FOOTER_Y + 6);
    s_tft.print(ble::BLEServer::isConnected() ? "BLE" : "   ");
}

static void renderMainScreen() {
#ifdef AIRTAK_FLASH_TEST
    drawHeader("FLASH TEST OK");
#else
    drawHeader(FIRMWARE_DISPLAY_TITLE);
#endif

    s_tft.setTextFont(2);
    int16_t y = DISP_CONTENT_Y + 2;
    constexpr int16_t LINE_H = DISP_LINE_HEIGHT;

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
    s_tft.setCursor(4, DISP_CONTENT_Y + 8);
    s_tft.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_tft.setTextFont(2);
    s_tft.setTextColor(COL_DIM, COL_BG);
    s_tft.setCursor(4, DISP_CONTENT_Y + 8);
    s_tft.print("(no players)");
    drawFooter();
}

static void renderRadioScreen() {
    drawHeader("RADIO");

    s_tft.setTextFont(2);
    int16_t y = DISP_CONTENT_Y + 4;
    const int16_t LINE_H = DISP_LINE_HEIGHT + 4;

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

// Backlight state for pulse-train protocol
static uint8_t  s_bl_level = 0;


// LilyGo T-Deck backlight chip counts LOW→HIGH edges on GPIO 42.
// Each edge increments an internal counter; the brightness is the counter value.
// 0 = off, 16 = max.  To turn off, hold the pin LOW.
static void applyBacklight(uint8_t target) {
    if (target > BL_STEPS) target = BL_STEPS;
    if (target == 0) {
        digitalWrite(AIRTAK_PIN_TFT_BL, LOW);
        delay(TFT_BACKLIGHT_OFF_HOLD_MS);                              // backlight chip needs ms-level hold
        s_bl_level = 0;
        return;
    }
    if (s_bl_level == 0) {
        digitalWrite(AIRTAK_PIN_TFT_BL, HIGH);
        s_bl_level = BL_STEPS;
        delayMicroseconds(30);
    }
    int from = BL_STEPS - s_bl_level;
    int to   = BL_STEPS - target;
    int num  = (BL_STEPS + to - from) % BL_STEPS;
    for (int i = 0; i < num; i++) {
        digitalWrite(AIRTAK_PIN_TFT_BL, LOW);
        digitalWrite(AIRTAK_PIN_TFT_BL, HIGH);
    }
    s_bl_level = target;
}

void Display::init() {
    pinMode(AIRTAK_PIN_TFT_BL, OUTPUT);
    digitalWrite(AIRTAK_PIN_TFT_BL, HIGH);
    delayMicroseconds(30);
    s_bl_level = BL_STEPS;

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
    uint8_t level = (uint8_t)(((uint16_t)pct * BL_STEPS + 50) / 100);
    if (level == 0) level = 1;
    if (!s_bl_asleep) applyBacklight(level);  // don't wake backlight while display is asleep
}

void Display::sleep() {
    s_bl_asleep = true;
    applyBacklight(0);
    s_tft.writecommand(0x10);
}

void Display::wake() {
    s_bl_asleep = false;
    s_tft.writecommand(0x11);
    delay(TFT_WAKE_DELAY_MS);
    uint8_t pct = config::ConfigManager::powerBrightnessPct();
    uint8_t level = (uint8_t)(((uint16_t)pct * BL_STEPS + 50) / 100);
    if (level == 0) level = 1;
    applyBacklight(level);
}

} // namespace airtak
