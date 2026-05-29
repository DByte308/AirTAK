// AirTAK — Airsoft Team Awareness Kit
// Display Constants — Dimensions, Timing, Colors, and Dirty-State Flags
#pragma once
#include <cstdint>

namespace airtak {

// ── Display Dirty-State Bit Flags (§9.6) ────────────────────────────────────

/// Dirty flag for GPS data update
constexpr uint16_t DISPLAY_DIRTY_GPS     = (1 << 0);

/// Dirty flag for radio status update
constexpr uint16_t DISPLAY_DIRTY_RADIO   = (1 << 1);

/// Dirty flag for battery level update
constexpr uint16_t DISPLAY_DIRTY_BATTERY = (1 << 2);

/// Dirty flag for BLE connection status update
constexpr uint16_t DISPLAY_DIRTY_BLE     = (1 << 3);

/// Dirty flag for game state update
constexpr uint16_t DISPLAY_DIRTY_GAME    = (1 << 4);

/// Dirty flag for incoming message notification
constexpr uint16_t DISPLAY_DIRTY_MESSAGE = (1 << 5);

/// Dirty flag for player list update
constexpr uint16_t DISPLAY_DIRTY_PLAYERS = (1 << 6);

/// Combined dirty mask for a full display refresh
constexpr uint16_t DISPLAY_DIRTY_ALL     = 0xFFFF;

// ── Display Refresh Timing ──────────────────────────────────────────────────

/// Minimum interval between full display refreshes (60 seconds)
constexpr uint32_t DISPLAY_MIN_REFRESH_MS      = 60000;

/// Interval for checking whether a dirty refresh is needed (5 seconds)
constexpr uint32_t DISPLAY_REFRESH_CHECK_MS    = 5000;

/// Poll interval when the display is in sleep/low-power mode
constexpr uint32_t DISPLAY_SLEEP_POLL_MS       = 250;

/// Display task main loop delay
constexpr uint32_t DISPLAY_LOOP_DELAY_MS       = 50;

// ── Default Power ───────────────────────────────────────────────────────────

/// Default display auto-off timeout in seconds (0 = never off)
constexpr uint32_t DISPLAY_OFF_SECS_DEFAULT    = 0;

/// Default display brightness as a percentage (100 = full brightness)
constexpr uint32_t DISPLAY_BRIGHTNESS_PCT_DEF  = 100;

// ── Per-Board Display Dimensions ────────────────────────────────────────────

#if defined(AIRTAK_BOARD_T_ECHO) || defined(AIRTAK_BOARD_T_ECHO_PLUS)
    // SSD1681 E-Ink — 200×200 pixels, 1-bit monochrome
    /// Display width in pixels
    constexpr uint16_t DISP_WIDTH           = 200;
    /// Display height in pixels
    constexpr uint16_t DISP_HEIGHT          = 200;
    /// Height of the status header bar in pixels
    constexpr uint8_t  DISP_HEADER_HEIGHT    = 16;
    /// Height of the footer bar in pixels
    constexpr uint8_t  DISP_FOOTER_HEIGHT    = 14;
    /// Y coordinate where main content rows begin
    constexpr uint8_t  DISP_ROW_CONTENT_START = 18;
    /// Y coordinate where main content rows end
    constexpr uint8_t  DISP_ROW_CONTENT_END   = 184;
    /// Y coordinate where footer rendering begins
    constexpr uint8_t  DISP_ROW_FOOTER_START  = 186;
    /// Line height for E-Ink text rendering
    constexpr int16_t  DISP_LINE_HEIGHT       = 16;
#elif defined(AIRTAK_BOARD_HELTEC_T114)
    // ST7789 TFT — 240×135 pixels, 16-bit color
    /// Display width in pixels
    constexpr uint16_t DISP_WIDTH           = 240;
    /// Display height in pixels
    constexpr uint16_t DISP_HEIGHT          = 135;
    /// Height of the status header bar in pixels
    constexpr uint16_t DISP_HEADER_HEIGHT    = 14;
    /// Height of the footer bar in pixels
    constexpr uint16_t DISP_FOOTER_HEIGHT    = 12;
    /// Y coordinate where main content begins
    constexpr uint16_t DISP_CONTENT_Y       = 16;
    /// Y coordinate where footer rendering begins
    constexpr uint16_t DISP_FOOTER_Y       = 123;
    /// Line height for TFT text rendering
    constexpr int16_t  DISP_LINE_HEIGHT       = 12;
#elif defined(AIRTAK_BOARD_T_DECK)
    // ST7789 TFT — 320×240 pixels, 16-bit color
    /// Display width in pixels
    constexpr uint16_t DISP_WIDTH           = 320;
    /// Display height in pixels
    constexpr uint16_t DISP_HEIGHT          = 240;
    /// Height of the status header bar in pixels
    constexpr uint16_t DISP_HEADER_HEIGHT    = 24;
    /// Height of the footer bar in pixels
    constexpr uint16_t DISP_FOOTER_HEIGHT    = 22;
    /// Y coordinate where main content begins
    constexpr uint16_t DISP_CONTENT_Y       = 26;
    /// Y coordinate where footer rendering begins
    constexpr uint16_t DISP_FOOTER_Y       = 218;
    /// Line height for TFT text rendering
    constexpr int16_t  DISP_LINE_HEIGHT       = 18;
#elif defined(AIRTAK_BOARD_HELTEC_V3) || defined(AIRTAK_BOARD_T3S3)
    // SSD1306 OLED — 128×64 pixels, monochrome
    /// Display width in pixels
    constexpr uint16_t DISP_WIDTH           = 128;
    /// Display height in pixels
    constexpr uint16_t DISP_HEIGHT          = 64;
    /// Height of the status header bar in pixels
    constexpr uint16_t DISP_HEADER_HEIGHT    = 11;
    /// Height of the footer bar in pixels
    constexpr uint16_t DISP_FOOTER_HEIGHT    = 10;
    /// Y coordinate where main content begins
    constexpr uint16_t DISP_CONTENT_Y       = 13;
    /// Y coordinate where footer rendering begins
    constexpr uint16_t DISP_FOOTER_Y       = 54;
    /// Line height for OLED text rendering
    constexpr int16_t  DISP_LINE_HEIGHT       = 9;
#else
    // Native test — no physical display
    /// Display width in pixels (native test fallback)
    constexpr uint16_t DISP_WIDTH           = 200;
    /// Display height in pixels (native test fallback)
    constexpr uint16_t DISP_HEIGHT          = 200;
    /// Height of the status header bar in pixels (native test fallback)
    constexpr uint16_t DISP_HEADER_HEIGHT    = 16;
    /// Height of the footer bar in pixels (native test fallback)
    constexpr uint16_t DISP_FOOTER_HEIGHT    = 14;
    /// Y coordinate where main content begins (native test fallback)
    constexpr uint16_t DISP_CONTENT_Y       = 18;
    /// Y coordinate where footer begins (native test fallback)
    constexpr uint16_t DISP_FOOTER_Y       = 186;
    /// Line height for text rendering (native test fallback)
    constexpr int16_t  DISP_LINE_HEIGHT       = 16;
#endif

// ── Screen Count ─────────────────────────────────────────────────────────────

/// Total number of selectable screens: MAIN, MESSAGES, PLAYERS, RADIO
constexpr uint8_t  SCREEN_COUNT             = 4;

// ── Backlight ───────────────────────────────────────────────────────────────

/// Number of backlight brightness steps (T-Deck PWM resolution)
constexpr uint8_t  BL_STEPS                  = 16;

// ── Per-Board Color Palettes ────────────────────────────────────────────────

#if defined(AIRTAK_BOARD_HELTEC_T114)
    // ST7789 TFT 16-bit colors (240×135)
    /// Background color
    constexpr uint16_t COL_BG        = 0x0000;
    /// Header bar background color
    constexpr uint16_t COL_HDR_BG    = 0x1082;
    /// Primary text color
    constexpr uint16_t COL_TEXT      = 0xFFFF;
    /// Accent highlight color
    constexpr uint16_t COL_ACCENT    = 0x07FF;
    /// OK / good status color (green)
    constexpr uint16_t COL_OK        = 0x07E0;
    /// Warning status color (yellow)
    constexpr uint16_t COL_WARN      = 0xFFE0;
    /// Error status color (red)
    constexpr uint16_t COL_ERR       = 0xF800;
    /// Dimmed / secondary text color
    constexpr uint16_t COL_DIM       = 0x7BEF;
#elif defined(AIRTAK_BOARD_T_DECK)
    /// Background color
    constexpr uint16_t COL_BG        = 0x0000;
    /// Header bar background color
    constexpr uint16_t COL_HDR_BG    = 0x1082;
    /// Primary text color
    constexpr uint16_t COL_TEXT      = 0xFFFF;
    /// Accent highlight color
    constexpr uint16_t COL_ACCENT    = 0x07FF;
    /// OK / good status color (green)
    constexpr uint16_t COL_OK        = 0x07E0;
    /// Warning status color (yellow)
    constexpr uint16_t COL_WARN      = 0xFFE0;
    /// Error status color (red)
    constexpr uint16_t COL_ERR       = 0xF800;
    /// Dimmed / secondary text color
    constexpr uint16_t COL_DIM       = 0x7BEF;
#endif

} // namespace airtak
