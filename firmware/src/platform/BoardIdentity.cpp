#include "platform/BoardIdentity.h"
#include <cstring>

namespace airtak {
namespace {
constexpr uint8_t kMagic[] = {'A', 'T', 'K', 'B'};
constexpr uint8_t kVersion = 1;
constexpr uint8_t kEsp32S3 = 1;
constexpr size_t kChecksumOffset = 24;
}

uint32_t boardIdentityChecksum(const uint8_t* data, size_t size) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ ((crc & 1u) ? 0xEDB88320u : 0u);
    }
    return crc ^ 0xFFFFFFFFu;
}

bool encodeBoardIdentity(const char* boardId, uint8_t* out, size_t capacity) {
    if (!boardId || !out || capacity < BOARD_IDENTITY_RECORD_SIZE) return false;
    const auto& profile = board(boardId);
    if (&profile == &unknownBoard() || profile.platform != BoardPlatform::Esp32S3) return false;
    const size_t length = std::strlen(profile.boardId);
    if (length == 0 || length > BOARD_IDENTITY_ID_CAPACITY) return false;
    std::memset(out, 0, BOARD_IDENTITY_RECORD_SIZE);
    std::memcpy(out, kMagic, sizeof(kMagic));
    out[4] = kVersion;
    out[5] = kEsp32S3;
    out[6] = static_cast<uint8_t>(length);
    std::memcpy(out + 8, profile.boardId, length);
    const uint32_t crc = boardIdentityChecksum(out, kChecksumOffset);
    for (unsigned i = 0; i < 4; ++i) out[kChecksumOffset + i] = (crc >> (8 * i)) & 0xFFu;
    return true;
}

const BoardDescriptor& decodeBoardIdentity(const uint8_t* data, size_t size) {
    if (!data || size != BOARD_IDENTITY_RECORD_SIZE) return unknownBoard();
    if (std::memcmp(data, kMagic, sizeof(kMagic)) != 0 || data[4] != kVersion ||
        data[5] != kEsp32S3 || data[7] != 0) return unknownBoard();
    const size_t length = data[6];
    if (length == 0 || length > BOARD_IDENTITY_ID_CAPACITY) return unknownBoard();
    uint32_t storedCrc = 0;
    for (unsigned i = 0; i < 4; ++i) storedCrc |= uint32_t(data[kChecksumOffset + i]) << (8 * i);
    if (storedCrc != boardIdentityChecksum(data, kChecksumOffset)) return unknownBoard();
    for (size_t i = length; i < BOARD_IDENTITY_ID_CAPACITY; ++i)
        if (data[8 + i] != 0) return unknownBoard();
    char id[BOARD_IDENTITY_ID_CAPACITY + 1] = {};
    // Reject embedded NULs: a record must identify exactly one complete id.
    for (size_t i = 0; i < length; ++i) {
        const uint8_t ch = data[8 + i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_'))
            return unknownBoard();
        id[i] = static_cast<char>(ch);
    }
    return board(id);
}
} // namespace airtak
