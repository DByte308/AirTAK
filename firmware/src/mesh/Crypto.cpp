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
#include <cstring>

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__,
              "AirTAK Crypto nonce construction requires little-endian host");

#include <mbedtls/aes.h>
#include <mbedtls/sha256.h>

namespace airtak {

void Crypto::deriveKey(const uint8_t* psk, size_t psk_len,
                       uint8_t key_out[AES_MAX_KEY_BYTES], uint8_t& key_len_out) {
    if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;

    memset(key_out, 0, AES_MAX_KEY_BYTES);

    // Match Meshtastic's default CryptoEngine key-length logic:
    //   key.length == 16  → AES-128 (16-byte key)
    //   key.length == 0   → no encryption
    //   otherwise         → AES-256 (32-byte key, zero-padded)
    if (psk_len == 0) {
        key_len_out = 0;
    } else if (psk_len == 16) {
        memcpy(key_out, psk, 16);
        key_len_out = 16;
    } else {
        memcpy(key_out, psk, psk_len > 32 ? 32 : psk_len);
        key_len_out = 32;
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
    memcpy(nonce_out + 0, &packet_id,    4);
    memcpy(nonce_out + 8, &from_node_id, 4);
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
    if (slot_index == 0) {
        uint8_t padded[PSK_MAX_BYTES] = {};
        if (psk_len > PSK_MAX_BYTES) psk_len = PSK_MAX_BYTES;
        memcpy(padded, psk, psk_len);
        uint8_t hash = 0;
        if (channel_name) {
            for (size_t i = 0; channel_name[i]; i++) hash ^= (uint8_t)channel_name[i];
        }
        for (size_t i = 0; i < PSK_MAX_BYTES; i++) hash ^= padded[i];
        return hash;
    } else {
        uint8_t digest[32];
        mbedtls_sha256(psk, psk_len, digest, 0);
        return digest[0];
    }
}

} // namespace airtak
