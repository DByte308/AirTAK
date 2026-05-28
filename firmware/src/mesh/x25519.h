// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/x25519.h — Standalone X25519 wrapper using mbedTLS ECP
// Planning: §15.1 PKI | Roadmap: PKI/DM
//
// Provides simple raw X25519 operations — keypair generation and ECDH —
// without TLS handshake framing.
//
// All arguments are 32-byte buffers.

#pragma once

#include <cstdint>

namespace airtak {

bool x25519_keypair(uint8_t pubkey_out[32], uint8_t privkey_out[32],
                    const uint8_t seed[32]);

bool x25519_shared_secret(uint8_t shared_out[32],
                          const uint8_t privkey[32],
                          const uint8_t pubkey[32]);

} // namespace airtak
