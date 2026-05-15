// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ble/Framing.h — BLE GATT characteristic chunked framing
// Planning: §10.2 | Roadmap: 1.5
//
// Frame header (6 bytes, little-endian):
//   [0:1] total_length    — total payload bytes across all chunks
//   [2:3] chunk_index     — 0-based index of this chunk
//   [4:5] chunk_count     — total number of chunks
//
// At MTU 517, usable payload per chunk = 508 bytes (517 − 3 ATT overhead − 6 frame header).
// The BLE ATT notification PDU carries a 3-byte header (opcode + handle), leaving
// 514 bytes of ATT payload. Subtracting the 6-byte AirTAK frame header gives 508 bytes
// of chunk data per notification. This matches §10.2: "MTU − 6 − 3 (ATT overhead)".
// Used for both CONFIG_RW and ADMIN characteristics (§10.3, §10.5).

#pragma once

#include <cstdint>
#include <cstddef>

namespace airtak {
namespace ble {

constexpr size_t   FRAME_HEADER_SIZE = 6;
constexpr uint16_t BLE_MTU           = 517;
constexpr size_t   BLE_ATT_OVERHEAD  = 3;   // ATT notification PDU header (opcode + handle)
constexpr size_t   FRAME_CHUNK_SIZE  = BLE_MTU - BLE_ATT_OVERHEAD - FRAME_HEADER_SIZE; // 508 bytes
constexpr size_t   BLE_FRAME_MAX_LEN = FRAME_HEADER_SIZE + FRAME_CHUNK_SIZE; // 514-byte ATT value

struct FrameHeader {
    uint16_t total_length;
    uint16_t chunk_index;
    uint16_t chunk_count;
};

class Framing {
public:
    // Encode: write chunk_index-th chunk of payload (total len bytes) into out_buf.
    // out_buf must be at least FRAME_HEADER_SIZE + FRAME_CHUNK_SIZE bytes.
    // Returns total bytes written to out_buf.
    static size_t encodeChunk(const uint8_t* payload, size_t len,
                              uint16_t chunk_index,
                              uint8_t* out_buf, size_t out_buf_len);

    // Returns the total number of chunks required to send len bytes.
    static uint16_t chunkCount(size_t len);

    // Decode: parse a received BLE notification into its header and payload slice.
    // Returns false if the buffer is too small to contain the header.
    static bool decodeChunk(const uint8_t* in_buf, size_t in_len,
                            FrameHeader* hdr_out,
                            const uint8_t** payload_out, size_t* payload_len_out);

    // Reassembler: copy a decoded chunk into assembly_buf.
    // Returns true when all chunks have been received (assembly complete).
    // assembly_buf must be allocated to hdr.total_length bytes before first call.
    static bool reassemble(const FrameHeader& hdr,
                           const uint8_t* chunk_data, size_t chunk_len,
                           uint8_t* assembly_buf,
                           uint64_t* chunks_received_mask); // bitmask, max 64 chunks
};

} // namespace ble
} // namespace airtak
