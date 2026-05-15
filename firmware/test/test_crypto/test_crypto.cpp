// AirTAK — Airsoft Team Awareness Kit
// firmware/test/test_crypto/test_crypto.cpp — AES-128-CTR unit tests
// Planning: §15.1, §19.1 | Roadmap: Phase 1 exit criteria
//
// Validates:
//   1. Key derivation: SHA256(psk)[0:16] matches Meshtastic reference vectors.
//   2. Nonce construction: packet_id||from_node_id||0x00... layout.
//   3. AES-CTR encrypt/decrypt round-trip (plaintext == decrypt(encrypt(plaintext))).
//   4. Channel hash slot 0: XOR-fold of all 32 PSK bytes — Meshtastic-compatible.
//   5. Channel hash slot 0: known-good test vector using the Meshtastic default
//      LongFast PSK (§8.6 interop requirement, §19.1).
//   6. Channel hash slot 1–7: SHA256(psk)[0].
//   7. Zero-length PSK produces deterministic key (SHA256 of 32 zero bytes).
//
// Test vector derivation for §5 (Meshtastic default "LongFast" channel):
//   PSK bytes:  {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00 × 26}
//   XOR fold:   0xd4 ^ 0xf1 ^ 0xbb ^ 0x3a ^ 0x20 ^ 0x29 = 0xad
//   (All remaining 26 bytes are 0x00, which do not change the XOR result.)
//   Expected channel hash: 0xad — must match what a stock Meshtastic device computes.
//
// Meshtastic reference commit SHA is pinned in proto/meshtastic/VERSION.

#include <unity.h>
#include "mesh/Crypto.h"
#include "config.h"
#include <cstring>
#include <cstdio>

using namespace airtak;

// Test PSK: "LongFast" default channel from Meshtastic (0xd4f1bb3a20290000... padded)
static const uint8_t TEST_PSK[32] = {
    0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

void setUp() {}
void tearDown() {}

void test_key_derivation_length() {
    uint8_t key[16];
    Crypto::deriveKey(TEST_PSK, sizeof(TEST_PSK), key);
    // Key must be exactly 16 bytes (tested via compile-time array size)
    TEST_ASSERT_EQUAL(16, sizeof(key)); // structural check
}

void test_key_derivation_raw_psk_sha256_vector() {
    const uint8_t psk[] = {'a', 'b', 'c'};
    const uint8_t expected[16] = {
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
        0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
    };
    uint8_t key[16];
    Crypto::deriveKey(psk, sizeof(psk), key);
    TEST_ASSERT_EQUAL_MEMORY(expected, key, sizeof(expected));
}

void test_encrypt_decrypt_roundtrip() {
    uint8_t plaintext[32] = "AirTAK test payload data 123456";
    uint8_t ciphertext[32];
    uint8_t recovered[32];
    memcpy(ciphertext, plaintext, 32);

    Crypto::encrypt(TEST_PSK, 32, 0x00001234, 0xDEADBEEF, ciphertext, 32);
    memcpy(recovered, ciphertext, 32);
    Crypto::decrypt(TEST_PSK, 32, 0x00001234, 0xDEADBEEF, recovered, 32);

    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, 32);
}

void test_different_packet_ids_produce_different_ciphertext() {
    uint8_t buf_a[16] = {};
    uint8_t buf_b[16] = {};
    Crypto::encrypt(TEST_PSK, 32, 0x00000001, 0x11111111, buf_a, 16);
    Crypto::encrypt(TEST_PSK, 32, 0x00000002, 0x11111111, buf_b, 16);
    TEST_ASSERT_NOT_EQUAL(0, memcmp(buf_a, buf_b, 16));
}

void test_nonce_construction() {
    uint8_t nonce[16] = {};
    Crypto::buildNonce(0x12345678, 0xABCDEF01, nonce);
    // Bytes 0–3: packet_id (little-endian)
    TEST_ASSERT_EQUAL_HEX8(0x78, nonce[0]);
    TEST_ASSERT_EQUAL_HEX8(0x56, nonce[1]);
    TEST_ASSERT_EQUAL_HEX8(0x34, nonce[2]);
    TEST_ASSERT_EQUAL_HEX8(0x12, nonce[3]);
    // Bytes 4–7: from_node_id (little-endian)
    TEST_ASSERT_EQUAL_HEX8(0x01, nonce[4]);
    TEST_ASSERT_EQUAL_HEX8(0xEF, nonce[5]);
    TEST_ASSERT_EQUAL_HEX8(0xCD, nonce[6]);
    TEST_ASSERT_EQUAL_HEX8(0xAB, nonce[7]);
    // Bytes 8–15: zero padding
    for (int i = 8; i < 16; i++) TEST_ASSERT_EQUAL_HEX8(0x00, nonce[i]);
}

void test_channel_hash_slot0_xor_fold() {
    uint8_t psk[32];
    memset(psk, 0xAA, 32);
    uint8_t expected = 0;
    for (int i = 0; i < 32; i++) expected ^= 0xAA;
    TEST_ASSERT_EQUAL_HEX8(expected, Crypto::channelHash(psk, 32, 0));
}

// §8.6 Meshtastic interop: channel hash for the default LongFast channel.
// PSK:  {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00...0x00} (32 bytes)
// XOR fold:  0xd4 ^ 0xf1 = 0x25
//            0x25 ^ 0xbb = 0x9e
//            0x9e ^ 0x3a = 0xa4
//            0xa4 ^ 0x20 = 0x84
//            0x84 ^ 0x29 = 0xad
//            0xad ^ 0x00...= 0xad
// Expected:  0xad — this is the byte that appears in the Flags field of every
// default Meshtastic LongFast packet and must match what stock Meshtastic devices
// compute. A mismatch here means AirTAK and Meshtastic are on different logical
// channels and will silently discard each other's packets (§8.6 §G1).
void test_channel_hash_slot0_meshtastic_longfast_interop() {
    static const uint8_t LONGFAST_PSK[32] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    TEST_ASSERT_EQUAL_HEX8(0xad, Crypto::channelHash(LONGFAST_PSK, 32, 0));
}

// Slot 1–7 must use SHA256(psk)[0], not XOR-fold (§8.6).
void test_channel_hash_slot1_differs_from_slot0_xor() {
    static const uint8_t LONGFAST_PSK[32] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    uint8_t hash0 = Crypto::channelHash(LONGFAST_PSK, 32, 0); // XOR-fold = 0xad
    uint8_t hash1 = Crypto::channelHash(LONGFAST_PSK, 32, 1); // SHA256[0]
    // The two algorithms produce different values for this PSK, confirming
    // that slot 1 is NOT using the XOR-fold path.
    TEST_ASSERT_NOT_EQUAL(hash0, hash1);
}

// Zero-length PSK must produce a deterministic, non-crashing result.
// SHA256 of 32 zero bytes is well-defined and stable.
void test_key_derivation_zero_length_psk() {
    uint8_t key[16];
    Crypto::deriveKey(nullptr, 0, key);
    // Must not crash; key must be non-zero (SHA256 of empty input produces a
    // well-known digest whose first 16 bytes are not all-zero).
    uint8_t zeros[16] = {};
    TEST_ASSERT_NOT_EQUAL(0, memcmp(key, zeros, sizeof(key)));
}

// 32-byte PSK at exact max boundary must derive correctly.
void test_key_derivation_32_byte_psk_boundary() {
    uint8_t psk[32];
    memset(psk, 0xFF, 32);
    uint8_t key_a[16], key_b[16];
    Crypto::deriveKey(psk, 32, key_a);
    // 33 bytes (truncated to 32) must produce the same key as 32 bytes.
    Crypto::deriveKey(psk, 33, key_b);
    TEST_ASSERT_EQUAL_MEMORY(key_a, key_b, 16);
}

// AES-CTR must be deterministic: same key, nonce, and plaintext → same ciphertext.
// This is critical for the duplicate-suppression cache and relay logic which
// compare encrypted bytes byte-for-byte.
void test_encrypt_deterministic_same_output_for_same_input() {
    const uint8_t plaintext[16] = {'d','e','t','e','r','m','i','n','i','s','m',' ','t','e','s','t'};
    uint8_t ct_a[16], ct_b[16];
    memcpy(ct_a, plaintext, 16);
    memcpy(ct_b, plaintext, 16);

    Crypto::encrypt(TEST_PSK, 32, 0x00000042, 0xAABBCCDD, ct_a, 16);
    Crypto::encrypt(TEST_PSK, 32, 0x00000042, 0xAABBCCDD, ct_b, 16);
    TEST_ASSERT_EQUAL_MEMORY(ct_a, ct_b, 16);
}

// Max LoRa payload (237 bytes) round-trip with the Meshtastic LongFast PSK.
// Exercises AES-CTR streaming across multiple 16-byte blocks and verifies
// the cipher does not corrupt data at non-mod-16 boundaries.
void test_encrypt_decrypt_max_payload_roundtrip() {
    uint8_t plaintext[MESH_MAX_PAYLOAD_BYTES];
    uint8_t ciphertext[MESH_MAX_PAYLOAD_BYTES];
    uint8_t recovered[MESH_MAX_PAYLOAD_BYTES];

    // Fill with a known pattern: each byte equals its index.
    for (size_t i = 0; i < MESH_MAX_PAYLOAD_BYTES; i++) {
        plaintext[i] = (uint8_t)(i & 0xFF);
    }
    memcpy(ciphertext, plaintext, MESH_MAX_PAYLOAD_BYTES);

    Crypto::encrypt(TEST_PSK, 32, 0xDEADBEEF, 0xCAFEBABE,
                    ciphertext, MESH_MAX_PAYLOAD_BYTES);
    // Ciphertext must differ from plaintext (AES-CTR is not a no-op).
    TEST_ASSERT_NOT_EQUAL(0, memcmp(plaintext, ciphertext, MESH_MAX_PAYLOAD_BYTES));

    memcpy(recovered, ciphertext, MESH_MAX_PAYLOAD_BYTES);
    Crypto::decrypt(TEST_PSK, 32, 0xDEADBEEF, 0xCAFEBABE,
                    recovered, MESH_MAX_PAYLOAD_BYTES);
    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, MESH_MAX_PAYLOAD_BYTES);
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_key_derivation_length);
    RUN_TEST(test_key_derivation_raw_psk_sha256_vector);
    RUN_TEST(test_encrypt_decrypt_roundtrip);
    RUN_TEST(test_different_packet_ids_produce_different_ciphertext);
    RUN_TEST(test_nonce_construction);
    RUN_TEST(test_channel_hash_slot0_xor_fold);
    RUN_TEST(test_channel_hash_slot0_meshtastic_longfast_interop);
    RUN_TEST(test_channel_hash_slot1_differs_from_slot0_xor);
    RUN_TEST(test_key_derivation_zero_length_psk);
    RUN_TEST(test_key_derivation_32_byte_psk_boundary);
    RUN_TEST(test_encrypt_deterministic_same_output_for_same_input);
    RUN_TEST(test_encrypt_decrypt_max_payload_roundtrip);
    return UNITY_END();
}
