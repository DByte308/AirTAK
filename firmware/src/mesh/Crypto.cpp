// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Crypto.cpp — AES-CTR channel encryption (AES-128 / AES-256)
// Planning: §15.1, §8.3, §8.6 | Roadmap: 2.1
//
// Uses mbedTLS AES-CTR. AES-128 for PSK ≤ 16 bytes, AES-256 for PSK > 16 bytes.
// Key derivation matches Meshtastic's Channels::getKey() — raw bytes, zero-padded,
// no SHA-256 hashing.
//
// VALIDATION REQUIREMENT: Output of encrypt/decrypt for channel 0 MUST match
// the Meshtastic reference implementation. See §19.1 crypto tests.

#include "mesh/Crypto.h"
#include "config/constants/Constants.h"
#include <cstring>
#include <Arduino.h>

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__,
              "AirTAK Crypto nonce construction requires little-endian host");

#define MBEDTLS_ALLOW_PRIVATE_ACCESS
#include <mbedtls/aes.h>
#include <mbedtls/sha256.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ccm.h>
#include "mesh/x25519.h"

#if defined(AIRTAK_PLATFORM_ESP32)
#include <esp_random.h>
#endif

namespace airtak {

// ── RNG wrapper for mbedTLS CTR_DRBG (PKI keygen) ──────────────────────────
#if defined(AIRTAK_PLATFORM_ESP32)
static int hwRngCallback(void*, unsigned char* out, size_t len) {
    while (len >= 4) {
        uint32_t r = esp_random();
        memcpy(out, &r, 4);
        out += 4; len -= 4;
    }
    if (len) {
        uint32_t r = esp_random();
        memcpy(out, &r, len);
    }
    return 0;
}
#define PKI_RNG_CALLBACK hwRngCallback
#define PKI_RNG_CTX       nullptr
#else
static mbedtls_ctr_drbg_context s_drbg;
static mbedtls_entropy_context  s_entropy;
static bool s_drbg_ready = false;
static void ensureDrbg() {
    if (s_drbg_ready) return;
    mbedtls_ctr_drbg_init(&s_drbg);
    mbedtls_entropy_init(&s_entropy);
    mbedtls_ctr_drbg_seed(&s_drbg, mbedtls_entropy_func, &s_entropy, nullptr, 0);
    s_drbg_ready = true;
}
#define PKI_RNG_CALLBACK mbedtls_ctr_drbg_random
#define PKI_RNG_CTX      (&s_drbg)
#endif

void Crypto::deriveKey(const uint8_t* psk, size_t psk_len,
                       uint8_t key_out[AES_MAX_KEY_BYTES], uint8_t& key_len_out) {
    if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;

    memset(key_out, 0, AES_MAX_KEY_BYTES);

    // Match Meshtastic's default CryptoEngine key-length logic:
    //   key.length == 16  → AES-128 (16-byte key)
    //   key.length == 0   → no encryption
    //   key.length 1–16   → AES-128 (zero-padded to 16 bytes)
    //   key.length 17–32  → AES-256 (zero-padded to 32 bytes)
    if (psk_len == 0) {
        key_len_out = 0;
    } else if (psk_len <= AES128_KEY_BYTES) {
        memcpy(key_out, psk, psk_len);
        key_len_out = AES128_KEY_BYTES;   // AES-128 (zero-padded via memset above)
    } else {
        memcpy(key_out, psk, psk_len > AES256_KEY_BYTES ? AES256_KEY_BYTES : psk_len);
        key_len_out = AES256_KEY_BYTES;
    }
}

void Crypto::buildNonce(uint32_t packet_id, uint32_t from_node_id,
                        uint8_t nonce_out[AES_NONCE_BYTES]) {
    // Meshtastic-compatible nonce: packet_id as uint64_t (upper 32 bits = 0)
    // followed by from_node_id as uint32_t (§15.1, matches Meshtastic CryptoEngine).
    //   nonce[0:4]  = packet_id  (LE uint32_t)
    //   nonce[4:8]  = 0x00000000 (upper 32 bits of uint64_t packetId)
    //   nonce[8:12] = from_node_id (LE uint32_t)
    //   nonce[12:16] = 0x00000000
    memset(nonce_out, 0, AES_NONCE_BYTES);
    memcpy(nonce_out + NONCE_PACKET_ID_OFFSET, &packet_id,     NONCE_PACKET_ID_BYTES);
    memcpy(nonce_out + NONCE_FROM_NODE_OFFSET, &from_node_id, NONCE_FROM_NODE_BYTES);
}

void Crypto::crypt(const uint8_t* key, uint8_t key_len,
                   const uint8_t nonce[AES_NONCE_BYTES],
                   uint8_t* data, size_t length) {
    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_enc(&ctx, key, key_len * 8);

    uint8_t nonce_counter[AES_NONCE_BYTES];
    uint8_t stream_block[AES_NONCE_BYTES];
    size_t  nc_off = 0;
    memcpy(nonce_counter, nonce, AES_NONCE_BYTES);
    memset(stream_block, 0, sizeof(stream_block));

    mbedtls_aes_crypt_ctr(&ctx, length, &nc_off, nonce_counter, stream_block, data, data);

    mbedtls_aes_free(&ctx);
}

void Crypto::encrypt(const uint8_t* psk, size_t psk_len,
                     uint32_t packet_id, uint32_t from_node_id,
                     uint8_t* data, size_t length) {
    uint8_t key[AES_MAX_KEY_BYTES];
    uint8_t key_len;
    uint8_t nonce[AES_NONCE_BYTES];
    deriveKey(psk, psk_len, key, key_len);
    buildNonce(packet_id, from_node_id, nonce);
    crypt(key, key_len, nonce, data, length);
}

void Crypto::decrypt(const uint8_t* psk, size_t psk_len,
                     uint32_t packet_id, uint32_t from_node_id,
                     uint8_t* data, size_t length) {
    encrypt(psk, psk_len, packet_id, from_node_id, data, length);
}

uint8_t Crypto::channelHash(const uint8_t* psk, size_t psk_len,
                              uint8_t slot_index, const char* channel_name) {
    // XOR hash for ALL channel slots, matching Meshtastic's
    // Channels::generateHash() (xorHash(name) ^ xorHash(psk)).
    // There is no SHA256 variant upstream.
    (void)slot_index;
    uint8_t padded[PSK_MAX_BYTES] = {};
    if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;
    memcpy(padded, psk, psk_len);
    uint8_t hash = 0;
    if (channel_name) {
        for (size_t i = 0; channel_name[i]; i++) hash ^= (uint8_t)channel_name[i];
    }
    for (size_t i = 0; i < PSK_MAX_BYTES; i++) hash ^= padded[i];
    return hash;
}

// ── PKI: Curve25519 keypair generation and encryption/decryption ──────────

bool Crypto::pkikeypair(uint8_t pubkey_out[CURVE25519_PUBKEY_BYTES], uint8_t privkey_out[CURVE25519_PRIVKEY_BYTES]) {
#if !defined(AIRTAK_PLATFORM_ESP32)
    ensureDrbg();
#endif
    uint8_t seed[CURVE25519_PRIVKEY_BYTES];
    int ret = PKI_RNG_CALLBACK(PKI_RNG_CTX, seed, CURVE25519_PRIVKEY_BYTES);
    if (ret != 0) return false;
    return x25519_keypair(pubkey_out, privkey_out, seed);
}

bool Crypto::pkiEncrypt(const uint8_t our_privkey[CURVE25519_PRIVKEY_BYTES],
                        const uint8_t remote_pubkey[CURVE25519_PUBKEY_BYTES],
                        uint32_t from_node, uint32_t packet_id,
                        size_t plaintext_len, const uint8_t* plaintext,
                        uint8_t* ciphertext_out) {
    uint8_t shared_secret[CURVE25519_SHARED_BYTES];
    if (!x25519_shared_secret(shared_secret, our_privkey, remote_pubkey))
        return false;

    uint8_t shared_key[AES_MAX_KEY_BYTES];
    mbedtls_sha256(shared_secret, CURVE25519_SHARED_BYTES, shared_key, 0);

    uint32_t extraNonce;
#if !defined(AIRTAK_PLATFORM_ESP32)
    ensureDrbg();
#endif
    if (PKI_RNG_CALLBACK(PKI_RNG_CTX, reinterpret_cast<uint8_t*>(&extraNonce), NONCE_EXTRANONCE_BYTES) != 0)
        return false;

    // Meshtastic CCM uses L=2 → 13-byte nonce.  The 13-byte field is
    //   nonce[0..3]  = packet_id (LE)
    //   nonce[4..7]  = extraNonce (LE)  — overwrites packet_id high bytes
    //   nonce[8..11] = from_node (LE)
    //   nonce[12]    = 0
    uint8_t nonce13[AES_CCM_NONCE_BYTES];
    memset(nonce13, 0, sizeof(nonce13));
    memcpy(nonce13 + NONCE_PACKET_ID_OFFSET, &packet_id, NONCE_PACKET_ID_BYTES);
    memcpy(nonce13 + NONCE_EXTRANONCE_OFFSET, &extraNonce, NONCE_EXTRANONCE_BYTES);
    memcpy(nonce13 + NONCE_FROM_NODE_OFFSET, &from_node, NONCE_FROM_NODE_BYTES);

    mbedtls_ccm_context ccm;
    mbedtls_ccm_init(&ccm);
    int ret = mbedtls_ccm_setkey(&ccm, MBEDTLS_CIPHER_ID_AES, shared_key, 256);
    if (ret != 0) { mbedtls_ccm_free(&ccm); return false; }

    if (plaintext_len > 0 && plaintext) {
        memcpy(ciphertext_out, plaintext, plaintext_len);
    }

    uint8_t mac[PKI_OVERHEAD_BYTES - 4];
    ret = mbedtls_ccm_encrypt_and_tag(&ccm, plaintext_len,
                                       nonce13, AES_CCM_NONCE_BYTES,
                                       nullptr, 0,
                                       ciphertext_out, ciphertext_out,
                                       mac, PKI_OVERHEAD_BYTES - 4);
    mbedtls_ccm_free(&ccm);
    if (ret != 0) return false;

    memcpy(ciphertext_out + plaintext_len, mac, PKI_OVERHEAD_BYTES - 4);
    memcpy(ciphertext_out + plaintext_len + (PKI_OVERHEAD_BYTES - 4), &extraNonce, 4);

    return true;
}

bool Crypto::pkiDecrypt(const uint8_t our_privkey[CURVE25519_PRIVKEY_BYTES],
                        const uint8_t remote_pubkey[CURVE25519_PUBKEY_BYTES],
                        uint32_t from_node, uint32_t packet_id,
                        size_t num_bytes, uint8_t* bytes) {
    if (num_bytes < PKI_OVERHEAD_BYTES) return false;

    const uint8_t* mac_ptr    = bytes + num_bytes - PKI_OVERHEAD_BYTES;
    uint32_t extraNonce;
    memcpy(&extraNonce, mac_ptr + (PKI_OVERHEAD_BYTES - 4), 4);
    size_t ct_len = num_bytes - PKI_OVERHEAD_BYTES;

    uint8_t shared_secret[CURVE25519_SHARED_BYTES];
    if (!x25519_shared_secret(shared_secret, our_privkey, remote_pubkey))
        return false;

    uint8_t shared_key[AES_MAX_KEY_BYTES];
    mbedtls_sha256(shared_secret, CURVE25519_SHARED_BYTES, shared_key, 0);

    // Meshtastic CCM L=2 → 13-byte nonce matching initNonce layout.
    uint8_t nonce13[AES_CCM_NONCE_BYTES];
    memset(nonce13, 0, sizeof(nonce13));
    memcpy(nonce13 + NONCE_PACKET_ID_OFFSET, &packet_id, NONCE_PACKET_ID_BYTES);
    memcpy(nonce13 + NONCE_EXTRANONCE_OFFSET, &extraNonce, NONCE_EXTRANONCE_BYTES);
    memcpy(nonce13 + NONCE_FROM_NODE_OFFSET, &from_node, NONCE_FROM_NODE_BYTES);

    mbedtls_ccm_context ccm;
    mbedtls_ccm_init(&ccm);
    int ret = mbedtls_ccm_setkey(&ccm, MBEDTLS_CIPHER_ID_AES, shared_key, 256);
    if (ret != 0) { mbedtls_ccm_free(&ccm); return false; }

    ret = mbedtls_ccm_auth_decrypt(&ccm, ct_len,
                                    nonce13, AES_CCM_NONCE_BYTES,
                                    nullptr, 0,
                                    bytes, bytes,
                                    mac_ptr, PKI_OVERHEAD_BYTES - 4);
    mbedtls_ccm_free(&ccm);
    return (ret == 0);
}

} // namespace airtak
