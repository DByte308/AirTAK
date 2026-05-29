// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ble/Framing.cpp — BLE GATT characteristic chunked framing implementation
// Planning: §10.2 | Roadmap: 1.5

#include "ble/Framing.h"
#include <cstring>

namespace airtak {
namespace ble {

size_t Framing::encodeChunk(const uint8_t* payload, size_t len,
                            uint16_t chunk_index,
                            uint8_t* out_buf, size_t out_buf_len) {
    uint16_t total_chunks = chunkCount(len);
    if (chunk_index >= total_chunks) return 0;

    size_t offset      = (size_t)chunk_index * BLE_FRAME_CHUNK_SIZE;
    size_t chunk_bytes = len - offset;
    if (chunk_bytes > BLE_FRAME_CHUNK_SIZE) chunk_bytes = BLE_FRAME_CHUNK_SIZE;
    if (out_buf_len < BLE_FRAME_HEADER_SIZE + chunk_bytes) return 0;

    // Write 6-byte little-endian header
    uint16_t tl = (uint16_t)len;
    uint16_t ci = chunk_index;
    uint16_t cc = total_chunks;
    memcpy(out_buf + 0, &tl, 2);
    memcpy(out_buf + 2, &ci, 2);
    memcpy(out_buf + 4, &cc, 2);
    // Write payload slice
    memcpy(out_buf + BLE_FRAME_HEADER_SIZE, payload + offset, chunk_bytes);
    return BLE_FRAME_HEADER_SIZE + chunk_bytes;
}

uint16_t Framing::chunkCount(size_t len) {
    if (len == 0) return 1;
    return (uint16_t)((len + BLE_FRAME_CHUNK_SIZE - 1) / BLE_FRAME_CHUNK_SIZE);
}

bool Framing::decodeChunk(const uint8_t* in_buf, size_t in_len,
                          FrameHeader* hdr_out,
                          const uint8_t** payload_out, size_t* payload_len_out) {
    if (in_len < BLE_FRAME_HEADER_SIZE) return false;
    memcpy(&hdr_out->total_length, in_buf + 0, 2);
    memcpy(&hdr_out->chunk_index,  in_buf + 2, 2);
    memcpy(&hdr_out->chunk_count,  in_buf + 4, 2);
    if (hdr_out->chunk_count == 0) return false;
    if (hdr_out->chunk_index >= hdr_out->chunk_count) return false;
    if (hdr_out->chunk_count > 64) return false;
    *payload_out     = in_buf + BLE_FRAME_HEADER_SIZE;
    *payload_len_out = in_len - BLE_FRAME_HEADER_SIZE;
    return true;
}

bool Framing::reassemble(const FrameHeader& hdr,
                         const uint8_t* chunk_data, size_t chunk_len,
                         uint8_t* assembly_buf,
                         uint64_t* chunks_received_mask) {
    if (hdr.chunk_count == 0 || hdr.chunk_count > 64) return false;
    if (hdr.chunk_index >= hdr.chunk_count) return false;
    size_t offset = (size_t)hdr.chunk_index * BLE_FRAME_CHUNK_SIZE;
    if (offset + chunk_len > hdr.total_length) return false;

    memcpy(assembly_buf + offset, chunk_data, chunk_len);
    *chunks_received_mask |= (uint64_t(1) << hdr.chunk_index);

    // Assembly complete when all bits are set
    uint64_t all_bits = (hdr.chunk_count == 64)
        ? UINT64_MAX
        : ((uint64_t(1) << hdr.chunk_count) - uint64_t(1));
    return (*chunks_received_mask & all_bits) == all_bits;
}

} // namespace ble
} // namespace airtak
