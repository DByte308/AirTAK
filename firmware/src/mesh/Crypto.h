// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Crypto.h — AES-CTR channel encryption (AES-128 / AES-256)
// Key derivation (Meshtastic-compatible, matches Channels::getKey()):
//   - PSK == 0 bytes: no encryption
//   - PSK 1–16 bytes (incl. 1-byte index expanded by ChannelManager):
//     zero-padded to 16 bytes (AES-128)
//   - PSK 17–32 bytes: zero-padded to 32 bytes (AES-256)
//
// CTR nonce: packet_id (32 bits LE) || from_node_id (32 bits LE) || 0x00... (padded to 128 bits)
//
// CRITICAL: Channel 0 key derivation and nonce construction MUST be byte-for-byte
// identical to the Meshtastic implementation at the pinned commit in
// proto/meshtastic/VERSION. Validate against Meshtastic test vectors (§19.1).

#pragma once

#include <cstdint>
#include <cstddef>

namespace airtak {

constexpr size_t PSK_MAX_BYTES      = 32;
constexpr size_t AES128_KEY_BYTES   = 16;
constexpr size_t AES256_KEY_BYTES   = 32;
constexpr size_t AES_MAX_KEY_BYTES  = 32;
constexpr size_t AES_NONCE_BYTES    = 16;

class Crypto {
public:
    // Derive an AES key from a raw PSK (Meshtastic-compatible).
    // PSK ≤ 16 bytes → AES-128 key (16 bytes, zero-padded).
    // PSK > 16 bytes → AES-256 key (32 bytes, zero-padded).
    // key_out must point to AES_MAX_KEY_BYTES bytes.
    // key_len_out receives the actual key length (16 or 32).
    static void deriveKey(const uint8_t* psk, size_t psk_len,
                          uint8_t key_out[AES_MAX_KEY_BYTES], uint8_t& key_len_out);

    // Build the AES-CTR nonce from packet_id and from_node_id (§15.1).
    // nonce_out must point to a buffer of at least AES_NONCE_BYTES bytes.
    static void buildNonce(uint32_t packet_id, uint32_t from_node_id,
                           uint8_t nonce_out[AES_NONCE_BYTES]);

    // Encrypt or decrypt a buffer in-place using AES-CTR.
    // AES-CTR is its own inverse. Supports AES-128 (key_len=16) and AES-256 (key_len=32).
    // key must be key_len bytes. nonce must be AES_NONCE_BYTES bytes.
    static void crypt(const uint8_t* key, uint8_t key_len,
                      const uint8_t nonce[AES_NONCE_BYTES],
                      uint8_t* data, size_t length);

    // Convenience: derive key and nonce, then crypt in-place.
    static void encrypt(const uint8_t* psk, size_t psk_len,
                        uint32_t packet_id, uint32_t from_node_id,
                        uint8_t* data, size_t length);
    static void decrypt(const uint8_t* psk, size_t psk_len,
                        uint32_t packet_id, uint32_t from_node_id,
                        uint8_t* data, size_t length);

   // Compute channel hash (§8.6).
    // XOR of channel name bytes ^ XOR of zero-padded PSK bytes, matching
    // Meshtastic's Channels::generateHash() for all slots.
    // channel_name participates only for the primary slot;
    // pass nullptr for secondary slots.
   static uint8_t channelHash(const uint8_t* psk, size_t psk_len,
                              uint8_t slot_index,
                              const char* channel_name = nullptr);
};

} // namespace airtak
