// AirTAK — Airsoft Team Awareness Kit
// firmware/src/platform/boards/heltec_v3/hal/Display.cpp — SSD1306 OLED display driver
// Planning: §9.6 | Roadmap: 1.4
//
// Implements the board-agnostic Display.h interface using Adafruit SSD1306 on
// the Heltec WiFi LoRa 32 V3's 0.96" 128×64 monochrome OLED (I2C).
//
// The SSD1306 is connected via I2C0 (SDA=GPIO17, SCL=GPIO18, RST=GPIO21).
// Vext (GPIO 36, active LOW) must be enabled before the OLED is usable.
// ADC_CTRL (GPIO 37, active LOW) enables the battery voltage divider.

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
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

namespace airtak {

using namespace ui;

// ── OLED framebuffer ──────────────────────────────────────────────────────────
static Adafruit_SSD1306 s_display(AIRTAK_OLED_WIDTH, AIRTAK_OLED_HEIGHT, &Wire,
                                  AIRTAK_PIN_OLED_RST);

static ScreenId  s_current_screen  = ScreenId::MAIN;
static uint16_t  s_dirty_mask      = DISPLAY_DIRTY_ALL;
static uint32_t  s_last_refresh_ms = 0;

// ── Drawing helpers ───────────────────────────────────────────────────────────

static void drawHeader(const char* title) {
    s_display.setTextColor(SSD1306_WHITE);
    s_display.fillRect(0, 0, DISP_WIDTH, DISP_HEADER_HEIGHT, SSD1306_BLACK);
    s_display.drawRect(0, 0, DISP_WIDTH, DISP_HEADER_HEIGHT, SSD1306_WHITE);
    s_display.setTextSize(1);
    s_display.setCursor(3, 2);
    s_display.print(title);

    // Screen dots
    for (uint8_t i = 0; i < SCREEN_COUNT; i++) {
        int16_t x = (int16_t)(DISP_WIDTH - 6 - (SCREEN_COUNT - 1 - i) * 8);
        int16_t y = DISP_HEADER_HEIGHT / 2;
        if (i == static_cast<uint8_t>(s_current_screen)) {
            s_display.fillCircle(x, y, 2, SSD1306_WHITE);
        } else {
            s_display.drawCircle(x, y, 2, SSD1306_WHITE);
        }
    }
}

static void drawFooter() {
    s_display.fillRect(0, DISP_FOOTER_Y, DISP_WIDTH, DISP_FOOTER_HEIGHT, SSD1306_BLACK);
    s_display.drawRect(0, DISP_FOOTER_Y, DISP_WIDTH, DISP_FOOTER_HEIGHT, SSD1306_WHITE);
    s_display.setTextSize(1);

    uint8_t pct = (uint8_t)(Battery::level() * 100.0f);
    s_display.setTextColor(SSD1306_WHITE);
    char bat[10];
    snprintf(bat, sizeof(bat), "%d%%%s", pct, Battery::isCharging() ? "+" : "");
    s_display.setCursor(2, DISP_FOOTER_Y + 2);
    s_display.print(bat);

    s_display.setCursor(DISP_WIDTH - 22, DISP_FOOTER_Y + 2);
    s_display.print(ble::BLEServer::isConnected() ? "BLE" : "   ");
}

// ── Screen renderers ─────────────────────────────────────────────────────────

static void renderMainScreen() {
#ifdef AIRTAK_FLASH_TEST
    drawHeader("FLASH TEST OK");
#else
    drawHeader(FIRMWARE_DISPLAY_TITLE);
#endif

    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    int16_t y = DISP_CONTENT_Y + 1;

    {
        const char* longn  = config::ConfigManager::deviceLongName();
        const char* shortn = config::ConfigManager::deviceShortName();
        char namebuf[32];
        snprintf(namebuf, sizeof(namebuf), "%.12s (%.4s)", longn, shortn);
        s_display.setCursor(2, y); s_display.print(namebuf);
        y += DISP_LINE_HEIGHT;
    }

    {
        char csbuf[20];
        snprintf(csbuf, sizeof(csbuf), "CS:%.10s", config::ConfigManager::gameCallsign());
        s_display.setCursor(2, y); s_display.print(csbuf);
        y += DISP_LINE_HEIGHT;
    }

    {
        const GpsFix& fix = GPS::currentFix();
        char buf[48];
        if (GPS::hasFix()) {
            snprintf(buf, sizeof(buf), "GPS %.4f %.4f", fix.latitude, fix.longitude);
            s_display.setCursor(2, y); s_display.print(buf); y += DISP_LINE_HEIGHT;
            snprintf(buf, sizeof(buf), " %.0fm SAT:%u", fix.altitude_m, fix.satellites);
        } else {
            snprintf(buf, sizeof(buf), "GPS: NO FIX");
        }
        s_display.setCursor(2, y); s_display.print(buf);
        y += DISP_LINE_HEIGHT;
    }

    {
        const ChannelSlot* primary = ChannelManager::primarySlot();
        if (primary) {
            char buf[30];
            snprintf(buf, sizeof(buf), "CH:%-6s RSSI:%.0f", primary->name, Radio::lastRssi());
            s_display.setCursor(2, y); s_display.print(buf);
        }
        y += DISP_LINE_HEIGHT;
    }

    {
        uint32_t n = MeshNode::knownNodeCount();
        char mbuf[20];
        snprintf(mbuf, sizeof(mbuf), "MESH:%lu node%s",
                 static_cast<unsigned long>(n), n == 1 ? "" : "s");
        s_display.setCursor(2, y); s_display.print(mbuf);
    }

    drawFooter();
}

static void renderMessagesScreen() {
    drawHeader("MESSAGES");
    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    s_display.setCursor(2, DISP_CONTENT_Y + 6);
    s_display.print("(no messages)");
    drawFooter();
}

static void renderPlayersScreen() {
    drawHeader("PLAYERS");
    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    s_display.setCursor(2, DISP_CONTENT_Y + 6);
    s_display.print("(no players)");
    drawFooter();
}

static void renderRadioScreen() {
    drawHeader("RADIO");

    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    int16_t y = DISP_CONTENT_Y + 1;

    const ChannelSlot* primary = ChannelManager::primarySlot();
    if (primary) {
        char buf[30];
        snprintf(buf, sizeof(buf), "Chan: %s", primary->name);
        s_display.setCursor(2, y); s_display.print(buf); y += DISP_LINE_HEIGHT;
        snprintf(buf, sizeof(buf), "PHY: %u", primary->phy_profile_index);
        s_display.setCursor(2, y); s_display.print(buf); y += DISP_LINE_HEIGHT;
    }

    char rbuf[30];
    snprintf(rbuf, sizeof(rbuf), "RSSI: %.0f dBm", Radio::lastRssi());
    s_display.setCursor(2, y); s_display.print(rbuf); y += DISP_LINE_HEIGHT;
    snprintf(rbuf, sizeof(rbuf), "SNR:  %.1f dB", Radio::lastSnr());
    s_display.setCursor(2, y); s_display.print(rbuf); y += DISP_LINE_HEIGHT;
    snprintf(rbuf, sizeof(rbuf), "RX:%-3lu TX:%lu",
             static_cast<unsigned long>(Radio::packetsRx()),
             static_cast<unsigned long>(Radio::packetsTx()));
    s_display.setCursor(2, y); s_display.print(rbuf);

    drawFooter();
}

// ── Public API ────────────────────────────────────────────────────────────────

void Display::init() {
    // Enable peripheral power rail (Vext, active LOW).
    pinMode(AIRTAK_PIN_POWER_EN, OUTPUT);
    digitalWrite(AIRTAK_PIN_POWER_EN, LOW);
    delay(PERIPH_POWER_SETTLE_MS); // allow OLED and LoRa boost to stabilise

    // Enable battery ADC voltage divider (ADC_CTRL, active LOW).
    pinMode(AIRTAK_PIN_ADC_CTRL, OUTPUT);
    digitalWrite(AIRTAK_PIN_ADC_CTRL, LOW);

    Wire.begin(AIRTAK_PIN_OLED_SDA, AIRTAK_PIN_OLED_SCL);

    if (!s_display.begin(SSD1306_SWITCHCAPVCC, AIRTAK_OLED_I2C_ADDRESS)) {
        // Display init failed; firmware continues without display.
        s_last_refresh_ms = 0;
        s_dirty_mask      = 0;
        return;
    }

    s_display.clearDisplay();
    s_display.setTextWrap(false);
    s_display.cp437(true);

    s_last_refresh_ms = 0;
    s_dirty_mask      = DISPLAY_DIRTY_ALL;
}

void Display::refresh(EventGroupHandle_t system_event_group) {
    (void)system_event_group;
    if (s_dirty_mask == 0) return;

    s_display.clearDisplay();

    switch (s_current_screen) {
        case ScreenId::MAIN:     renderMainScreen();     break;
        case ScreenId::MESSAGES: renderMessagesScreen(); break;
        case ScreenId::PLAYERS:  renderPlayersScreen();  break;
        case ScreenId::RADIO:    renderRadioScreen();    break;
    }

    s_display.display();
    s_last_refresh_ms = millis();
    s_dirty_mask      = 0;
}

void Display::showPasscode(uint32_t passkey) {
    s_display.clearDisplay();

    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    s_display.setCursor(4, 4);
    s_display.print("AirTAK Pairing");
    s_display.drawLine(0, 12, DISP_WIDTH, 12, SSD1306_WHITE);

    char buf[8];
    snprintf(buf, sizeof(buf), "%06lu", (unsigned long)passkey);
    s_display.setTextSize(3);
    s_display.setCursor(20, 22);
    s_display.print(buf);

    s_display.setTextSize(1);
    s_display.setCursor(4, 50);
    s_display.print("Confirm on your phone");

    s_display.display();
    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

void Display::showShutdownWarning() {
    s_display.clearDisplay();
    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);

    s_display.setCursor(10, 16);
    s_display.print("Release button");
    s_display.setCursor(14, 32);
    s_display.print("to power off");

    s_display.display();
    s_dirty_mask = DISPLAY_DIRTY_ALL;
}

void Display::showRecoveryMode(bool config_erased, bool bonds_cleared) {
    s_display.clearDisplay();

    s_display.setTextSize(1);
    s_display.setTextColor(SSD1306_WHITE);
    s_display.setCursor(4, 2);
    s_display.print("SAFE MODE");
    s_display.drawLine(0, 11, DISP_WIDTH, 11, SSD1306_WHITE);

    int16_t y = 16;
    const int16_t LH = DISP_LINE_HEIGHT;
    s_display.setCursor(4, y); s_display.print(config_erased ? "Config: ERASED" : "Config: kept"); y += LH;
    s_display.setCursor(4, y); s_display.print(bonds_cleared ? "BLE bonds: CLEARED" : "BLE bonds: kept"); y += LH + 2;
    s_display.setCursor(4, y); s_display.print("Name: AirTAK Recovery"); y += LH;
    s_display.setCursor(4, y); s_display.print("Hold 10s: erase cfg"); y += LH;
    s_display.setCursor(4, y); s_display.print("Power cycle to exit.");

    s_display.display();
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
void Display::setBrightness(uint8_t)      {}   // OLED: contrast fixed
void Display::sleep()                     {}   // OLED: auto-off handled by task
void Display::wake()                      {}

} // namespace airtak
