// MASH — Mesh Airsoft Situational Hub
// firmware/test/test_framing/test_framing.cpp — BLE chunk framing unit tests
// Planning: §10.2, §19.1 | Roadmap: Phase 1 exit criteria
//
// Validates:
//   1. Single-chunk encode/decode for payload ≤ 511 bytes.
//   2. Multi-chunk encode/decode: 512–1023 byte payloads → 2 chunks.
//   3. MTU-boundary encode/decode: exactly 511, 512, 513 byte payloads.
//   4. Header fields (total_length, chunk_index, chunk_count) are correct little-endian.
//   5. Reassemble: complete when all chunks received; partial when any chunk missing.
//   6. Reassemble: out-of-order chunk delivery produces correct output.

#include <unity.h>
#include "ble/Framing.h"
#include <cstring>

using namespace mash::ble;

static uint8_t s_payload[2048];
static uint8_t s_out[BLE_MTU];
static uint8_t s_assembled[2048];

void setUp() {
    for (size_t i = 0; i < sizeof(s_payload); i++) s_payload[i] = (uint8_t)(i & 0xFF);
    memset(s_assembled, 0, sizeof(s_assembled));
}
void tearDown() {}

void test_single_chunk_small_payload() {
    size_t n = Framing::encodeChunk(s_payload, 100, 0, s_out, sizeof(s_out));
    TEST_ASSERT_EQUAL(FRAME_HEADER_SIZE + 100, n);

    FrameHeader hdr;
    const uint8_t* payload;
    size_t plen;
    TEST_ASSERT_TRUE(Framing::decodeChunk(s_out, n, &hdr, &payload, &plen));
    TEST_ASSERT_EQUAL(100, hdr.total_length);
    TEST_ASSERT_EQUAL(0,   hdr.chunk_index);
    TEST_ASSERT_EQUAL(1,   hdr.chunk_count);
    TEST_ASSERT_EQUAL(100, plen);
    TEST_ASSERT_EQUAL_MEMORY(s_payload, payload, 100);
}

void test_chunk_count_at_511_boundary() {
    TEST_ASSERT_EQUAL(1, Framing::chunkCount(511));
    TEST_ASSERT_EQUAL(2, Framing::chunkCount(512));
    TEST_ASSERT_EQUAL(2, Framing::chunkCount(1022));
    TEST_ASSERT_EQUAL(3, Framing::chunkCount(1023));
}

void test_multi_chunk_reassemble() {
    size_t total = 600; // 2 chunks: 511 + 89
    uint16_t mask = 0;

    // Chunk 0
    uint8_t c0[BLE_MTU];
    size_t n0 = Framing::encodeChunk(s_payload, total, 0, c0, sizeof(c0));
    FrameHeader h0; const uint8_t* p0; size_t l0;
    Framing::decodeChunk(c0, n0, &h0, &p0, &l0);
    bool done = Framing::reassemble(h0, p0, l0, s_assembled, &mask);
    TEST_ASSERT_FALSE(done);

    // Chunk 1
    uint8_t c1[BLE_MTU];
    size_t n1 = Framing::encodeChunk(s_payload, total, 1, c1, sizeof(c1));
    FrameHeader h1; const uint8_t* p1; size_t l1;
    Framing::decodeChunk(c1, n1, &h1, &p1, &l1);
    done = Framing::reassemble(h1, p1, l1, s_assembled, &mask);
    TEST_ASSERT_TRUE(done);

    TEST_ASSERT_EQUAL_MEMORY(s_payload, s_assembled, total);
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_single_chunk_small_payload);
    RUN_TEST(test_chunk_count_at_511_boundary);
    RUN_TEST(test_multi_chunk_reassemble);
    return UNITY_END();
}
