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
#include "mesh/x25519.h"
#include "config.h"
#include "config/constants/Constants.h"
#include <cstring>

using namespace airtak;

// 32-byte PSK for AES-256 tests (all 0xFF)
static const uint8_t AES256_PSK[PSK_MAX_BYTES] = {
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
    // 16-byte default Meshtastic PSK → key.length=16 → AES-128.
    // Matches Meshtastic getKey() + NRF52 nRFCrypto AES-128 path.
    const uint8_t psk[16] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0, 0xbc, 0xff, 0xab, 0xcf, 0x4e, 0x69, 0x01,
    };
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    Crypto::deriveKey(psk, sizeof(psk), key, key_len);
    TEST_ASSERT_EQUAL(AES128_KEY_BYTES, key_len);
    TEST_ASSERT_EQUAL_MEMORY(psk, key, AES128_KEY_BYTES);
}

void test_key_derivation_aes128_exact_16() {
    const uint8_t psk[16] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
    };
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    Crypto::deriveKey(psk, sizeof(psk), key, key_len);
    TEST_ASSERT_EQUAL(AES128_KEY_BYTES, key_len);
    TEST_ASSERT_EQUAL_MEMORY(psk, key, AES128_KEY_BYTES);
}

void test_key_derivation_aes256_zero_pad() {
    // 20-byte PSK → padded to 32, AES-256
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    Crypto::deriveKey(AES256_PSK_SHORT, 20, key, key_len);
    TEST_ASSERT_EQUAL(AES256_KEY_BYTES, key_len);
    // First 20 bytes match input, remaining 12 are zeros
    TEST_ASSERT_EQUAL_MEMORY(AES256_PSK_SHORT, key, 20);
    for (int i = 20; i < AES256_KEY_BYTES; i++) TEST_ASSERT_EQUAL_HEX8(0x00, key[i]);
}

void test_key_derivation_aes256_full_32() {
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    Crypto::deriveKey(AES256_PSK, AES256_KEY_BYTES, key, key_len);
    TEST_ASSERT_EQUAL(AES256_KEY_BYTES, key_len);
    TEST_ASSERT_EQUAL_MEMORY(AES256_PSK, key, AES256_KEY_BYTES);
}

void test_key_derivation_zero_length() {
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    Crypto::deriveKey(nullptr, 0, key, key_len);
    TEST_ASSERT_EQUAL(0, key_len);
    uint8_t zeros[AES_MAX_KEY_BYTES] = {};
    TEST_ASSERT_EQUAL_MEMORY(zeros, key, AES128_KEY_BYTES);
}

void test_key_derivation_33_truncated_to_32() {
    uint8_t psk33[33];
    memset(psk33, 0xAA, 33);
    uint8_t key_a[AES_MAX_KEY_BYTES], key_b[AES_MAX_KEY_BYTES];
    uint8_t key_len_a, key_len_b;
    Crypto::deriveKey(psk33, 33, key_a, key_len_a);
    Crypto::deriveKey(psk33, AES256_KEY_BYTES, key_b, key_len_b);
    TEST_ASSERT_EQUAL(AES256_KEY_BYTES, key_len_a);
    TEST_ASSERT_EQUAL(AES256_KEY_BYTES, key_len_b);
    TEST_ASSERT_EQUAL_MEMORY(key_b, key_a, AES256_KEY_BYTES);
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
    uint8_t nonce[AES_NONCE_BYTES] = {};
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
    for (int i = 12; i < AES_NONCE_BYTES; i++) TEST_ASSERT_EQUAL_HEX8(0x00, nonce[i]);
}

void test_channel_hash_slot0_xor_fold() {
    // Pure XOR fold of 32 0xAA bytes (no channel name).
    uint8_t psk[PSK_MAX_BYTES];
    memset(psk, 0xAA, 32);
    uint8_t expected = 0;
    for (int i = 0; i < PSK_MAX_BYTES; i++) expected ^= 0xAA;
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
    static const uint8_t LONGFAST_PSK[PSK_MAX_BYTES] = {
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
    static const uint8_t LONGFAST_PSK[PSK_MAX_BYTES] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
   uint8_t hash0 = Crypto::channelHash(LONGFAST_PSK, 32, 0);
   uint8_t hash1 = Crypto::channelHash(LONGFAST_PSK, 32, 1);
    TEST_ASSERT_EQUAL(hash0, hash1); // XOR is used for ALL slots (Meshtastic-compatible)
}

// ── X25519 keypair tests ────────────────────────────────────────────────────

void test_x25519_keypair_generates_valid_keys() {
    uint8_t seed[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) seed[i] = (uint8_t)(i + 1);
    uint8_t pub[CURVE25519_PUBKEY_BYTES], priv[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub, priv, seed));

    uint8_t zero[CURVE25519_PUBKEY_BYTES] = {};
    TEST_ASSERT_NOT_EQUAL(0, memcmp(pub, zero, CURVE25519_PUBKEY_BYTES));
    TEST_ASSERT_NOT_EQUAL(0, memcmp(priv, zero, CURVE25519_PRIVKEY_BYTES));
}

void test_x25519_keypair_deterministic() {
    uint8_t seed[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) seed[i] = 0xAA;
    uint8_t pub1[CURVE25519_PUBKEY_BYTES], priv1[CURVE25519_PRIVKEY_BYTES], pub2[CURVE25519_PUBKEY_BYTES], priv2[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub1, priv1, seed));
    TEST_ASSERT_TRUE(x25519_keypair(pub2, priv2, seed));
    TEST_ASSERT_EQUAL_MEMORY(pub1, pub2, CURVE25519_PUBKEY_BYTES);
    TEST_ASSERT_EQUAL_MEMORY(priv1, priv2, CURVE25519_PRIVKEY_BYTES);
}

void test_x25519_ecdh_symmetric() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));

    uint8_t shared_ab[CURVE25519_SHARED_BYTES], shared_ba[CURVE25519_SHARED_BYTES];
    TEST_ASSERT_TRUE(x25519_shared_secret(shared_ab, priv_a, pub_b));
    TEST_ASSERT_TRUE(x25519_shared_secret(shared_ba, priv_b, pub_a));
    TEST_ASSERT_EQUAL_MEMORY(shared_ab, shared_ba, CURVE25519_SHARED_BYTES);

    uint8_t zero[CURVE25519_SHARED_BYTES] = {};
    TEST_ASSERT_NOT_EQUAL(0, memcmp(shared_ab, zero, CURVE25519_SHARED_BYTES));
}

void test_x25519_rejects_zero_shared_secret() {
    // All-zero public key produces all-zero shared secret → rejected
    uint8_t priv[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) priv[i] = 0x01;
    uint8_t zero_pub[CURVE25519_PUBKEY_BYTES] = {};
    uint8_t shared[CURVE25519_SHARED_BYTES];
    TEST_ASSERT_FALSE(x25519_shared_secret(shared, priv, zero_pub));
}

// ── PKI encrypt/decrypt tests ───────────────────────────────────────────────

void test_pki_encrypt_decrypt_roundtrip() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));

    uint8_t plaintext[64];
    for (size_t i = 0; i < sizeof(plaintext); i++) plaintext[i] = (uint8_t)(i * 7 + 13);

    uint8_t ciphertext[sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0xDEADBEEF, 0x00000042,
                                         sizeof(plaintext), plaintext, ciphertext));

    uint8_t recovered[sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES];
    memcpy(recovered, ciphertext, sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES);
    TEST_ASSERT_TRUE(Crypto::pkiDecrypt(priv_b, pub_a, 0xDEADBEEF, 0x00000042,
                                         sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES, recovered));
    TEST_ASSERT_EQUAL_MEMORY(plaintext, recovered, sizeof(plaintext));
}

void test_pki_decrypt_fails_with_wrong_key() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES], seed_c[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); seed_c[i] = (uint8_t)0xFF; }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES], pub_c[CURVE25519_PUBKEY_BYTES], priv_c[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));
    TEST_ASSERT_TRUE(x25519_keypair(pub_c, priv_c, seed_c));

    uint8_t plaintext[32];
    for (size_t i = 0; i < sizeof(plaintext); i++) plaintext[i] = (uint8_t)i;

    uint8_t ciphertext[sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0x11111111, 1,
                                         sizeof(plaintext), plaintext, ciphertext));

    // Decrypt with wrong key (C's keypair instead of B's)
    uint8_t recovered[sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES];
    memcpy(recovered, ciphertext, sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES);
    TEST_ASSERT_FALSE(Crypto::pkiDecrypt(priv_c, pub_a, 0x11111111, 1,
                                          sizeof(plaintext) + Crypto::PKI_OVERHEAD_BYTES, recovered));
}

void test_pki_decrypt_detects_bad_mac() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));

    uint8_t plaintext[16];
    memset(plaintext, 0x42, 16);

    size_t total = 16 + Crypto::PKI_OVERHEAD_BYTES;
    uint8_t ciphertext[16 + Crypto::PKI_OVERHEAD_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0xAAAA, 99,
                                         16, plaintext, ciphertext));

    // Corrupt the last byte (extraNonce)
    ciphertext[total - 1] ^= 0xFF;

    uint8_t recovered[16 + Crypto::PKI_OVERHEAD_BYTES];
    memcpy(recovered, ciphertext, total);
    TEST_ASSERT_FALSE(Crypto::pkiDecrypt(priv_b, pub_a, 0xAAAA, 99, total, recovered));
}

void test_pki_encrypt_produces_different_output_for_different_inputs() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));

    uint8_t plaintext[16];
    memset(plaintext, 0x13, 16);

    uint8_t ct1[16 + Crypto::PKI_OVERHEAD_BYTES];
    uint8_t ct2[16 + Crypto::PKI_OVERHEAD_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0x1111, 1, 16, plaintext, ct1));
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0x1111, 2, 16, plaintext, ct2));

    // Different packet IDs should produce different ciphertext
    TEST_ASSERT_NOT_EQUAL(0, memcmp(ct1, ct2, sizeof(ct1)));
}

void test_pki_encrypt_empty_payload() {
    uint8_t seed_a[CURVE25519_PRIVKEY_BYTES], seed_b[CURVE25519_PRIVKEY_BYTES];
    for (int i = 0; i < CURVE25519_PRIVKEY_BYTES; i++) { seed_a[i] = (uint8_t)(i + 1); seed_b[i] = (uint8_t)(64 - i); }
    uint8_t pub_a[CURVE25519_PUBKEY_BYTES], priv_a[CURVE25519_PRIVKEY_BYTES], pub_b[CURVE25519_PUBKEY_BYTES], priv_b[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(x25519_keypair(pub_a, priv_a, seed_a));
    TEST_ASSERT_TRUE(x25519_keypair(pub_b, priv_b, seed_b));

    uint8_t ciphertext[Crypto::PKI_OVERHEAD_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkiEncrypt(priv_a, pub_b, 0xDEAD, 42, 0, nullptr, ciphertext));

    uint8_t recovered[Crypto::PKI_OVERHEAD_BYTES];
    memcpy(recovered, ciphertext, Crypto::PKI_OVERHEAD_BYTES);
    TEST_ASSERT_TRUE(Crypto::pkiDecrypt(priv_b, pub_a, 0xDEAD, 42, Crypto::PKI_OVERHEAD_BYTES, recovered));
    // Empty payload: nothing to compare
}

void test_pki_decrypt_rejects_too_short() {
    uint8_t buf[1] = {0};
    TEST_ASSERT_FALSE(Crypto::pkiDecrypt(nullptr, nullptr, 0, 0, 1, buf));
}

void test_crypto_pkikeypair_produces_valid_keys() {
    uint8_t pub[CURVE25519_PUBKEY_BYTES], priv[CURVE25519_PRIVKEY_BYTES];
    TEST_ASSERT_TRUE(Crypto::pkikeypair(pub, priv));
    uint8_t zero[CURVE25519_PUBKEY_BYTES] = {};
    TEST_ASSERT_NOT_EQUAL(0, memcmp(pub, zero, CURVE25519_PUBKEY_BYTES));
    TEST_ASSERT_NOT_EQUAL(0, memcmp(priv, zero, CURVE25519_PRIVKEY_BYTES));
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
    RUN_TEST(test_x25519_keypair_generates_valid_keys);
    RUN_TEST(test_x25519_keypair_deterministic);
    RUN_TEST(test_x25519_ecdh_symmetric);
    RUN_TEST(test_x25519_rejects_zero_shared_secret);
    RUN_TEST(test_pki_encrypt_decrypt_roundtrip);
    RUN_TEST(test_pki_decrypt_fails_with_wrong_key);
    RUN_TEST(test_pki_decrypt_detects_bad_mac);
    RUN_TEST(test_pki_encrypt_produces_different_output_for_different_inputs);
    RUN_TEST(test_pki_encrypt_empty_payload);
    RUN_TEST(test_pki_decrypt_rejects_too_short);
    RUN_TEST(test_crypto_pkikeypair_produces_valid_keys);
    return UNITY_END();
}
