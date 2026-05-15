// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Crypto.cpp — AES-128-CTR channel encryption implementation
// Planning: §15.1, §8.3, §8.6 | Roadmap: 2.1
//
// Uses mbedTLS AES-CTR (provided by the nRF52 SoftDevice SDK / Adafruit BSP).
// SHA-256 for key derivation and channel hash computation.
//
// VALIDATION REQUIREMENT: Output of encrypt/decrypt for channel 0 MUST match
// the Meshtastic reference implementation at the pinned commit. See §19.1 crypto tests.

#include "mesh/Crypto.h"
#include <cstring>

// The nonce construction relies on little-endian byte order (memcpy of uint32_t).
// nRF52840 is Cortex-M4F LE; this assertion guards against accidental big-endian
// toolchain selection that would break Meshtastic channel-0 interop.
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__,
              "AirTAK Crypto nonce construction requires little-endian host");

// mbedTLS headers (available via Adafruit nRF52 BSP)
#include <mbedtls/aes.h>
#include <mbedtls/sha256.h>

namespace airtak {

// ── Crypto::deriveKey() ───────────────────────────────────────────────────────
void Crypto::deriveKey(const uint8_t* psk, size_t psk_len, uint8_t key_out[AES_KEY_BYTES]) {
    // SHA256(psk_bytes)[0:16] — first 16 bytes of SHA-256 of the raw PSK.
    if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;

    uint8_t hash[32];
    mbedtls_sha256(psk, psk_len, hash, 0 /* is224=false */);
    memcpy(key_out, hash, AES_KEY_BYTES);
}

// ── Crypto::buildNonce() ──────────────────────────────────────────────────────
void Crypto::buildNonce(uint32_t packet_id, uint32_t from_node_id, uint8_t nonce_out[AES_NONCE_BYTES]) {
    // Nonce = packet_id (32 bits LE) || from_node_id (32 bits LE) || 0x00...
    memset(nonce_out, 0, AES_NONCE_BYTES);
    memcpy(nonce_out + 0, &packet_id,    4);
    memcpy(nonce_out + 4, &from_node_id, 4);
    // Bytes 8–15 remain zero (padding to 128 bits)
}

// ── Crypto::crypt() ──────────────────────────────────────────────────────────
void Crypto::crypt(const uint8_t key[AES_KEY_BYTES],
                   const uint8_t nonce[AES_NONCE_BYTES],
                   uint8_t*      data,
                   size_t        length) {
    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_enc(&ctx, key, AES_KEY_BYTES * 8);

    uint8_t nonce_counter[AES_NONCE_BYTES];
    uint8_t stream_block[AES_NONCE_BYTES];
    size_t  nc_off = 0;
    memcpy(nonce_counter, nonce, AES_NONCE_BYTES);
    memset(stream_block, 0, sizeof(stream_block));

    // AES-CTR encrypt/decrypt (same operation)
    mbedtls_aes_crypt_ctr(&ctx, length, &nc_off, nonce_counter, stream_block, data, data);

    mbedtls_aes_free(&ctx);
}

// ── Convenience encrypt/decrypt ───────────────────────────────────────────────
void Crypto::encrypt(const uint8_t* psk, size_t psk_len,
                     uint32_t packet_id, uint32_t from_node_id,
                     uint8_t* data, size_t length) {
    uint8_t key[AES_KEY_BYTES];
    uint8_t nonce[AES_NONCE_BYTES];
    deriveKey(psk, psk_len, key);
    buildNonce(packet_id, from_node_id, nonce);
    crypt(key, nonce, data, length);
}

void Crypto::decrypt(const uint8_t* psk, size_t psk_len,
                     uint32_t packet_id, uint32_t from_node_id,
                     uint8_t* data, size_t length) {
    // AES-CTR: decrypt == encrypt
    encrypt(psk, psk_len, packet_id, from_node_id, data, length);
}

// ── Crypto::channelHash() ─────────────────────────────────────────────────────
uint8_t Crypto::channelHash(const uint8_t* psk, size_t psk_len, uint8_t slot_index) {
    if (slot_index == 0) {
        // Channel 0: XOR fold of all 32 PSK bytes (Meshtastic-compatible algorithm, §8.6).
        uint8_t padded[PSK_MAX_BYTES] = {};
        if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;
        memcpy(padded, psk, psk_len);
        uint8_t hash = 0;
        for (size_t i = 0; i < PSK_MAX_BYTES; i++) hash ^= padded[i];
        return hash;
    } else {
        // Channels 1–7: SHA256(psk)[0] — first byte of SHA-256 of the raw PSK (§8.6).
        uint8_t digest[32];
        mbedtls_sha256(psk, psk_len, digest, 0);
        return digest[0];
    }
}

} // namespace airtak
