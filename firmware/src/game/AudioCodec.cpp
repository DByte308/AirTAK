// MASH — Mesh Airsoft Situational Hub
// firmware/src/game/AudioCodec.cpp — Codec2 encode/decode wrapper implementation (Phase 7)
// Planning: §12.7, §8.11 | Roadmap: 7.1

#ifdef MASH_PHASE7_AUDIO

#include "game/AudioCodec.h"
#include "mesh/Router.h"
#include "mesh/Portnums.h"
#include <codec2.h>
#include <cstring>

namespace mash {

static struct CODEC2* s_enc = nullptr;
static struct CODEC2* s_dec = nullptr;
static uint8_t s_fec_prev[CODEC2_BYTES_PER_FRAME * CODEC2_FRAMES_PER_PACKET];
static bool    s_fec_prev_valid = false;

void AudioCodec::init() {
    s_enc = codec2_create(CODEC2_MODE_1300);
    s_dec = codec2_create(CODEC2_MODE_1300);
    memset(s_fec_prev, 0, sizeof(s_fec_prev));
    s_fec_prev_valid = false;
}

void AudioCodec::encode(const int16_t* samples, uint8_t* out_bits) {
    if (!s_enc) return;
    codec2_encode(s_enc, out_bits, const_cast<int16_t*>(samples));
}

void AudioCodec::decode(const uint8_t* bits, int16_t* out_samples) {
    if (!s_dec) return;
    codec2_decode(s_dec, out_samples, const_cast<uint8_t*>(bits));
}

void AudioCodec::beginPttSession() {
    // SESSION BOUNDARY: fec_prev_audio_data MUST be empty on first packet (§8.11 / amendment H).
    memset(s_fec_prev, 0, sizeof(s_fec_prev));
    s_fec_prev_valid = false;
}

void AudioCodec::encodePttPacket(const int16_t* samples_8frames,
                                  uint32_t session_id, QueueHandle_t tx_queue) {
    uint8_t encoded[CODEC2_BYTES_PER_FRAME * CODEC2_FRAMES_PER_PACKET];
    for (uint8_t f = 0; f < CODEC2_FRAMES_PER_PACKET; f++) {
        encode(samples_8frames + f * CODEC2_SAMPLES_PER_FRAME, encoded + f * CODEC2_BYTES_PER_FRAME);
    }

    // TODO Phase 7.1: Serialize AudioPacket proto:
    //   audio_data = encoded (32 bytes)
    //   fec_prev_audio_data = s_fec_prev_valid ? s_fec_prev : empty
    //   session_id = session_id
    // Then call Router::sendPacket(Portnum::MASH_AUDIO, BROADCAST_ADDR, pb_data, len, false, tx_queue)
    // hop_limit will be set to 1 by defaultHopLimit(MASH_AUDIO).

    // Store current packet as FEC for next packet
    memcpy(s_fec_prev, encoded, sizeof(encoded));
    s_fec_prev_valid = true;

    (void)session_id; (void)tx_queue;
}

void AudioCodec::decodeAudioPacket(const uint8_t* pb_data, size_t pb_len) {
    // TODO Phase 7.1: Deserialize AudioPacket proto. Use FEC if primary data is lost.
    // Maintain 3-packet jitter buffer. Decode 8 frames and write to audio output.
    (void)pb_data; (void)pb_len;
}

void AudioCodec::deinit() {
    if (s_enc) { codec2_destroy(s_enc); s_enc = nullptr; }
    if (s_dec) { codec2_destroy(s_dec); s_dec = nullptr; }
}

} // namespace mash

#endif // MASH_PHASE7_AUDIO
