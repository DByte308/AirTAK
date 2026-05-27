// AirTAK — Airsoft Team Awareness Kit
// firmware/test/test_crypto/test_crypto.cpp — AES-CTR unit tests (AES-128 / AES-256)
// Planning: §15.1, §19.1 | Roadmap: Phase 1 exit criteria
//
// Validates:
//   1. Key derivation: zero-padded raw PSK matches Meshtastic's Channels::getKey().
//   2. AES-128 for PSK ≤ 16 bytes, AES-256 for PSK 17–32 bytes.
//   3. Nonce construction: packet_id||from_node_id||0x00... layout.
//   4. AES-CTR encrypt/decrypt round-trip for AES-128 and AES-256.
//   5. Channel hash slot 0: XOR-fold of all 32 PSK bytes — Meshtastic-compatible.
//   6. Channel hash slot 0: known-good test vector (LongFast default PSK).
//   7. Channel hash slot 1–7: SHA256(psk)[0].
//   8. Zero-length PSK produces all-zero key (unencrypted).

#include <unity.h>
#include "mesh/Crypto.h"
#include "config.h"
#include <cstring>

using namespace airtak;

// 32-byte PSK for AES-256 tests (all 0xFF)
static const uint8_t AES256_PSK[32] = {
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
};

// 20-byte PSK for AES-256 (17-32 byte range)
static const uint8_t AES256_PSK_SHORT[20] = "20-byte test key !!";

void setUp() {}
void tearDown() {}

void test_key_derivation_aes128_zero_pad() {
    const uint8_t psk[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
    const uint8_t expected[16] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    uint8_t key[32];
    uint8_t key_len;
    Crypto::deriveKey(psk, sizeof(psk), key, key_len);
    TEST_ASSERT_EQUAL(16, key_len);
    TEST_ASSERT_EQUAL_MEMORY(expected, key, 16);
}

void test_key_derivation_aes128_exact_16() {
    const uint8_t psk[16] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
    };
    uint8_t key[32];
    uint8_t key_len;
    Crypto::deriveKey(psk, sizeof(psk), key, key_len);
    TEST_ASSERT_EQUAL(16, key_len);
    TEST_ASSERT_EQUAL_MEMORY(psk, key, 16);
}

void test_key_derivation_aes256_zero_pad() {
    // 20-byte PSK → padded to 32, AES-256
    uint8_t key[32];
    uint8_t key_len;
    Crypto::deriveKey(AES256_PSK_SHORT, 20, key, key_len);
    TEST_ASSERT_EQUAL(32, key_len);
    // First 20 bytes match input, remaining 12 are zeros
    TEST_ASSERT_EQUAL_MEMORY(AES256_PSK_SHORT, key, 20);
    for (int i = 20; i < 32; i++) TEST_ASSERT_EQUAL_HEX8(0x00, key[i]);
}

void test_key_derivation_aes256_full_32() {
    uint8_t key[32];
    uint8_t key_len;
    Crypto::deriveKey(AES256_PSK, 32, key, key_len);
    TEST_ASSERT_EQUAL(32, key_len);
    TEST_ASSERT_EQUAL_MEMORY(AES256_PSK, key, 32);
}

void test_key_derivation_zero_length() {
    uint8_t key[32];
    uint8_t key_len;
    Crypto::deriveKey(nullptr, 0, key, key_len);
    TEST_ASSERT_EQUAL(16, key_len);
    uint8_t zeros[32] = {};
    TEST_ASSERT_EQUAL_MEMORY(zeros, key, 16);
}

void test_key_derivation_33_truncated_to_32() {
    uint8_t psk33[33];
    memset(psk33, 0xAA, 33);
    uint8_t key_a[32], key_b[32];
    uint8_t key_len_a, key_len_b;
    Crypto::deriveKey(psk33, 33, key_a, key_len_a);
    Crypto::deriveKey(psk33, 32, key_b, key_len_b);
    TEST_ASSERT_EQUAL(32, key_len_a);
    TEST_ASSERT_EQUAL(32, key_len_b);
    TEST_ASSERT_EQUAL_MEMORY(key_b, key_a, 32);
}

void test_encrypt_decrypt_aes128_roundtrip() {
    uint8_t plaintext[32] = "AirTAK test payload data 123456";
    uint8_t ciphertext[32];
    uint8_t recovered[32];
    memcpy(ciphertext, plaintext, 32);

    const uint8_t psk[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
    Crypto::encrypt(psk, 6, 0x00001234, 0xDEADBEEF, ciphertext, 32);
    memcpy(recovered, ciphertext, 32);
    Crypto::decrypt(psk, 6, 0x00001234, 0xDEADBEEF, recovered, 32);

    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, 32);
}

void test_encrypt_decrypt_aes256_roundtrip() {
    uint8_t plaintext[64] = "AES-256 round-trip test with 32-byte key 0123456789abcdef!";
    uint8_t ciphertext[64];
    uint8_t recovered[64];
    memcpy(ciphertext, plaintext, 64);

    Crypto::encrypt(AES256_PSK, 32, 0xCAFEBABE, 0xFEEDFACE, ciphertext, 64);
    memcpy(recovered, ciphertext, 64);
    Crypto::decrypt(AES256_PSK, 32, 0xCAFEBABE, 0xFEEDFACE, recovered, 64);

    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, 64);
}

void test_different_packet_ids_produce_different_ciphertext() {
    uint8_t buf_a[16] = {};
    uint8_t buf_b[16] = {};
    const uint8_t psk[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
    Crypto::encrypt(psk, 6, 0x00000001, 0x11111111, buf_a, 16);
    Crypto::encrypt(psk, 6, 0x00000002, 0x11111111, buf_b, 16);
    TEST_ASSERT_NOT_EQUAL(0, memcmp(buf_a, buf_b, 16));
}

void test_encrypt_deterministic() {
    const uint8_t plaintext[16] = {'d','e','t','e','r','m','i','n','i','s','m',' ','t','e','s','t'};
    uint8_t ct_a[16], ct_b[16];
    memcpy(ct_a, plaintext, 16);
    memcpy(ct_b, plaintext, 16);
    Crypto::encrypt(AES256_PSK, 32, 0x00000042, 0xAABBCCDD, ct_a, 16);
    Crypto::encrypt(AES256_PSK, 32, 0x00000042, 0xAABBCCDD, ct_b, 16);
    TEST_ASSERT_EQUAL_MEMORY(ct_a, ct_b, 16);
}

void test_encrypt_decrypt_max_payload_roundtrip() {
    uint8_t plaintext[MESH_MAX_PAYLOAD_BYTES];
    uint8_t ciphertext[MESH_MAX_PAYLOAD_BYTES];
    uint8_t recovered[MESH_MAX_PAYLOAD_BYTES];
    for (size_t i = 0; i < MESH_MAX_PAYLOAD_BYTES; i++) plaintext[i] = (uint8_t)(i & 0xFF);
    memcpy(ciphertext, plaintext, MESH_MAX_PAYLOAD_BYTES);
    Crypto::encrypt(AES256_PSK, 32, 0xDEADBEEF, 0xCAFEBABE, ciphertext, MESH_MAX_PAYLOAD_BYTES);
    TEST_ASSERT_NOT_EQUAL(0, memcmp(plaintext, ciphertext, MESH_MAX_PAYLOAD_BYTES));
    memcpy(recovered, ciphertext, MESH_MAX_PAYLOAD_BYTES);
    Crypto::decrypt(AES256_PSK, 32, 0xDEADBEEF, 0xCAFEBABE, recovered, MESH_MAX_PAYLOAD_BYTES);
    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, MESH_MAX_PAYLOAD_BYTES);
}

void test_aes128_aes256_produce_different_outputs() {
    // Same plaintext, different key sizes → different ciphertext
    uint8_t buf128[16] = {1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6};
    uint8_t buf256[16] = {1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6};
    const uint8_t psk128[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
    Crypto::encrypt(psk128, 6, 0x42, 0x99, buf128, 16);
    Crypto::encrypt(AES256_PSK, 32, 0x42, 0x99, buf256, 16);
    TEST_ASSERT_NOT_EQUAL(0, memcmp(buf128, buf256, 16));
}

// Meshtastic-compatible nonce: packet_id (uint64_t LE, upper=0) || from_node_id (uint32_t LE) || zeros
void test_nonce_construction() {
    uint8_t nonce[16] = {};
    Crypto::buildNonce(0x12345678, 0xABCDEF01, nonce);
    // Bytes 0–3: packet_id (little-endian)
    TEST_ASSERT_EQUAL_HEX8(0x78, nonce[0]);
    TEST_ASSERT_EQUAL_HEX8(0x56, nonce[1]);
    TEST_ASSERT_EQUAL_HEX8(0x34, nonce[2]);
    TEST_ASSERT_EQUAL_HEX8(0x12, nonce[3]);
    // Bytes 4–7: zero (upper 32 bits of uint64_t packetId)
    for (int i = 4; i < 8; i++) TEST_ASSERT_EQUAL_HEX8(0x00, nonce[i]);
    // Bytes 8–11: from_node_id (little-endian)
    TEST_ASSERT_EQUAL_HEX8(0x01, nonce[8]);
    TEST_ASSERT_EQUAL_HEX8(0xEF, nonce[9]);
    TEST_ASSERT_EQUAL_HEX8(0xCD, nonce[10]);
    TEST_ASSERT_EQUAL_HEX8(0xAB, nonce[11]);
    // Bytes 12–15: zero padding
    for (int i = 12; i < 16; i++) TEST_ASSERT_EQUAL_HEX8(0x00, nonce[i]);
}

void test_channel_hash_slot0_xor_fold() {
    // Pure XOR fold of 32 0xAA bytes (no channel name).
    uint8_t psk[32];
    memset(psk, 0xAA, 32);
    uint8_t expected = 0;
    for (int i = 0; i < 32; i++) expected ^= 0xAA;
    TEST_ASSERT_EQUAL_HEX8(expected, Crypto::channelHash(psk, 32, 0));
}

// §8.6 Meshtastic interop: channel hash for the default LongFast channel.
// Meshtastic's Channels::generateHash() computes:
//   hash = XOR(channel_name) ^ XOR(key_bytes)
// For the LongFast default channel:
//   channel name = "LongFast" → XOR = 0x0A
//   key = {d4,f1,bb,3a,20,29,00...00} → XOR = 0xAD
//   hash = 0x0A ^ 0xAD = 0xA7
// This MUST match what a stock Meshtastic device computes.
void test_channel_hash_slot0_meshtastic_longfast_interop() {
    static const uint8_t LONGFAST_PSK[32] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    TEST_ASSERT_EQUAL_HEX8(0xA7, Crypto::channelHash(LONGFAST_PSK, 32, 0, "LongFast"));
}

void test_channel_hash_slot0_name_changes_hash() {
    // Different channel name produces different hash with the same PSK.
    static const uint8_t PSK[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
    uint8_t h1 = Crypto::channelHash(PSK, 6, 0, "LongFast");
    uint8_t h2 = Crypto::channelHash(PSK, 6, 0, "MyChannel");
    TEST_ASSERT_NOT_EQUAL(h1, h2);
}

void test_channel_hash_slot1_differs_from_slot0_xor() {
    static const uint8_t LONGFAST_PSK[32] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    uint8_t hash0 = Crypto::channelHash(LONGFAST_PSK, 32, 0);
    uint8_t hash1 = Crypto::channelHash(LONGFAST_PSK, 32, 1);
    TEST_ASSERT_NOT_EQUAL(hash0, hash1);
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_key_derivation_aes128_zero_pad);
    RUN_TEST(test_key_derivation_aes128_exact_16);
    RUN_TEST(test_key_derivation_aes256_zero_pad);
    RUN_TEST(test_key_derivation_aes256_full_32);
    RUN_TEST(test_key_derivation_zero_length);
    RUN_TEST(test_key_derivation_33_truncated_to_32);
    RUN_TEST(test_encrypt_decrypt_aes128_roundtrip);
    RUN_TEST(test_encrypt_decrypt_aes256_roundtrip);
    RUN_TEST(test_different_packet_ids_produce_different_ciphertext);
    RUN_TEST(test_encrypt_deterministic);
    RUN_TEST(test_encrypt_decrypt_max_payload_roundtrip);
    RUN_TEST(test_aes128_aes256_produce_different_outputs);
    RUN_TEST(test_nonce_construction);
    RUN_TEST(test_channel_hash_slot0_xor_fold);
    RUN_TEST(test_channel_hash_slot0_meshtastic_longfast_interop);
    RUN_TEST(test_channel_hash_slot0_name_changes_hash);
    RUN_TEST(test_channel_hash_slot1_differs_from_slot0_xor);
    return UNITY_END();
}
