// AirTAK — versioned, portable persisted board-identity record.
#pragma once

#include <cstddef>
#include <cstdint>
#include "platform/BoardDescriptor.h"

namespace airtak {

// Byte layout: "ATKB", version=1, family=1 (ESP32-S3), id length, reserved=0,
// 16-byte zero-padded ASCII board id, CRC32-IEEE of bytes [0,24), little endian.
// Explicit byte encoding avoids compiler struct padding/endianness dependencies.
constexpr size_t BOARD_IDENTITY_RECORD_SIZE = 28;
constexpr size_t BOARD_IDENTITY_ID_CAPACITY = 16;

uint32_t boardIdentityChecksum(const uint8_t* data, size_t size);
bool encodeBoardIdentity(const char* boardId, uint8_t* out, size_t capacity);
// Invalid/unknown/wrong-family/future-version records always return safe unknown.
const BoardDescriptor& decodeBoardIdentity(const uint8_t* data, size_t size);

} // namespace airtak
