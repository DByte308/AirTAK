// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/Display.cpp — SSD1681 E-Ink display driver implementation
// Planning: §9.6, §7.1 | Roadmap: 1.4

#include "hal/Display.h"
#include "ui/Screens.h"
#include "hal/Battery.h"
#include "hal/GPS.h"
#include "hal/Radio.h"
#include "ble/BLEServer.h"
#include "mesh/MeshNode.h"
#include "mesh/ChannelManager.h"
#include "config/Defaults.h"
#include "config.h"
#include <Arduino.h>
#include <SPI.h>

// GxEPD2 — SSD1681 200×200 1-bit controller (ZinggJM/GxEPD2 ≥ 1.5.4)
#include <GxEPD2_BW.h>
#include <epd/GxEPD2_154_D67.h>      // SSD1681 200x200 display variant

// Adafruit GFX for text/graphics primitives (dependency of GxEPD2)
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold9pt7b.h>

namespace mash {

using namespace ui;

// ── Display hardware instance ─────────────────────────────────────────────────
// GxEPD2_BW<DisplayClass, PageHeight> — page height ≤ display height.
// SSD1681 with 200×200 resolution, 200-line single-page buffer (full update).
static GxEPD2_BW<GxEPD2_154_D67, GxEPD2_154_D67::HEIGHT>
    s_display(GxEPD2_154_D67(MASH_PIN_EINK_CS, MASH_PIN_EINK_DC, MASH_PIN_EINK_RST, MASH_PIN_EINK_BUSY));

// Custom SPI instance for the dedicated e-ink SPI bus (separate from radio SPI).
// SSD1681 is write-only — MISO is not connected. BUSY is used as a placeholder.
static SPIClass s_eink_spi(NRF_SPIM2, MASH_PIN_EINK_BUSY /*miso unused*/, MASH_PIN_EINK_SCK, MASH_PIN_EINK_MOSI);

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

// ── Internal helpers ──────────────────────────────────────────────────────────
static void drawHeader(const char* title) {
    s_display.setFont(&FreeSansBold9pt7b);
    s_display.setTextColor(GxEPD_BLACK);
    s_display.setCursor(4, 14);
    s_display.print(title);
    // Separator line below header
    s_display.drawLine(0, ROW_HEADER_END, EINK_WIDTH, ROW_HEADER_END, GxEPD_BLACK);
    // Screen indicator dots (right side of header)
    for (uint8_t i = 0; i < SCREEN_COUNT; i++) {
        uint16_t x = EINK_WIDTH - 4 - (SCREEN_COUNT - 1 - i) * 7;
        uint16_t y = 8;
        if (i == static_cast<uint8_t>(s_current_screen)) {
            s_display.fillCircle(x, y, 3, GxEPD_BLACK);
        } else {
            s_display.drawCircle(x, y, 3, GxEPD_BLACK);
        }
    }
}

static void drawFooter() {
    s_display.drawLine(0, ROW_FOOTER_START, EINK_WIDTH, ROW_FOOTER_START, GxEPD_BLACK);
    s_display.setFont(nullptr); // small built-in font
    s_display.setTextColor(GxEPD_BLACK);

    // Battery indicator (right side of footer)
    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    char bat[12];
    snprintf(bat, sizeof(bat), "%d%%%s", pct, Battery::isCharging() ? "+" : "");
    s_display.setCursor(EINK_WIDTH - 32, ROW_FOOTER_START + 9);
    s_display.print(bat);

    // BLE indicator (left side of footer)
    s_display.setCursor(2, ROW_FOOTER_START + 9);
    s_display.print(mash::ble::BLEServer::isConnected() ? "BLE" : "   ");
}

static void renderMainScreen() {
    drawHeader("MASH");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);

    uint16_t y = ROW_CONTENT_START + 14;

    // GPS row
    if (GPS::hasFix()) {
        const GpsFix& fix = GPS::currentFix();
        char buf[32];
        snprintf(buf, sizeof(buf), "%.4f, %.4f", fix.latitude, fix.longitude);
        s_display.setCursor(4, y); s_display.print(buf);
        y += 18;
        snprintf(buf, sizeof(buf), "%.0fm  SAT:%u  HDOP:%.1f",
                 fix.altitude_m, fix.satellites, fix.hdop);
        s_display.setCursor(4, y); s_display.print(buf);
    } else {
        s_display.setCursor(4, y); s_display.print("GPS: NO FIX");
    }
    y += 22;

    // Radio row
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[32];
        snprintf(buf, sizeof(buf), "CH: %s  %.0fdBm", primary->name, Radio::lastRssi());
        s_display.setCursor(4, y); s_display.print(buf);
    }
    y += 18;

    // Mesh row
    char mbuf[24];
    uint32_t knownNodeCount = MeshNode::knownNodeCount();
    snprintf(mbuf, sizeof(mbuf), "MESH: %lu node%s",
             static_cast<unsigned long>(knownNodeCount), knownNodeCount == 1 ? "" : "s");
    s_display.setCursor(4, y); s_display.print(mbuf);
    y += 18;

    // Node ID (short, last 4 hex digits)
    char idbuf[16];
    snprintf(idbuf, sizeof(idbuf), "ID: %08lX", static_cast<unsigned long>(MeshNode::nodeId()));
    s_display.setCursor(4, y); s_display.print(idbuf);

    drawFooter();
}

static void renderMessagesScreen() {
    drawHeader("MESSAGES");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);
    s_display.setCursor(4, ROW_CONTENT_START + 14);
    // Phase 3.5: messages delivered from BLE MESH_RX are rendered here.
    s_display.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);
    s_display.setCursor(4, ROW_CONTENT_START + 14);
    // Phase 4.2: player list rendered here after MASH_PLAYER RX implemented.
    s_display.print("(no players)");
    drawFooter();
}

static void renderRadioScreen() {
    drawHeader("RADIO");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);

    uint16_t y = ROW_CONTENT_START + 14;
    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[32];
        snprintf(buf, sizeof(buf), "Chan: %s", primary->name);
        s_display.setCursor(4, y); s_display.print(buf); y += 18;
        snprintf(buf, sizeof(buf), "PHY:  %u", primary->phy_profile_index);
        s_display.setCursor(4, y); s_display.print(buf); y += 18;
    }

    char rbuf[32];
    snprintf(rbuf, sizeof(rbuf), "RSSI: %.0f dBm", Radio::lastRssi());
    s_display.setCursor(4, y); s_display.print(rbuf); y += 18;
    snprintf(rbuf, sizeof(rbuf), "SNR:  %.1f dB", Radio::lastSnr());
    s_display.setCursor(4, y); s_display.print(rbuf); y += 18;
    snprintf(rbuf, sizeof(rbuf), "RX:%lu TX:%lu",
             static_cast<unsigned long>(Radio::packetsRx()),
             static_cast<unsigned long>(Radio::packetsTx()));
    s_display.setCursor(4, y); s_display.print(rbuf);

    drawFooter();
}

// ── Display::init() ──────────────────────────────────────────────────────────
void Display::init() {
    pinMode(MASH_PIN_EINK_EN, OUTPUT);
    digitalWrite(MASH_PIN_EINK_EN, HIGH);

    // Initialize the dedicated SPI bus for the e-ink display.
    s_eink_spi.begin();

    // Initialize GxEPD2 with the dedicated SPI instance.
    s_display.init(115200, true, 2, false, s_eink_spi, SPISettings(4000000, MSBFIRST, SPI_MODE0));

    s_last_refresh_ms = 0;
    s_dirty_mask      = DISPLAY_DIRTY_ALL;
}

// ── Display::refresh() ───────────────────────────────────────────────────────
void Display::refresh(EventGroupHandle_t system_event_group) {
    (void)system_event_group;

    if (s_dirty_mask == 0) return;

    uint32_t now = millis();
    if (s_last_refresh_ms != 0 && (now - s_last_refresh_ms) < EINK_MIN_REFRESH_MS) return;

    // Full-screen update: clear + render current screen content.
    s_display.setFullWindow();
    s_display.firstPage();
    do {
        s_display.fillScreen(GxEPD_WHITE);
        switch (s_current_screen) {
            case ScreenId::MAIN:     renderMainScreen();     break;
            case ScreenId::MESSAGES: renderMessagesScreen(); break;
            case ScreenId::PLAYERS:  renderPlayersScreen();  break;
            case ScreenId::RADIO:    renderRadioScreen();    break;
        }
    } while (s_display.nextPage());

    s_last_refresh_ms = now;
    s_dirty_mask      = 0;
}

// ── Display::showPasscode() ───────────────────────────────────────────────────
// Immediately render the BLE Numeric Comparison passkey (§10.6).
// Bypasses the normal refresh interval because passkey timeout is short.
void Display::showPasscode(uint32_t passkey) {
    s_display.setFullWindow();
    s_display.firstPage();
    do {
        s_display.fillScreen(GxEPD_WHITE);
        s_display.setFont(&FreeSansBold9pt7b);
        s_display.setTextColor(GxEPD_BLACK);
        s_display.setCursor(4, 30);
        s_display.print("Pair code:");
        char buf[8];
        snprintf(buf, sizeof(buf), "%06lu", (unsigned long)passkey);
        // Large font for the passkey digits
        s_display.setFont(&FreeSansBold9pt7b);
        s_display.setCursor(30, 80);
        s_display.setTextSize(3);
        s_display.print(buf);
        s_display.setTextSize(1);
        s_display.setFont(nullptr);
        s_display.setCursor(4, 130);
        s_display.print("Confirm on your phone");
        s_display.setCursor(4, 145);
        s_display.print("to complete pairing.");
    } while (s_display.nextPage());
    // Mark dirty so next full refresh restores the normal screen.
    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

// ── Display control ───────────────────────────────────────────────────────────
void Display::nextScreen() {
    uint8_t next = (static_cast<uint8_t>(s_current_screen) + 1) % SCREEN_COUNT;
    s_current_screen = static_cast<ScreenId>(next);
    markDirty(DISPLAY_DIRTY_ALL);
}

ui::ScreenId Display::currentScreen()     { return s_current_screen; }
void Display::markDirty(uint16_t mask)     { s_dirty_mask |= mask; }
void Display::clearDirty()                 { s_dirty_mask = 0; }
void Display::forceRefresh()               { s_dirty_mask = DISPLAY_DIRTY_ALL; s_last_refresh_ms = 0; }

} // namespace mash
