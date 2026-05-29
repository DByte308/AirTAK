// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/CryptoConstants.h — Centralized cryptographic constants
//
// Constants extracted from firmware/src/mesh/Crypto.h.
// All values match the Meshtastic CryptoEngine specification (§15.1).
//
// Categories:
//   AES Key Sizes      — AES-128/256 key, nonce, and max buffer sizes
//   CCM Configuration  — AES-CCM L-value, nonce size, and authentication tag size
//   PKI Overhead       — Curve25519 ECDH key sizes and per-payload overhead

#pragma once
#include <cstdint>
#include <cstddef>

namespace airtak {

// ── AES Key Sizes (§15.1) ────────────────────────────────────────────────────
constexpr size_t PSK_MAX_BYTES      = 32;   // Maximum PSK size (zero-padded to key size)
constexpr size_t AES128_KEY_BYTES   = 16;   // AES-128 key length in bytes
constexpr size_t AES256_KEY_BYTES   = 32;   // AES-256 key length in bytes
constexpr size_t AES_MAX_KEY_BYTES  = 32;   // Maximum AES key buffer size
constexpr size_t AES_NONCE_BYTES    = 16;   // AES-CTR nonce size (128-bit)

// ── CCM Configuration — matches Meshtastic CryptoEngine (§15.1) ──────────────
// CCM L value: tag length = 2 → 13-byte nonce (15 − L), 2-byte authentication tag
constexpr uint8_t AES_CCM_L              = 2;
// CCM nonce = 15 − L = 13 bytes
constexpr size_t  AES_CCM_NONCE_BYTES    = 13;
// CCM authentication tag = L = 2 bytes
constexpr size_t  AES_CCM_TAG_BYTES      = 2;

// ── PKI (Curve25519 ECDH + AES-CCM) overhead ────────────────────────────────
// MAC (8 bytes) + extraNonce (4 bytes) = 12 bytes overhead per PKI-encrypted payload
constexpr size_t PKI_OVERHEAD_BYTES = 12;
// Curve25519 key sizes
constexpr size_t CURVE25519_PUBKEY_BYTES  = 32;
constexpr size_t CURVE25519_PRIVKEY_BYTES = 32;
constexpr size_t CURVE25519_SHARED_BYTES  = 32;

// ── Nonce Field Layout (§15.1, Meshtastic-compatible) ──────────────────────
// CTR nonce (128-bit): packet_id(LE u32) || 0x00000000 || from_node(LE u32) || 0x00000000
// CCM nonce (13-byte):  packet_id(LE)     || extraNonce(LE)   || from_node(LE)     || 0x00
constexpr size_t  NONCE_PACKET_ID_OFFSET    = 0;     // Offset of packet_id in nonce
constexpr size_t  NONCE_PACKET_ID_BYTES     = 4;     // packet_id field width (uint32_t)
constexpr size_t  NONCE_EXTRANONCE_OFFSET   = 4;     // Offset of extraNonce in CCM nonce
constexpr size_t  NONCE_EXTRANONCE_BYTES    = 4;     // extraNonce field width (uint32_t)
constexpr size_t  NONCE_FROM_NODE_OFFSET    = 8;    // Offset of from_node in nonce
constexpr size_t  NONCE_FROM_NODE_BYTES     = 4;     // from_node field width (uint32_t)

} // namespace airtak
