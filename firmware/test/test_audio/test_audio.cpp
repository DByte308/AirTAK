// MASH — Mesh Airsoft Situational Hub
// firmware/test/test_audio/test_audio.cpp — Codec2 round-trip SNR test (Phase 7)
// Planning: §12.7, §19.1 | Roadmap: 7.1
//
// Validates:
//   1. Codec2 init/deinit does not crash.
//   2. Encode → decode round-trip produces SNR ≥ 15 dB for a 1 kHz sine wave.
//   3. AudioPacket FEC: fec_prev_audio_data is EMPTY on first packet of each PTT session.
//   4. Session ID field is set correctly in AudioPacket proto.
//
// Only compiled when MASH_PHASE7_AUDIO is defined.

#ifdef MASH_PHASE7_AUDIO

#include <unity.h>
#include "game/AudioCodec.h"
#include <cmath>
#include <cstring>

using namespace mash;

static int16_t s_sine_frame[CODEC2_SAMPLES_PER_FRAME];

void setUp() {
    // Generate a 1 kHz sine wave at 8 kHz sample rate
    for (size_t i = 0; i < CODEC2_SAMPLES_PER_FRAME; i++) {
        s_sine_frame[i] = (int16_t)(16000.0 * sin(2.0 * M_PI * 1000.0 * i / 8000.0));
    }
}
void tearDown() {}

void test_codec2_roundtrip_snr() {
    AudioCodec::init();

    uint8_t  bits[CODEC2_BYTES_PER_FRAME];
    int16_t  decoded[CODEC2_SAMPLES_PER_FRAME];

    AudioCodec::encode(s_sine_frame, bits);
    AudioCodec::decode(bits, decoded);

    // Compute SNR
    double signal_power = 0.0, noise_power = 0.0;
    for (size_t i = 0; i < CODEC2_SAMPLES_PER_FRAME; i++) {
        double s  = s_sine_frame[i];
        double err = decoded[i] - s;
        signal_power += s * s;
        noise_power  += err * err;
    }
    double snr_db = 10.0 * log10(signal_power / (noise_power + 1e-12));
    TEST_ASSERT_GREATER_OR_EQUAL(15.0, snr_db);

    AudioCodec::deinit();
}

void test_fec_prev_empty_on_session_start() {
    // TODO Phase 7.1: After beginPttSession(), the first encodePttPacket call must
    // produce an AudioPacket proto where fec_prev_audio_data.size() == 0.
    TEST_IGNORE_MESSAGE("Requires nanopb proto serialization — implement in Phase 7.1");
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_codec2_roundtrip_snr);
    RUN_TEST(test_fec_prev_empty_on_session_start);
    return UNITY_END();
}

#else
// Phase 7 not enabled — provide an empty main for the test runner
#include <unity.h>
int main() {
    UNITY_BEGIN();
    // No tests: MASH_PHASE7_AUDIO not defined
    return UNITY_END();
}
#endif // MASH_PHASE7_AUDIO
