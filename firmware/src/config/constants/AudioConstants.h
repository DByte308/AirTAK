// AirTAK — Airsoft Team Awareness Kit
// Audio Codec Constants

#pragma once
#include <cstdint>

namespace airtak {

// ── Codec2 Parameters (1300 bps mode) ──────────────────────────────────────
constexpr uint8_t  CODEC2_FRAMES_PER_PACKET    = 8;     // Frames per audio packet
constexpr uint16_t CODEC2_BITS_PER_FRAME       = 26;    // Encoded bits per 20ms frame
constexpr uint8_t  CODEC2_BYTES_PER_FRAME      = 4;     // Encoded bytes per frame (ceil(26/8))
constexpr uint16_t CODEC2_SAMPLES_PER_FRAME    = 160;   // PCM samples per frame (8kHz × 20ms)

// ── Audio Config Defaults ──────────────────────────────────────────────────
constexpr uint32_t AUDIO_DEFAULT_CHANNEL_SLOT  = 2;     // Channel slot for audio
constexpr uint32_t AUDIO_DEFAULT_CODEC2_BPS    = 1300;  // Codec2 bitrate
constexpr uint32_t AUDIO_DEFAULT_FRAME_COUNT   = 8;     // Frames per TX burst

// ── Audio Frame Timing ─────────────────────────────────────────────────────
constexpr uint32_t AUDIO_FRAME_INTERVAL_MS     = 20;    // 20ms Codec2 frame

} // namespace airtak
