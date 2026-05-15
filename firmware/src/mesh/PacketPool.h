// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/PacketPool.h — Fixed-size preallocated packet buffer pool
// Planning: §9.2 | Roadmap: 1.7, 2.5
//
// Prevents heap fragmentation from frequent packet allocation in the radio task.
// All packet allocations in the mesh stack use this pool exclusively.

#pragma once

#include <cstdint>
#include <cstddef>

namespace airtak {

// Maximum number of concurrently live packets in the system.
// Sized to hold: radio RX queue (16) + TX queue (8) + ACK retry queue (8) + headroom (8).
constexpr size_t PACKET_POOL_SIZE = 40;

// Maximum on-air packet size (§8.2 wire format + header overhead).
constexpr size_t PACKET_POOL_BUF_SIZE = 256;

struct PooledPacket {
    uint8_t  data[PACKET_POOL_BUF_SIZE];
    uint16_t length;
    float    rssi;   // received signal strength (dBm), set by Radio task
    float    snr;    // signal-to-noise ratio (dB),    set by Radio task
    uint8_t  phy_profile_index; // selected LoRa PHY profile for TX frames
    bool     in_use;
};

class PacketPool {
public:
    // Initialize the pool. Must be called before any alloc/free.
    static void init();

    // Allocate a packet buffer. Returns nullptr if pool is exhausted.
    // All allocations are O(1). Thread-safe (mutex-protected).
    static PooledPacket* alloc();

    // Return a packet buffer to the pool. Safe to call with nullptr.
    static void free(PooledPacket* pkt);

    // Diagnostic: number of currently allocated packets.
    static size_t usedCount();
};

} // namespace airtak
