// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/Router.cpp — Flood router implementation
// Planning: §8.7, §8.9 | Roadmap: 2.5, 2.6, 2.9, 2.10, 2.11

#include "mesh/Router.h"
#include "mesh/Crypto.h"
#include "mesh/ChannelManager.h"
#include "mesh/MeshNode.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <cstring>
#include <Arduino.h>
#include <FreeRTOS.h>

namespace mash {

// ── Duplicate-suppression cache (§8.7) ────────────────────────────────────────
struct DupEntry {
    uint32_t src_id;
    uint32_t packet_id;
    uint32_t expires_ms;
    float    rx_snr;        // SNR when we received this packet (for heard-by suppression)
    bool     in_use;
};

static DupEntry    s_dup_cache[defaults::ROUTER_DUP_CACHE_SIZE];
static AckRetryEntry s_retry_queue[defaults::ACK_RETRY_QUEUE_SIZE];

// ── Router::init() ────────────────────────────────────────────────────────────
void Router::init() {
    memset(s_dup_cache,    0, sizeof(s_dup_cache));
    memset(s_retry_queue,  0, sizeof(s_retry_queue));
}

// ── Router::processIncoming() ────────────────────────────────────────────────
void Router::processIncoming(const uint8_t* raw, uint8_t raw_len,
                             float rssi, float snr,
                             QueueHandle_t tx_queue) {
    if (raw_len < 12) return; // minimum OTA header size

    // Parse OTA header (§8.2)
    OtaHeader hdr;
    memcpy(&hdr.dest_node_id,  raw + 0, 4);
    memcpy(&hdr.src_node_id,   raw + 4, 4);
    hdr.packet_id_low = raw[8];
    hdr.flags         = raw[9];
    hdr.channel_hash  = raw[10];
    hdr.next_hop_low  = raw[11];

    // Phase 2.7: Dispatch to ChannelManager to find matching channel by hash.
    // If no channel matches, silently discard.
    // TODO: implement ChannelManager::findByHash()

    // Duplicate suppression check (§8.7)
    uint32_t now_ms  = millis();
    uint32_t full_id = (uint32_t(hdr.src_node_id) ^ uint32_t(hdr.packet_id_low));

    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        auto& e = s_dup_cache[i];
        if (e.in_use && e.src_id == hdr.src_node_id && e.packet_id == full_id) {
            if (now_ms < e.expires_ms) {
                // Duplicate: check heard-by suppression (§8.7)
                handleOverheardRelay(hdr.src_node_id, full_id, snr);
                return;
            }
            e.in_use = false; // expired entry
        }
    }

    // Add to duplicate-suppression cache
    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        if (!s_dup_cache[i].in_use) {
            s_dup_cache[i] = { hdr.src_node_id, full_id,
                               now_ms + defaults::ROUTER_DUP_CACHE_TTL_MS,
                               snr, true };
            break;
        }
    }

    // Relay if hop_limit > 0
    if (hdr.hopLimit() > 0) {
        // SNR-based relay delay (§8.7): delay_ms = (SNR_max − SNR_received) × 50 + random(0, 50)
        // TODO Phase 2.6: implement actual delay via timer; placeholder is immediate relay
        hdr.setHopLimit(hdr.hopLimit() - 1);
        // TODO: queue relay packet on tx_queue
    }

    // TODO Phase 2.3/2.4: Decrypt payload, parse portnum, dispatch to application handlers.
    (void)tx_queue; (void)rssi;
}

// ── Router::sendPacket() ─────────────────────────────────────────────────────
void Router::sendPacket(Portnum portnum, uint32_t dest,
                        const uint8_t* payload, uint16_t payload_len,
                        bool want_ack, QueueHandle_t tx_queue) {
    // Apply per-packet-type default hop limit (§8.7)
    uint8_t hop_limit = defaultHopLimit(portnum);
    // TODO Phase 2.5: Build OTA header, encrypt payload, push to tx_queue.
    // Set hop_start = hop_limit (§8.2 — hop_start preserved, never decremented by sender).
    // For ACKNOWLEDGED packets, add entry to s_retry_queue (Phase 2.11).
    (void)dest; (void)payload; (void)payload_len; (void)want_ack; (void)tx_queue;
    (void)hop_limit;
}

// ── Router::tickRetryQueue() ─────────────────────────────────────────────────
void Router::tickRetryQueue(QueueHandle_t tx_queue) {
    // Phase 2.11: Iterate retry queue; retransmit expired entries with new packet_id.
    // Backoff formula: random(0, 200 * 2^attempt) ms (§8.9).
    // After MAX_RETRIES, notify BLE layer of delivery failure.
    uint32_t now_ms = millis();
    for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
        auto& e = s_retry_queue[i];
        if (!e.in_use || e.attempts >= defaults::ACK_MAX_RETRIES) {
            if (e.in_use && e.attempts >= defaults::ACK_MAX_RETRIES) {
                e.in_use = false; // TODO: notify BLE of failure
            }
            continue;
        }
        if (now_ms >= e.timeout_ms) {
            // TODO Phase 2.11: Generate new packet_id, retransmit with same request_id.
            e.attempts++;
            uint32_t backoff = (uint32_t)random(0, defaults::ACK_RETRY_BASE_MS * (1 << e.attempts));
            e.timeout_ms = now_ms + backoff +
                           max(defaults::ACK_TIMEOUT_MIN_MS,
                               2 * e.hop_start * defaults::ACK_TIMEOUT_PER_HOP_MS);
        }
    }
    (void)tx_queue;
}

// ── Router::handleAck() ──────────────────────────────────────────────────────
void Router::handleAck(uint32_t ack_request_id) {
    for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
        if (s_retry_queue[i].in_use && s_retry_queue[i].msg_id == ack_request_id) {
            s_retry_queue[i].in_use = false;
            return;
        }
    }
}

// ── Router::handleOverheardRelay() ───────────────────────────────────────────
void Router::handleOverheardRelay(uint32_t src_id, uint32_t packet_id, float overheard_snr) {
    // Phase 2.10: If we have this packet queued for relay AND overheard_snr ≥ our rx_snr,
    // cancel the queued relay (heard-by suppression, §8.7).
    // TODO: check relay backoff queue for matching (src_id, packet_id)
    (void)src_id; (void)packet_id; (void)overheard_snr;
}

// ── Router::defaultHopLimit() ────────────────────────────────────────────────
uint8_t Router::defaultHopLimit(Portnum portnum) {
    switch (portnum) {
        case Portnum::TEXT_MESSAGE_APP:    return defaults::HOP_LIMIT_TEXT_MESSAGE;
        case Portnum::POSITION_APP:        return defaults::HOP_LIMIT_POSITION;
        case Portnum::NODEINFO_APP:        return defaults::HOP_LIMIT_NODEINFO;
        case Portnum::TELEMETRY_APP:       return defaults::HOP_LIMIT_TELEMETRY;
        case Portnum::MASH_PLAYER:         return defaults::HOP_LIMIT_MASH_PLAYER;
        case Portnum::MASH_GAME:           return defaults::HOP_LIMIT_MASH_GAME;
        case Portnum::MASH_ZONE:           return defaults::HOP_LIMIT_MASH_ZONE;
        case Portnum::MASH_OBJECTIVE:      return defaults::HOP_LIMIT_MASH_OBJECTIVE;
        case Portnum::MASH_ADMIN:          return defaults::HOP_LIMIT_MASH_ADMIN;
        case Portnum::MASH_ACK:            return defaults::HOP_LIMIT_MASH_ACK; // set dynamically
        case Portnum::MASH_POSITION_REQUEST: return defaults::HOP_LIMIT_POSITION_REQUEST;
        case Portnum::MASH_AUDIO:          return defaults::HOP_LIMIT_MASH_AUDIO;
        default:                           return 3;
    }
}

} // namespace mash
