// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/Router.cpp — Flood router implementation
// Planning: §8.7, §8.9 | Roadmap: 2.5, 2.6, 2.9, 2.10, 2.11

#include "mesh/Router.h"
#include "mesh/Crypto.h"
#include "mesh/ChannelManager.h"
#include "mesh/MeshNode.h"
#include "mesh/PacketPool.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <cstring>
#include <Arduino.h>
#include <FreeRTOS.h>

namespace mash {

// ── Duplicate-suppression cache (§8.7) ────────────────────────────────────────
struct DupEntry {
    uint32_t src_id;
    uint32_t packet_id;   // (src_id XOR packet_id_low) — simplified unique key
    uint32_t expires_ms;
    float    rx_snr;      // SNR of our reception (for heard-by suppression, §8.7)
    bool     in_use;
};

// Pending relay queue: packets queued for relay after SNR-based backoff.
struct RelayEntry {
    uint8_t  data[LORA_MAX_PAYLOAD_BYTES];
    uint8_t  length;
    uint32_t send_after_ms;   // absolute time to transmit
    bool     in_use;
    float    rx_snr;          // our rx SNR (for heard-by cancellation)
};

static DupEntry    s_dup_cache[defaults::ROUTER_DUP_CACHE_SIZE];
static AckRetryEntry s_retry_queue[defaults::ACK_RETRY_QUEUE_SIZE];
static RelayEntry  s_relay_queue[16];  // pending relay slots
static uint32_t    s_next_packet_id = 1; // local packet ID counter

// ── Packet ID generation ──────────────────────────────────────────────────────
static uint32_t nextPacketId() {
    return s_next_packet_id++;
}

// ── Minimal varint encoder used for inline proto building ────────────────────
static uint8_t writeVarU32(uint8_t* buf, uint32_t v) {
    uint8_t n = 0;
    while (v >= 0x80) { buf[n++] = (uint8_t)((v & 0x7F) | 0x80); v >>= 7; }
    buf[n++] = (uint8_t)v;
    return n;
}

// ── Router::init() ────────────────────────────────────────────────────────────
void Router::init() {
    memset(s_dup_cache,   0, sizeof(s_dup_cache));
    memset(s_retry_queue, 0, sizeof(s_retry_queue));
    memset(s_relay_queue, 0, sizeof(s_relay_queue));
    // Seed packet ID with a pseudorandom value derived from millis() to reduce
    // collisions with other nodes that also boot close in time.
    s_next_packet_id = (millis() & 0xFFFF) | (MeshNode::nodeId() << 8);
}

// ── buildOtaHeader() ─────────────────────────────────────────────────────────
// Serialize the §8.2 OTA wire header into buf[0..11].
static void buildOtaHeader(uint8_t* buf, const OtaHeader& hdr) {
    memcpy(buf + 0, &hdr.dest_node_id, 4);
    memcpy(buf + 4, &hdr.src_node_id,  4);
    buf[8]  = hdr.packet_id_low;
    buf[9]  = hdr.flags;
    buf[10] = hdr.channel_hash;
    buf[11] = hdr.next_hop_low;
}

// ── Router::processIncoming() ─────────────────────────────────────────────────
void Router::processIncoming(const uint8_t* raw, uint8_t raw_len,
                             float rssi, float snr,
                             QueueHandle_t tx_queue) {
    if (raw_len < 12) return;

    // Parse OTA header (§8.2).
    OtaHeader hdr;
    memcpy(&hdr.dest_node_id, raw + 0, 4);
    memcpy(&hdr.src_node_id,  raw + 4, 4);
    hdr.packet_id_low = raw[8];
    hdr.flags         = raw[9];
    hdr.channel_hash  = raw[10];
    hdr.next_hop_low  = raw[11];

    const uint8_t* payload     = raw + 12;
    uint8_t        payload_len = raw_len - 12;

    // Drop our own transmissions (could occur on full-duplex test setups).
    if (hdr.src_node_id == MeshNode::nodeId()) return;

    // Find matching channel by hash (§8.6).
    const ChannelSlot* slot = ChannelManager::findByHash(hdr.channel_hash);
    if (!slot) return; // unknown channel — discard

    // Build a simplified unique key for duplicate suppression.
    uint32_t dup_key = hdr.src_node_id ^ ((uint32_t)hdr.packet_id_low << 24);
    uint32_t now_ms  = millis();

    // Check duplicate-suppression cache (§8.7).
    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        auto& e = s_dup_cache[i];
        if (!e.in_use || e.expires_ms < now_ms) {
            if (e.in_use) e.in_use = false; // expired
            continue;
        }
        if (e.src_id == hdr.src_node_id && e.packet_id == dup_key) {
            // Duplicate: attempt heard-by relay suppression (§8.7).
            handleOverheardRelay(hdr.src_node_id, dup_key, snr);
            return;
        }
    }

    // Add to duplicate-suppression cache.
    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        if (!s_dup_cache[i].in_use) {
            s_dup_cache[i] = { hdr.src_node_id, dup_key,
                               now_ms + defaults::ROUTER_DUP_CACHE_TTL_MS,
                               snr, true };
            break;
        }
    }

    // Decrypt payload using channel AES key (§15.1).
    // Work on a local copy to avoid modifying the pool buffer.
    uint8_t decrypted[MESH_MAX_PAYLOAD_BYTES];
    if (payload_len > sizeof(decrypted)) return;
    memcpy(decrypted, payload, payload_len);

    if (slot->psk_len > 0) {
        // Nonce = packet_id_low (8-bit OTA field, zero-extended) || src_node_id (§15.1).
        // Receivers can only reconstruct the nonce from the 8-bit OTA field; both sides
        // must use the same 8-bit-extended value for decryption to succeed.
        uint8_t nonce[AES_NONCE_BYTES];
        Crypto::buildNonce((uint32_t)hdr.packet_id_low, hdr.src_node_id, nonce);
        Crypto::crypt(slot->aes_key, nonce, decrypted, payload_len);
    }

    // Deliver to BLE MESH_RX if uplink is enabled for this slot.
    // The payload is the raw protobuf Data message (including portnum).
    // We prefix the decrypted payload with the 12-byte OTA header for the Android app.
    if (slot->uplink_enabled) {
        // Build a ble_tx_queue entry: type byte 0x01 (MESH_RX) + OTA header + decrypted payload.
        extern QueueHandle_t g_ble_tx_queue;
        PooledPacket* bpkt = PacketPool::alloc();
        if (bpkt) {
            bpkt->data[0] = 0x01; // MESH_RX type tag
            memcpy(bpkt->data + 1, raw, 12);               // OTA header
            memcpy(bpkt->data + 13, decrypted, payload_len); // decrypted payload
            bpkt->length = 1 + 12 + payload_len;
            if (xQueueSendToBack(g_ble_tx_queue, &bpkt, 0) != pdTRUE) {
                PacketPool::free(bpkt);
            }
        }
    }

    // Relay if hop_limit > 0 and not MASH_AUDIO (which must never be relayed, §12.7).
    // Parse portnum from decrypted Data proto (field 1, varint; tag = 0x08).
    uint32_t portnum_value = 0;
    if (payload_len >= 2 && decrypted[0] == 0x08) {
        uint16_t ppos = 1;
        while (ppos < payload_len) {
            uint8_t b = decrypted[ppos];
            portnum_value |= (uint32_t)(b & 0x7F) << ((ppos - 1) * 7);
            ppos++;
            if (!(b & 0x80)) break;
        }
    }

    // ── MASH_POSITION_REQUEST dispatch (§2.12) ──────────────────────────────
    // When we receive a PositionRequestPacket addressed to us (or broadcast),
    // respond with a unicast POSITION_APP within 2 s, bypassing the beaconing timer.
    if (portnum_value == static_cast<uint32_t>(Portnum::MASH_POSITION_REQUEST)) {
        // Parse the inner PositionRequestPacket from the Data.payload field.
        // Data proto: field 1 = portnum (already parsed), field 2 = payload bytes.
        // Look for tag 0x12 (field 2, wire 2) in decrypted[].
        uint32_t target_node_id = 0xFFFFFFFF; // broadcast = any node responds
        for (uint16_t i = 1; i + 1 < payload_len; i++) {
            if (decrypted[i] == 0x12) { // Data.payload tag
                uint8_t inner_len = decrypted[i + 1];
                if (i + 2 + inner_len <= payload_len && inner_len >= 2) {
                    // Parse PositionRequestPacket.target_node_id (field 1, uint32, tag 0x08).
                    const uint8_t* inner = decrypted + i + 2;
                    if (inner[0] == 0x08) {
                        uint32_t tid = 0; uint8_t shift = 0; uint8_t j = 1;
                        while (j < inner_len) {
                            uint8_t b = inner[j++];
                            tid |= (uint32_t)(b & 0x7F) << shift;
                            if (!(b & 0x80)) break;
                            shift += 7;
                        }
                        target_node_id = tid;
                    }
                }
                break;
            }
        }
        if (target_node_id == MeshNode::nodeId() || target_node_id == 0xFFFFFFFF) {
            MeshNode::handlePositionRequest(hdr.src_node_id, tx_queue);
        }
        // MASH_POSITION_REQUEST is BEST_EFFORT; still relay below if hop_limit > 0.
    }

    // ── MASH_ACK receive: cancel pending retry queue entry (§8.9) ───────────
    // Correlation is via Data.request_id (field 6, tag 0x30) which the ACK sender
    // sets to the ack_request_id so the original sender can match it.
    if (portnum_value == static_cast<uint32_t>(Portnum::MASH_ACK)) {
        for (uint16_t pi = 0; pi + 1 < payload_len; pi++) {
            if (decrypted[pi] == 0x30) { // Data.request_id, field 6, wire 0
                uint32_t ack_req_id = 0; uint8_t shift = 0; uint16_t j = pi + 1;
                while (j < payload_len && shift < 32) {
                    uint8_t b = decrypted[j++];
                    ack_req_id |= (uint32_t)(b & 0x7F) << shift;
                    if (!(b & 0x80)) break;
                    shift += 7;
                }
                handleAck(ack_req_id);
                break;
            }
        }
        // Still relay MASH_ACK so it can reach back to the original sender.
    }

    // ── Generate MASH_ACK when sender requested acknowledgment (§8.9) ───────
    // Never ACK a MASH_ACK itself (prevents infinite ACK loops).
    if (hdr.wantAck() && portnum_value != static_cast<uint32_t>(Portnum::MASH_ACK)) {
        // Parse Data.request_id (field 6, tag 0x30) from the decrypted outer Data proto.
        uint32_t req_id = 0;
        for (uint16_t pi = 0; pi + 1 < payload_len; pi++) {
            if (decrypted[pi] == 0x30) { // Data.request_id tag
                uint32_t rv = 0; uint8_t shift = 0; uint16_t j = pi + 1;
                while (j < payload_len && shift < 32) {
                    uint8_t b = decrypted[j++];
                    rv |= (uint32_t)(b & 0x7F) << shift;
                    if (!(b & 0x80)) break;
                    shift += 7;
                }
                req_id = rv;
                break;
            }
        }
        if (req_id != 0) {
            // Build Data proto bytes for MASH_ACK (portnum 261 = 0x85 0x02 as varint).
            uint8_t ack_data[64]; uint8_t ap = 0;
            ack_data[ap++] = 0x08; ack_data[ap++] = 0x85; ack_data[ap++] = 0x02; // portnum=261
            // Build inner MashAck sub-message.
            uint8_t inner[32]; uint8_t ip = 0;
            inner[ip++] = 0x08; ip += writeVarU32(inner + ip, req_id);            // ack_request_id (field 1)
            inner[ip++] = 0x10; ip += writeVarU32(inner + ip, MeshNode::nodeId()); // from_node_id (field 2)
            inner[ip++] = 0x18; inner[ip++] = hdr.hopStart();                     // original_hop_start (field 3)
            ack_data[ap++] = 0x12; ack_data[ap++] = ip;                           // Data.payload tag + length
            memcpy(ack_data + ap, inner, ip); ap += ip;
            ack_data[ap++] = 0x30; ap += writeVarU32(ack_data + ap, req_id);      // Data.request_id = ack_req_id
            // ACK hop_limit = original packet's hop_start (§8.9); fall back to 7 if zero.
            uint8_t ack_hop = hdr.hopStart();
            if (ack_hop == 0) ack_hop = defaults::ACK_HOP_START_FALLBACK;
            // Build OTA header for ACK (uses hop_start-derived hop_limit, not defaultHopLimit).
            OtaHeader ack_hdr;
            ack_hdr.dest_node_id  = hdr.src_node_id;
            ack_hdr.src_node_id   = MeshNode::nodeId();
            uint32_t ack_pkt_id   = nextPacketId();
            ack_hdr.packet_id_low = (uint8_t)(ack_pkt_id & 0xFF);
            ack_hdr.flags         = ((ack_hop & 0x07) << 5) | (ack_hop & 0x07); // no want_ack on ACK
            ack_hdr.channel_hash  = slot->channel_hash;
            ack_hdr.next_hop_low  = 0;
            uint8_t ack_enc[64];
            memcpy(ack_enc, ack_data, ap);
            if (slot->psk_len > 0) {
                uint8_t nonce2[AES_NONCE_BYTES];
                Crypto::buildNonce((uint32_t)ack_hdr.packet_id_low, ack_hdr.src_node_id, nonce2);
                Crypto::crypt(slot->aes_key, nonce2, ack_enc, ap);
            }
            PooledPacket* ack_pkt = PacketPool::alloc();
            if (ack_pkt) {
                buildOtaHeader(ack_pkt->data, ack_hdr);
                memcpy(ack_pkt->data + 12, ack_enc, ap);
                ack_pkt->length = 12 + ap;
                extern EventGroupHandle_t g_radio_event_group;
                if (xQueueSendToBack(tx_queue, &ack_pkt, 0) != pdTRUE) {
                    PacketPool::free(ack_pkt);
                } else {
                    xEventGroupSetBits(g_radio_event_group, RADIO_EVENT_TX_READY);
                }
            }
        }
    }

    if (hdr.hopLimit() > 0) {
        // Check portnum to block audio relay (already parsed above as portnum_value).
        bool is_audio = (portnum_value == static_cast<uint32_t>(Portnum::MASH_AUDIO));

        if (!is_audio) {
            // SNR-based relay delay: delay_ms = (SNR_max - SNR_received) × 50 + random(0, 50) (§8.7).
            // SNR_max ≈ 10 dB for SX1262 at SF11/BW250 in line-of-sight.
            float snr_max  = 10.0f;
            uint32_t delay_ms = (uint32_t)(max(0.0f, snr_max - snr) * 50.0f)
                                + (uint32_t)(random(0, 50));

            // Build relay packet: decrement hop_limit, preserve hop_start.
            uint8_t relay_buf[LORA_MAX_PAYLOAD_BYTES];
            OtaHeader relay_hdr = hdr;
            relay_hdr.setHopLimit(hdr.hopLimit() - 1);
            relay_hdr.next_hop_low = 0; // cleared by relay node
            buildOtaHeader(relay_buf, relay_hdr);
            // Re-encrypt with the original (still-encrypted) payload for relay.
            memcpy(relay_buf + 12, payload, payload_len);

            // Add to pending relay queue.
            for (size_t i = 0; i < 16; i++) {
                if (!s_relay_queue[i].in_use) {
                    uint8_t relay_len = 12 + payload_len;
                    memcpy(s_relay_queue[i].data, relay_buf, relay_len);
                    s_relay_queue[i].length       = relay_len;
                    s_relay_queue[i].send_after_ms = now_ms + delay_ms;
                    s_relay_queue[i].rx_snr        = snr;
                    s_relay_queue[i].in_use        = true;
                    break;
                }
            }
        }
    }

    (void)rssi;
}

// ── Router::tickRelayQueue() ─────────────────────────────────────────────────
void Router::tickRelayQueue(QueueHandle_t tx_queue) {
    uint32_t now_ms = millis();
    for (size_t i = 0; i < 16; i++) {
        auto& e = s_relay_queue[i];
        if (!e.in_use) continue;
        if (now_ms >= e.send_after_ms) {
            // Queue for radio TX.
            PooledPacket* pkt = PacketPool::alloc();
            if (pkt) {
                memcpy(pkt->data, e.data, e.length);
                pkt->length = e.length;
                extern EventGroupHandle_t g_radio_event_group;
                if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
                    PacketPool::free(pkt);
                } else {
                    xEventGroupSetBits(g_radio_event_group, RADIO_EVENT_TX_READY);
                }
            }
            e.in_use = false;
        }
    }
}

// ── Router::sendPacket() ─────────────────────────────────────────────────────
void Router::sendPacket(Portnum portnum, uint32_t dest,
                        const uint8_t* payload, uint16_t payload_len,
                        bool want_ack, QueueHandle_t tx_queue) {
    if (payload_len > MESH_MAX_PAYLOAD_BYTES) return;

    const ChannelSlot* slot = ChannelManager::primarySlot();
    if (!slot) return;

    uint8_t hop_limit = defaultHopLimit(portnum);
    uint32_t packet_id = nextPacketId();
    uint32_t msg_id    = packet_id; // for first send, msg_id == packet_id

    // Build OTA header (§8.2).
    OtaHeader hdr;
    hdr.dest_node_id  = dest;
    hdr.src_node_id   = MeshNode::nodeId();
    hdr.packet_id_low = (uint8_t)(packet_id & 0xFF);
    // flags: [7:5]=hop_limit [4]=want_ack [3]=via_mqtt [2:0]=hop_start
    hdr.flags         = ((hop_limit & 0x07) << 5)
                      | (want_ack ? (1 << 4) : 0)
                      | (hop_limit & 0x07);  // hop_start = hop_limit (sender sets equal)
    hdr.channel_hash  = slot->channel_hash;
    hdr.next_hop_low  = 0;

    // Encrypt payload.
    uint8_t enc[MESH_MAX_PAYLOAD_BYTES];
    memcpy(enc, payload, payload_len);
    if (slot->psk_len > 0) {
        // Use only the OTA-visible low byte of packet_id for nonce (§15.1).
        // Receivers reconstruct the nonce from the 8-bit OTA header field; using
        // the full 32-bit ID would cause a nonce mismatch after packet #256.
        uint8_t nonce[16];
        Crypto::buildNonce((uint32_t)hdr.packet_id_low, hdr.src_node_id, nonce);
        Crypto::crypt(slot->aes_key, nonce, enc, payload_len);
    }

    // Build complete OTA frame.
    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    buildOtaHeader(pkt->data, hdr);
    memcpy(pkt->data + 12, enc, payload_len);
    pkt->length = 12 + payload_len;

    extern EventGroupHandle_t g_radio_event_group;
    if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
        PacketPool::free(pkt);
        return;
    }
    xEventGroupSetBits(g_radio_event_group, RADIO_EVENT_TX_READY);

    // Queue for ACKNOWLEDGED retry if requested (§8.9).
    if (want_ack && isAcknowledgedPortnum(portnum)) {
        // Find a free retry queue slot, evicting the oldest if full.
        size_t slot_idx = defaults::ACK_RETRY_QUEUE_SIZE;
        uint32_t oldest_ms = UINT32_MAX;
        size_t oldest_idx  = 0;
        for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
            if (!s_retry_queue[i].in_use) { slot_idx = i; break; }
            if (s_retry_queue[i].timeout_ms < oldest_ms) {
                oldest_ms  = s_retry_queue[i].timeout_ms;
                oldest_idx = i;
            }
        }
        if (slot_idx == defaults::ACK_RETRY_QUEUE_SIZE) slot_idx = oldest_idx; // evict

        uint32_t timeout = max(defaults::ACK_TIMEOUT_MIN_MS,
                               2u * hop_limit * defaults::ACK_TIMEOUT_PER_HOP_MS);
        s_retry_queue[slot_idx] = {
            msg_id,                  // msg_id (Data.request_id)
            millis() + timeout,      // timeout_ms
            0,                       // attempts
            hop_limit,               // hop_start
            (uint8_t)static_cast<uint32_t>(portnum),
            {},                      // payload copy
            payload_len,
            true                     // in_use
        };
        memcpy(s_retry_queue[slot_idx].payload, payload,
               min((size_t)payload_len, sizeof(s_retry_queue[slot_idx].payload)));
    }
}

// ── Router::tickRetryQueue() ─────────────────────────────────────────────────
void Router::tickRetryQueue(QueueHandle_t tx_queue) {
    uint32_t now_ms = millis();
    for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
        auto& e = s_retry_queue[i];
        if (!e.in_use) continue;

        if (e.attempts >= defaults::ACK_MAX_RETRIES) {
            e.in_use = false; // delivery failed silently; BLE notification pending
            continue;
        }

        if (now_ms >= e.timeout_ms) {
            // Retransmit with a NEW packet_id but same msg_id (Data.request_id) (§8.9).
            uint32_t new_packet_id = nextPacketId();
            const ChannelSlot* slot = ChannelManager::primarySlot();
            if (!slot) { e.in_use = false; continue; }

            OtaHeader hdr;
            hdr.dest_node_id  = 0xFFFFFFFF; // broadcast retry
            hdr.src_node_id   = MeshNode::nodeId();
            hdr.packet_id_low = (uint8_t)(new_packet_id & 0xFF);
            hdr.flags         = ((e.hop_start & 0x07) << 5)
                              | (1 << 4)           // want_ack=true
                              | (e.hop_start & 0x07);
            hdr.channel_hash  = slot->channel_hash;
            hdr.next_hop_low  = 0;

            uint8_t enc[MESH_MAX_PAYLOAD_BYTES];
            memcpy(enc, e.payload, e.payload_len);
            if (slot->psk_len > 0) {
                uint8_t nonce[16];
                Crypto::buildNonce(new_packet_id, MeshNode::nodeId(), nonce);
                Crypto::crypt(slot->aes_key, nonce, enc, e.payload_len);
            }

            PooledPacket* pkt = PacketPool::alloc();
            if (pkt) {
                buildOtaHeader(pkt->data, hdr);
                memcpy(pkt->data + 12, enc, e.payload_len);
                pkt->length = 12 + e.payload_len;
                extern EventGroupHandle_t g_radio_event_group;
                if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
                    PacketPool::free(pkt);
                } else {
                    xEventGroupSetBits(g_radio_event_group, RADIO_EVENT_TX_READY);
                }
            }

            e.attempts++;
            uint32_t backoff = (uint32_t)random(0, defaults::ACK_RETRY_BASE_MS * (1 << e.attempts));
            uint32_t timeout = max(defaults::ACK_TIMEOUT_MIN_MS,
                                   2u * (uint32_t)e.hop_start * defaults::ACK_TIMEOUT_PER_HOP_MS);
            e.timeout_ms = now_ms + backoff + timeout;
        }
    }
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
void Router::handleOverheardRelay(uint32_t src_id, uint32_t dup_key, float overheard_snr) {
    // If we overheard a relay and the overheard SNR ≥ our own rx SNR,
    // cancel our queued relay of the same packet (§8.7).
    for (size_t i = 0; i < 16; i++) {
        auto& e = s_relay_queue[i];
        if (!e.in_use) continue;
        // Match by checking the src_id in the relay buffer OTA header (bytes 4–7).
        uint32_t relay_src;
        memcpy(&relay_src, e.data + 4, 4);
        if (relay_src == src_id && overheard_snr >= e.rx_snr) {
            e.in_use = false;
        }
    }
    (void)dup_key;
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
        case Portnum::MASH_ACK:            return defaults::HOP_LIMIT_MASH_ACK;
        case Portnum::MASH_POSITION_REQUEST: return defaults::HOP_LIMIT_POSITION_REQUEST;
        case Portnum::MASH_AUDIO:          return defaults::HOP_LIMIT_MASH_AUDIO;
        default:                           return 3;
    }
}

} // namespace mash
