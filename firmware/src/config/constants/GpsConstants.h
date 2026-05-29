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

} // namespace airtak
