// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Crypto.h — AES-128-CTR channel encryption
// Planning: §15.1, §8.3 | Roadmap: 2.1
//
// Key derivation: SHA256(psk_bytes)[0:16]
// CTR nonce: packet_id (32 bits) || from_node_id (32 bits) || 0x00... (padded to 128 bits)
//
// CRITICAL: Channel 0 key derivation and nonce construction MUST be byte-for-byte
// identical to the Meshtastic implementation at the pinned commit in
// proto/meshtastic/VERSION. Validate against Meshtastic test vectors (§19.1).

#pragma once

#include <cstdint>
#include <cstddef>

namespace airtak {

// Maximum PSK length: 32 bytes (256-bit). Shorter PSKs are zero-padded before hashing.
constexpr size_t PSK_MAX_BYTES = 32;
// AES-128 key length derived from SHA256(psk)[0:16]
constexpr size_t AES_KEY_BYTES = 16;
// AES-CTR nonce length = 16 bytes (128-bit block size)
constexpr size_t AES_NONCE_BYTES = 16;

class Crypto {
public:
    // Derive a 128-bit AES key from a raw PSK.
    // key_out must point to a buffer of at least AES_KEY_BYTES bytes.
    static void deriveKey(const uint8_t* psk, size_t psk_len, uint8_t key_out[AES_KEY_BYTES]);

    // Build the AES-CTR nonce from packet_id and from_node_id (§15.1).
    // nonce_out must point to a buffer of at least AES_NONCE_BYTES bytes.
    static void buildNonce(uint32_t packet_id, uint32_t from_node_id, uint8_t nonce_out[AES_NONCE_BYTES]);

    // Encrypt or decrypt a buffer in-place using AES-128-CTR.
    // AES-CTR is its own inverse: encrypt and decrypt are the same operation.
    // key must be AES_KEY_BYTES bytes. nonce must be AES_NONCE_BYTES bytes.
    static void crypt(const uint8_t key[AES_KEY_BYTES],
                      const uint8_t nonce[AES_NONCE_BYTES],
                      uint8_t*      data,
                      size_t        length);

    // Convenience: derive key and nonce, then crypt in-place.
    static void encrypt(const uint8_t* psk, size_t psk_len,
                        uint32_t packet_id, uint32_t from_node_id,
                        uint8_t* data, size_t length);

    static void decrypt(const uint8_t* psk, size_t psk_len,
                        uint32_t packet_id, uint32_t from_node_id,
                        uint8_t* data, size_t length);

    // Compute channel hash for a PSK (§8.6).
    // Slot 0: XOR fold of all 32 PSK bytes (Meshtastic-compatible).
    // Slots 1–7: SHA256(psk)[0].
    static uint8_t channelHash(const uint8_t* psk, size_t psk_len, uint8_t slot_index);
};

} // namespace airtak
