// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/boards/t_echo_plus/hal/Display.cpp - SSD1681 E-Ink display
// Planning: section 9.6, section 7.1 | Roadmap: 1.4

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

#include <GxEPD2_BW.h>
#include <epd/GxEPD2_154_D67.h>
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold9pt7b.h>

namespace airtak {

using namespace ui;

static GxEPD2_BW<GxEPD2_154_D67, GxEPD2_154_D67::HEIGHT>
    s_display(GxEPD2_154_D67(AIRTAK_PIN_EINK_CS, AIRTAK_PIN_EINK_DC, AIRTAK_PIN_EINK_RST, AIRTAK_PIN_EINK_BUSY));

static SPIClass s_eink_spi(AIRTAK_EINK_SPI_INSTANCE, AIRTAK_PIN_EINK_BUSY, AIRTAK_PIN_EINK_SCK, AIRTAK_PIN_EINK_MOSI);

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

static void drawHeader(const char* title) {
    s_display.setFont(&FreeSansBold9pt7b);
    s_display.setTextColor(GxEPD_BLACK);
    s_display.setCursor(4, 14);
    s_display.print(title);
    s_display.drawLine(0, ROW_HEADER_END, EINK_WIDTH, ROW_HEADER_END, GxEPD_BLACK);
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
    s_display.setFont(nullptr);
    s_display.setTextColor(GxEPD_BLACK);

    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    char bat[12];
    snprintf(bat, sizeof(bat), "%d%%%s", pct, Battery::isCharging() ? "+" : "");
    s_display.setCursor(EINK_WIDTH - 32, ROW_FOOTER_START + 9);
    s_display.print(bat);

    s_display.setCursor(2, ROW_FOOTER_START + 9);
    s_display.print(airtak::ble::BLEServer::isConnected() ? "BLE" : "   ");
}

static void renderMainScreen() {
    drawHeader("AirTAK");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);

    uint16_t y = ROW_CONTENT_START + 14;

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

    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[32];
        snprintf(buf, sizeof(buf), "CH: %s  %.0fdBm", primary->name, Radio::lastRssi());
        s_display.setCursor(4, y); s_display.print(buf);
    }
    y += 18;

    char mbuf[24];
    uint32_t knownNodeCount = MeshNode::knownNodeCount();
    snprintf(mbuf, sizeof(mbuf), "MESH: %lu node%s",
             static_cast<unsigned long>(knownNodeCount), knownNodeCount == 1 ? "" : "s");
    s_display.setCursor(4, y); s_display.print(mbuf);
    y += 18;

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
    s_display.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_display.setFont(&FreeSans9pt7b);
    s_display.setTextColor(GxEPD_BLACK);
    s_display.setCursor(4, ROW_CONTENT_START + 14);
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

void Display::init() {
    pinMode(AIRTAK_PIN_EINK_EN, OUTPUT);
    digitalWrite(AIRTAK_PIN_EINK_EN, HIGH);

    s_eink_spi.begin();
    s_display.init(115200, true, 2, false, s_eink_spi, SPISettings(4000000, MSBFIRST, SPI_MODE0));

    s_last_refresh_ms = 0;
    s_dirty_mask      = DISPLAY_DIRTY_ALL;
}

void Display::refresh(EventGroupHandle_t system_event_group) {
    (void)system_event_group;

    if (s_dirty_mask == 0) return;

    uint32_t now = millis();
    if (s_last_refresh_ms != 0 && (now - s_last_refresh_ms) < EINK_MIN_REFRESH_MS) return;

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
    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

void Display::showRecoveryMode(bool config_erased, bool bonds_cleared) {
    s_display.setFullWindow();
    s_display.firstPage();
    do {
        s_display.fillScreen(GxEPD_WHITE);
        s_display.setTextColor(GxEPD_BLACK);
        s_display.setFont(&FreeSansBold9pt7b);
        s_display.setCursor(4, 28);
        s_display.print("SAFE MODE");

        s_display.setFont(nullptr);
        s_display.setCursor(4, 58);
        s_display.print(config_erased ? "Config erased" : "Config kept");
        s_display.setCursor(4, 76);
        s_display.print(bonds_cleared ? "BLE bonds cleared" : "BLE bonds kept");
        s_display.setCursor(4, 104);
        s_display.print("BLE DFU advertising");
        s_display.setCursor(4, 122);
        s_display.print("Name: AirTAK Recovery");
        s_display.setCursor(4, 144);
        s_display.print("Tap: USB UF2");
        s_display.setCursor(4, 160);
        s_display.print("Hold 5s: serial DFU");
        s_display.setCursor(4, 176);
        s_display.print("Hold 10s: erase cfg");
        s_display.setCursor(4, 188);
        s_display.print("Power cycle exits.");
    } while (s_display.nextPage());

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

ui::ScreenId Display::currentScreen()     { return s_current_screen; }
void Display::markDirty(uint16_t mask)     { s_dirty_mask |= mask; }
void Display::clearDirty()                 { s_dirty_mask = 0; }
void Display::forceRefresh()               { s_dirty_mask = DISPLAY_DIRTY_ALL; s_last_refresh_ms = 0; }

} // namespace airtak
