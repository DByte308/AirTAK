// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/AudioCodec.h — Codec2 encode/decode wrapper (Phase 7)
// Planning: §12.7, §8.11 | Roadmap: 7.1
//
// Wraps libcodec2 (1300 bps mode = 26 bits/20 ms frame).
// Audio packet: 8 frames per packet = 160 ms audio, ~92 bytes with FEC.
// FEC: fec_prev_audio_data piggybacks previous packet's payload.
// SESSION BOUNDARY: fec_prev_audio_data MUST be empty on the first packet of each PTT session.
// hop_limit = 1 ALWAYS for AIRTAK_AUDIO (single hop, local mesh only).
//
// Enabled only when AIRTAK_PHASE7_AUDIO is defined (§17 build flag).

#pragma once

#ifdef AIRTAK_PHASE7_AUDIO

#include <cstdint>
#include <cstddef>
#include <FreeRTOS.h>
#include <queue.h>

namespace airtak {

constexpr uint8_t  CODEC2_FRAMES_PER_PACKET = 8;
constexpr uint16_t CODEC2_BITS_PER_FRAME    = 26;
constexpr uint8_t  CODEC2_BYTES_PER_FRAME   = 4;  // ceil(26/8)
constexpr uint16_t CODEC2_SAMPLES_PER_FRAME = 160; // 20 ms at 8 kHz

class AudioCodec {
public:
    // Phase 7.1: Initialize Codec2 encoder and decoder at 1300 bps mode.
    static void init();

    // Phase 7.1: Encode one frame (CODEC2_SAMPLES_PER_FRAME samples at 8 kHz mono int16_t).
    // out_bits must hold at least CODEC2_BYTES_PER_FRAME bytes.
    static void encode(const int16_t* samples, uint8_t* out_bits);

    // Phase 7.1: Decode CODEC2_BYTES_PER_FRAME bytes into CODEC2_SAMPLES_PER_FRAME samples.
    static void decode(const uint8_t* bits, int16_t* out_samples);

    // Phase 7.1: PTT TX — start a new PTT session. Clears fec_prev_audio_data.
    static void beginPttSession();

    // Phase 7.1: PTT TX — encode 8 frames, build AudioPacket proto with FEC piggyback,
    // and push to the radio TX queue. session_id is set per §8.11 / amendment H.
    static void encodePttPacket(const int16_t* samples_8frames,
                                uint32_t session_id, QueueHandle_t tx_queue);

    // Phase 7.1: PTT RX — receive AudioPacket, decode 8 frames, write to AudioTrack output.
    // Maintains a 3-packet jitter buffer.
    static void decodeAudioPacket(const uint8_t* pb_data, size_t pb_len);

    static void deinit();
};

} // namespace airtak

#endif // AIRTAK_PHASE7_AUDIO
