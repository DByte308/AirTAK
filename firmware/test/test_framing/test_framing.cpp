// AirTAK — Airsoft Team Awareness Kit
// firmware/test/test_framing/test_framing.cpp — BLE chunk framing unit tests
// Planning: §10.2, §19.1 | Roadmap: Phase 1 exit criteria
//
// Validates:
//   1. Single-chunk encode/decode for payload ≤ FRAME_CHUNK_SIZE bytes.
//   2. Multi-chunk encode/decode across FRAME_CHUNK_SIZE boundaries.
//   3. MTU-boundary encode/decode at FRAME_CHUNK_SIZE ± 1 byte.
//   4. Header fields (total_length, chunk_index, chunk_count) are correct little-endian.
//   5. Reassemble: complete when all chunks received; partial when any chunk missing.
//   6. Reassemble: out-of-order chunk delivery produces correct output.

#include <unity.h>
#include "ble/Framing.h"
#include <cstring>

using namespace airtak::ble;

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

void test_chunk_count_at_mtu_boundary() {
    TEST_ASSERT_EQUAL(1, Framing::chunkCount(FRAME_CHUNK_SIZE));
    TEST_ASSERT_EQUAL(2, Framing::chunkCount(FRAME_CHUNK_SIZE + 1));
    TEST_ASSERT_EQUAL(2, Framing::chunkCount(FRAME_CHUNK_SIZE * 2));
    TEST_ASSERT_EQUAL(3, Framing::chunkCount(FRAME_CHUNK_SIZE * 2 + 1));
}

void test_multi_chunk_reassemble() {
    size_t total = FRAME_CHUNK_SIZE + 92;
    uint64_t mask = 0;

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

void test_out_of_order_reassemble() {
    size_t total = FRAME_CHUNK_SIZE * 2 + 17;
    uint64_t mask = 0;

    uint8_t c2[BLE_MTU];
    size_t n2 = Framing::encodeChunk(s_payload, total, 2, c2, sizeof(c2));
    FrameHeader h2; const uint8_t* p2; size_t l2;
    TEST_ASSERT_TRUE(Framing::decodeChunk(c2, n2, &h2, &p2, &l2));
    TEST_ASSERT_FALSE(Framing::reassemble(h2, p2, l2, s_assembled, &mask));

    uint8_t c0[BLE_MTU];
    size_t n0 = Framing::encodeChunk(s_payload, total, 0, c0, sizeof(c0));
    FrameHeader h0; const uint8_t* p0; size_t l0;
    TEST_ASSERT_TRUE(Framing::decodeChunk(c0, n0, &h0, &p0, &l0));
    TEST_ASSERT_FALSE(Framing::reassemble(h0, p0, l0, s_assembled, &mask));

    uint8_t c1[BLE_MTU];
    size_t n1 = Framing::encodeChunk(s_payload, total, 1, c1, sizeof(c1));
    FrameHeader h1; const uint8_t* p1; size_t l1;
    TEST_ASSERT_TRUE(Framing::decodeChunk(c1, n1, &h1, &p1, &l1));
    TEST_ASSERT_TRUE(Framing::reassemble(h1, p1, l1, s_assembled, &mask));

    TEST_ASSERT_EQUAL_MEMORY(s_payload, s_assembled, total);
}

void test_decode_rejects_invalid_chunk_index() {
    uint8_t frame[FRAME_HEADER_SIZE] = {};
    uint16_t total = 20;
    uint16_t chunk_index = 1;
    uint16_t chunk_count = 1;
    memcpy(frame + 0, &total, 2);
    memcpy(frame + 2, &chunk_index, 2);
    memcpy(frame + 4, &chunk_count, 2);

    FrameHeader hdr;
    const uint8_t* payload;
    size_t plen;
    TEST_ASSERT_FALSE(Framing::decodeChunk(frame, sizeof(frame), &hdr, &payload, &plen));
}

void test_framed_full_lora_packet_fits_single_att_value() {
    uint8_t frame[BLE_FRAME_MAX_LEN];
    size_t n = Framing::encodeChunk(s_payload, 255, 0, frame, sizeof(frame));

    TEST_ASSERT_EQUAL(FRAME_HEADER_SIZE + 255, n);
    TEST_ASSERT_LESS_OR_EQUAL(BLE_FRAME_MAX_LEN, n);
}

void test_maximum_chunk_uses_declared_frame_capacity() {
    uint8_t frame[BLE_FRAME_MAX_LEN];
    size_t n = Framing::encodeChunk(s_payload, FRAME_CHUNK_SIZE, 0, frame, sizeof(frame));

    TEST_ASSERT_EQUAL(BLE_FRAME_MAX_LEN, n);
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_single_chunk_small_payload);
    RUN_TEST(test_chunk_count_at_mtu_boundary);
    RUN_TEST(test_multi_chunk_reassemble);
    RUN_TEST(test_out_of_order_reassemble);
    RUN_TEST(test_decode_rejects_invalid_chunk_index);
    RUN_TEST(test_framed_full_lora_packet_fits_single_att_value);
    RUN_TEST(test_maximum_chunk_uses_declared_frame_capacity);
    return UNITY_END();
}
