// AirTAK — Airsoft Team Awareness Kit
// GPS Hardware Abstraction Constants

#pragma once
#include <cstdint>

namespace airtak {

// ── GPS Event Bits ──────────────────────────────────────────────────────────
constexpr uint32_t GPS_EVENT_FIX_ACQUIRED    = (1 << 8);
constexpr uint32_t GPS_EVENT_FIX_LOST        = (1 << 9);
constexpr uint32_t GPS_EVENT_NEW_POSITION    = (1 << 10);

// ── GPS Baud Rate Candidates ────────────────────────────────────────────────
// Tried in order until GPS sends valid NMEA sentences
constexpr uint32_t GPS_BAUD_CANDIDATE_1      = 38400;
constexpr uint32_t GPS_BAUD_CANDIDATE_2      = 9600;
constexpr uint32_t GPS_BAUD_CANDIDATE_3      = 115200;
constexpr uint8_t  GPS_BAUD_CANDIDATE_COUNT  = 3;

// ── Debug Sentence Buffer ───────────────────────────────────────────────────
constexpr size_t   GPS_DEBUG_SENTENCE_BUF_SIZE = 128;

// ── Earth Radius (WGS84, metres) ──────────────────────────────────────────
constexpr double   EARTH_RADIUS_M            = 6371000.0;

// ── Additional Baud Rate Candidates ─────────────────────────────────────────
constexpr uint32_t GPS_BAUD_CANDIDATE_4      = 57600;  // Additional fallback baud

// ── Baud Switching Timing ──────────────────────────────────────────────────
// After a baud switch, wait this many ms before probing for valid NMEA sentences
constexpr uint32_t GPS_BAUD_SWITCH_TIMEOUT_MS = 8000;   // 8 seconds
// Delay between closing and reopening GPS serial at new baud
constexpr uint32_t GPS_BAUD_SWITCH_DELAY_MS   = 10;     // 10 ms settle time

// ── GPS Thread Timing ────────────────────────────────────────────────────
constexpr uint32_t GPS_THREAD_INTERVAL_MS      = 50;     // GPS serial poll interval (nRF52 variant)

// ── GPS Fix Quality Thresholds ────────────────────────────────────────────
constexpr float    GPS_FIX_HDOP_MAX              = 5.0f;   // Maximum HDOP for valid fix
constexpr uint8_t  GPS_FIX_MIN_SATELLITES        = 4;      // Minimum satellites for 3D fix
constexpr float    GPS_HDOP_TO_ACCURACY_MULT     = 5.0f;   // HDOP × multiplier = accuracy (meters)
constexpr float    GPS_HDOP_DEFAULT_FALLBACK     = 1.0f;   // Default HDOP when accuracy unknown
constexpr float    GPS_DISPLACEMENT_FORCE_TX_M   = 9999.0f;// Large displacement to force first TX
constexpr uint32_t GPS_DEBUG_PRINT_INTERVAL_MS   = 10000;  // 10-second GPS debug print interval
constexpr uint8_t  GPS_MIN_CHARS_BEFORE_CONFIG   = 10;     // Min chars processed before sending PMTK config

} // namespace airtak
