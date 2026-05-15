// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Router.h — Flood router with duplicate suppression
// Planning: §8.7, §8.9 | Roadmap: 2.5, 2.6, 2.9, 2.10, 2.11
//
// Implements Meshtastic-compatible ManagedFlood algorithm:
//   - Re-broadcast up to hop_limit times; decrement per hop
//   - Rolling duplicate-suppression cache on (source_id, packet_id)
//   - SNR-based relay delay (§8.7)
//   - Heard-by relay suppression (§8.7)
//   - Per-packet-type default hop limits from Defaults.h (§8.7)
//   - Application-layer ACKNOWLEDGED delivery (§8.9)
//   - hop_start preserved in Flags [2:0] across all relay hops (§8.2)

#pragma once

#include <cstdint>
#include <cstddef>
#include <FreeRTOS.h>
#include <queue.h>
#include "mesh/Portnums.h"
#include "mesh/PacketPool.h"

namespace airtak {

constexpr uint8_t OTA_HEADER_BYTES = 16;

// Parsed representation of the OTA wire header (§8.2).
struct OtaHeader {
    uint32_t dest_node_id;   // bytes 0–3
    uint32_t src_node_id;    // bytes 4–7
    uint32_t packet_id;      // bytes 8–11
    uint8_t  flags;          // byte 12: [7:5]=hop_limit [4]=want_ack [3]=via_mqtt [2:0]=hop_start
    uint8_t  channel_hash;   // byte 13
    uint8_t  next_hop_low;   // byte 14
    uint8_t  relay_node_low; // byte 15

    // Helpers
    uint8_t hopLimit()  const { return (flags >> 5) & 0x07; }
    uint8_t hopStart()  const { return flags & 0x07; }
    bool    wantAck()   const { return (flags >> 4) & 0x01; }
    void setHopLimit(uint8_t v) { flags = (flags & ~0xE0) | ((v & 0x07) << 5); }
};

// Entry in the application-layer ACK retry queue (§8.9).
//
// State machine:
//   in_backoff == false  →  timeout_ms is the ACK deadline; on expiry,
//                           compute backoff, set in_backoff=true, set new
//                           timeout_ms = now + backoff.
//   in_backoff == true   →  timeout_ms is the backoff deadline; on expiry,
//                           retransmit with new packet_id, set in_backoff=false,
//                           increment attempts, set timeout_ms = now + ack_timeout.
struct AckRetryEntry {
    uint32_t msg_id;           // logical message ID (Data.request_id)
    uint32_t dest_node_id;     // original destination for retries
    uint32_t timeout_ms;       // absolute deadline: ACK deadline or backoff deadline
    uint8_t  attempts;         // retries attempted so far (0 = initial send only)
    uint8_t  hop_start;        // original hop_start for timeout formula
    uint8_t  slot_index;       // channel slot used for the original transmission
    uint32_t portnum;          // for diagnostics/retry policy
    uint8_t  payload[237];     // original payload bytes (unencrypted Data protobuf)
    uint16_t payload_len;
    bool     in_use;
    bool     in_backoff;       // true when waiting for backoff before next retry
};

class Router {
public:
    // Phase 2.5: Initialize duplicate-suppression cache and retry queue.
    static void init();

    // Phase 2.5: Process a received OTA packet from the radio RX queue.
    // Decrypts, dispatches to the correct channel, queues relay if appropriate.
    static void processIncoming(const uint8_t* raw, uint8_t raw_len,
                                float rssi, float snr,
                                QueueHandle_t tx_queue);

    // Phase 2.5: Prepare and queue an outgoing packet from the BLE layer.
    // Applies per-packet-type hop limits from Defaults.h.
    static void sendPacket(Portnum portnum, uint32_t dest,
                           const uint8_t* payload, uint16_t payload_len,
                           bool want_ack, QueueHandle_t tx_queue);

    // Phase 2.3: Prepare and queue an outgoing packet from a decoded
    // meshtastic.Data protobuf delivered over BLE MESH_TX.
    static bool sendDataPacket(const uint8_t* data_pb, uint16_t data_len,
                               QueueHandle_t tx_queue);

    // Phase 2.11: Tick the ACK retry queue — called from Router task every 100 ms.
    // Retransmits entries whose timeout has elapsed; removes after MAX_RETRIES.
    static void tickRetryQueue(QueueHandle_t tx_queue);

    // Phase 2.6: Tick the pending-relay queue; emit packets whose delay has elapsed.
    static void tickRelayQueue(QueueHandle_t tx_queue);

    // Phase 2.11: Called when a AIRTAK_ACK is received. Removes matching entry from
    // retry queue using ack_request_id (NOT packet ID — see §8.9).
    static void handleAck(uint32_t ack_request_id);

    // Phase 2.10: Called when the Router overhears a relay of a queued packet.
    // If overheard SNR ≥ own received SNR, cancel the queued relay.
    static void handleOverheardRelay(uint32_t src_id, uint32_t packet_id, float overheard_snr);

    // Returns the default hop_limit for a given portnum (§8.7 table, Defaults.h).
    static uint8_t defaultHopLimit(Portnum portnum);

    // Returns true if this portnum is eligible for application-layer ACK (§8.9).
    static bool isAcknowledgedPortnum(Portnum portnum) {
        switch (portnum) {
            case Portnum::TEXT_MESSAGE_APP:
            case Portnum::AIRTAK_GAME:
            case Portnum::AIRTAK_PLAYER:
            case Portnum::AIRTAK_ZONE:
            case Portnum::AIRTAK_OBJECTIVE:
            case Portnum::AIRTAK_ADMIN:
                return true;
            default:
                return false;
        }
    }

#ifdef AIRTAK_NATIVE_TEST
    static size_t testPendingRelayCount();
    static size_t testRetryEntryCount();
    static size_t testAppDedupCount();
    static bool testFirstRelay(uint8_t* out, uint8_t& out_len,
                               uint32_t& send_after_ms, float& rx_snr);
    static bool testFirstRetry(uint32_t& msg_id, uint8_t& attempts,
                               uint32_t& timeout_ms, uint8_t& hop_start,
                               uint16_t& payload_len);
    static bool testIsPacketAddressedToMe(const OtaHeader& hdr,
                                         uint32_t data_dest);
#endif
};

} // namespace airtak
