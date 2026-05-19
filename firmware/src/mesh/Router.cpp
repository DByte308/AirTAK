// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/Router.cpp — Flood router implementation
// Planning: §8.7, §8.9 | Roadmap: 2.5, 2.6, 2.9, 2.10, 2.11

#include "mesh/Router.h"
#include "mesh/Crypto.h"
// NOTE: <Arduino.h> MUST be included before ChannelManager.h on ESP32.
// esp32-hal-gpio.h defines the macro DISABLED=0x00; ChannelManager.h
// issues #undef DISABLED before the ChannelRole enum class so the member
// name is not substituted. If Arduino.h is included after ChannelManager.h
// the macro is re-defined and all subsequent ChannelRole::DISABLED
// expressions break at preprocessing.
// On native (AIRTAK_NATIVE_TEST), the native_stubs/Arduino.h provides
// millis()/max()/random() stubs and does NOT define DISABLED.
#include <Arduino.h>
#if !defined(AIRTAK_NATIVE_TEST)
#include <FreeRTOS.h>
#endif
#include "mesh/ChannelManager.h"
#include "mesh/MeshNode.h"
#include "mesh/PacketPool.h"
#include "mesh/DcTracker.h"
#include "ble/BLEQueue.h"
#include "config/Defaults.h"
#include "config/ConfigManager.h"
#include "hal/Radio.h"
#include "config.h"
#include <cstring>
#include <cstdio>

extern QueueHandle_t g_ble_tx_queue;
extern EventGroupHandle_t g_radio_event_group;

namespace airtak {

// ── Duplicate-suppression cache (§8.7) ────────────────────────────────────────
struct DupEntry {
    uint32_t src_id;
    uint32_t packet_id;
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
    uint8_t  phy_profile_index;
};

static DupEntry    s_dup_cache[defaults::ROUTER_DUP_CACHE_SIZE];
static AckRetryEntry s_retry_queue[defaults::ACK_RETRY_QUEUE_SIZE];
static RelayEntry  s_relay_queue[16];  // pending relay slots
static uint32_t    s_next_packet_id = 1; // local packet ID counter

struct AppDedupEntry {
    uint32_t src_id;
    uint32_t request_id;
    uint32_t expires_ms;
    bool     in_use;
};

static AppDedupEntry s_app_dedup[defaults::ACK_RETRY_QUEUE_SIZE * 4];

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

static bool appendVarU32(uint8_t* buf, uint16_t& pos, uint16_t max_len, uint32_t v) {
    do {
        if (pos >= max_len) return false;
        uint8_t b = static_cast<uint8_t>(v & 0x7F);
        v >>= 7;
        if (v) b |= 0x80;
        buf[pos++] = b;
    } while (v);
    return true;
}

static bool appendTag(uint8_t* buf, uint16_t& pos, uint16_t max_len, uint32_t field, uint8_t wire) {
    return appendVarU32(buf, pos, max_len, (field << 3) | wire);
}

static bool appendBytes(uint8_t* buf, uint16_t& pos, uint16_t max_len,
                        uint32_t field, const uint8_t* data, uint16_t len) {
    if (!appendTag(buf, pos, max_len, field, 2)) return false;
    if (!appendVarU32(buf, pos, max_len, len)) return false;
    if (pos + len > max_len) return false;
    memcpy(buf + pos, data, len);
    pos += len;
    return true;
}

struct DataMetadata {
    Portnum  portnum = Portnum::UNKNOWN_APP;
    uint32_t dest = 0xFFFFFFFF;
    uint32_t request_id = 0;
    bool     want_response = false;
    bool     has_request_id = false;
};

static bool readVarint(const uint8_t* buf, uint16_t len, uint16_t& pos, uint32_t& out) {
    out = 0;
    uint8_t shift = 0;
    while (pos < len && shift < 32) {
        uint8_t b = buf[pos++];
        out |= (uint32_t)(b & 0x7F) << shift;
        if (!(b & 0x80)) return true;
        shift += 7;
    }
    return false;
}

static bool skipField(const uint8_t* buf, uint16_t len, uint16_t& pos, uint8_t wire) {
    switch (wire) {
        case 0: {
            uint32_t ignored;
            return readVarint(buf, len, pos, ignored);
        }
        case 1:
            if (pos + 8 > len) return false;
            pos += 8;
            return true;
        case 2: {
            uint32_t field_len;
            if (!readVarint(buf, len, pos, field_len)) return false;
            if (pos + field_len > len) return false;
            pos += static_cast<uint16_t>(field_len);
            return true;
        }
        case 5:
            if (pos + 4 > len) return false;
            pos += 4;
            return true;
        default:
            return false;
    }
}

static bool findLengthDelimitedField(const uint8_t* buf, uint16_t len,
                                     uint32_t wanted_field,
                                     const uint8_t*& out_data,
                                     uint16_t& out_len) {
    uint16_t pos = 0;
    while (pos < len) {
        uint32_t tag;
        if (!readVarint(buf, len, pos, tag)) return false;
        uint8_t wire = tag & 0x07;
        uint32_t field = tag >> 3;
        if (wire == 2) {
            uint32_t field_len;
            if (!readVarint(buf, len, pos, field_len)) return false;
            if (field_len > UINT16_MAX || pos + field_len > len) return false;
            if (field == wanted_field) {
                out_data = buf + pos;
                out_len = static_cast<uint16_t>(field_len);
                return true;
            }
            pos += static_cast<uint16_t>(field_len);
        } else {
            if (!skipField(buf, len, pos, wire)) return false;
        }
    }
    return false;
}

static bool readVarintField(const uint8_t* buf, uint16_t len,
                            uint32_t wanted_field, uint32_t& out_value) {
    uint16_t pos = 0;
    while (pos < len) {
        uint32_t tag;
        if (!readVarint(buf, len, pos, tag)) return false;
        uint8_t wire = tag & 0x07;
        uint32_t field = tag >> 3;
        if (wire == 0) {
            uint32_t value;
            if (!readVarint(buf, len, pos, value)) return false;
            if (field == wanted_field) {
                out_value = value;
                return true;
            }
        } else {
            if (!skipField(buf, len, pos, wire)) return false;
        }
    }
    return false;
}

static bool extractDataPayload(const uint8_t* data_pb, uint16_t data_len,
                               const uint8_t*& payload, uint16_t& payload_len) {
    return findLengthDelimitedField(data_pb, data_len, 2, payload, payload_len);
}

static bool readPositionRequestTarget(const uint8_t* data_pb, uint16_t data_len,
                                      uint32_t& target_node_id) {
    const uint8_t* inner = nullptr;
    uint16_t inner_len = 0;
    if (!extractDataPayload(data_pb, data_len, inner, inner_len)) return false;
    return readVarintField(inner, inner_len, 1, target_node_id);
}

// Returns true if the inner PlayerStatePacket has is_state_transition = true.
// On any parse failure (malformed proto, missing field), defaults to true so
// that a state transition is never silently sent BEST_EFFORT (§8.9 fail-safe).
static bool airtakPlayerIsStateTransition(const uint8_t* data_pb, uint16_t data_len) {
    const uint8_t* inner = nullptr;
    uint16_t inner_len = 0;
    if (!extractDataPayload(data_pb, data_len, inner, inner_len)) return true;
    uint32_t value = 0;
    if (!readVarintField(inner, inner_len, 7, value)) return true;
    return value != 0;
}

// Same fail-safe for the raw PlayerStatePacket payload path (sendPacket).
static bool playerPayloadIsStateTransition(const uint8_t* payload, uint16_t payload_len) {
    uint32_t value = 0;
    if (!readVarintField(payload, payload_len, 7, value)) return true;
    return value != 0;
}

static bool parseDataMetadata(const uint8_t* data, uint16_t data_len, DataMetadata& out) {
    uint16_t pos = 0;
    bool saw_portnum = false;
    while (pos < data_len) {
        uint32_t tag;
        if (!readVarint(data, data_len, pos, tag)) return false;
        uint8_t wire = tag & 0x07;
        uint32_t field = tag >> 3;
        if (wire == 0) {
            uint32_t value;
            if (!readVarint(data, data_len, pos, value)) return false;
            switch (field) {
                case 1:
                    out.portnum = static_cast<Portnum>(value);
                    saw_portnum = true;
                    break;
                case 3:
                    out.want_response = (value != 0);
                    break;
                case 4:
                    out.dest = (value == 0) ? 0xFFFFFFFF : value;
                    break;
                case 6:
                    out.request_id = value;
                    out.has_request_id = true;
                    break;
                default:
                    break;
            }
        } else {
            if (!skipField(data, data_len, pos, wire)) return false;
        }
    }
    return saw_portnum;
}

static bool buildDataMessage(Portnum portnum, uint32_t dest, uint32_t src,
                             const uint8_t* app_payload, uint16_t app_payload_len,
                             bool want_ack, uint32_t request_id,
                             uint8_t* out, uint16_t& out_len, uint16_t max_len) {
    uint16_t pos = 0;
    if (!appendTag(out, pos, max_len, 1, 0)) return false;
    if (!appendVarU32(out, pos, max_len, static_cast<uint32_t>(portnum))) return false;
    if (app_payload_len > 0 && !appendBytes(out, pos, max_len, 2, app_payload, app_payload_len)) return false;
    if (want_ack) {
        if (!appendTag(out, pos, max_len, 3, 0)) return false;
        if (!appendVarU32(out, pos, max_len, 1)) return false;
    }
    if (dest != 0xFFFFFFFF) {
        if (!appendTag(out, pos, max_len, 4, 0)) return false;
        if (!appendVarU32(out, pos, max_len, dest)) return false;
    }
    if (!appendTag(out, pos, max_len, 5, 0)) return false;
    if (!appendVarU32(out, pos, max_len, src)) return false;
    if (want_ack) {
        if (!appendTag(out, pos, max_len, 6, 0)) return false;
        if (!appendVarU32(out, pos, max_len, request_id)) return false;
    }
    out_len = pos;
    return true;
}

static bool slotUsableForTx(const ChannelSlot* slot, bool require_downlink) {
    return slot && slot->role != ChannelRole::DISABLED &&
           (!require_downlink || slot->downlink_enabled);
}

static const ChannelSlot* selectTxSlot(Portnum portnum, bool require_downlink) {
    const ChannelSlot* primary = ChannelManager::primarySlot();

    if (portnum == Portnum::AIRTAK_AUDIO) {
        const ChannelSlot* audio = ChannelManager::slotByIndex(static_cast<uint8_t>(defaults::AUDIO_CHANNEL_SLOT));
        if (slotUsableForTx(audio, require_downlink)) return audio;
    }

    bool private_airtak = isAirTAKPortnum(portnum) &&
                          portnum != Portnum::AIRTAK_ACK &&
                          portnum != Portnum::AIRTAK_AUDIO;
    if (private_airtak) {
        // Prefer an encrypted, non-primary, non-audio slot for game traffic.
        for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
            if (i == static_cast<uint8_t>(defaults::AUDIO_CHANNEL_SLOT)) continue;
            const ChannelSlot* slot = ChannelManager::slotByIndex(i);
            if (slot == primary) continue;
            if (slotUsableForTx(slot, require_downlink) && slot->psk_len > 0) {
                return slot;
            }
        }
        // No dedicated encrypted slot — use the primary slot if it is
        // encrypted.  Encryption policy for game traffic is enforced at
        // config-validation time (BLE layer); here we make a best-effort
        // selection and let the caller handle a nullptr return.
        if (slotUsableForTx(primary, require_downlink) && primary->psk_len > 0)
            return primary;
        // Primary is unencrypted — fall through to the generic path below
        // so the packet is still transmitted (the config validator should
        // have prevented this scenario).
    }

    // Non-private (Meshtastic-standard) portnums: prefer the primary slot.
    if (slotUsableForTx(primary, require_downlink)) return primary;

    // Last resort: any usable slot.
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        const ChannelSlot* slot = ChannelManager::slotByIndex(i);
        if (slotUsableForTx(slot, require_downlink)) return slot;
    }
    return nullptr;
}

static bool isAppDuplicate(uint32_t src_id, uint32_t request_id, uint32_t now_ms) {
    if (request_id == 0) return false;
    for (auto& entry : s_app_dedup) {
        // Signed-difference expiry check is safe across millis() wrap
        // (TTL = 60 s, well under the ~24.8 day safe window).
        if (entry.in_use && (int32_t)(now_ms - entry.expires_ms) >= 0) {
            entry.in_use = false;
            continue;
        }
        if (entry.in_use && entry.src_id == src_id && entry.request_id == request_id) {
            return true;
        }
    }
    for (auto& entry : s_app_dedup) {
        if (!entry.in_use) {
            entry = {src_id, request_id, now_ms + defaults::ROUTER_DUP_CACHE_TTL_MS, true};
            return false;
        }
    }
    s_app_dedup[0] = {src_id, request_id, now_ms + defaults::ROUTER_DUP_CACHE_TTL_MS, true};
    return false;
}

static void queueDeliveryFailure(uint32_t portnum, uint32_t msg_id) {
    char error[96];
    snprintf(error, sizeof(error), "delivery_failed port=%lu request_id=%lu",
             static_cast<unsigned long>(portnum),
             static_cast<unsigned long>(msg_id));
    ble::queueAdminError(error);
}

static bool isPacketAddressedToMe(const OtaHeader& hdr, const DataMetadata& data_meta) {
    uint32_t my_id = MeshNode::nodeId();
    bool ota_ok = (hdr.dest_node_id == my_id || hdr.dest_node_id == 0xFFFFFFFF);
    bool data_ok = (data_meta.dest == my_id || data_meta.dest == 0xFFFFFFFF);
    return ota_ok && data_ok;
}

// ── Router::init() ────────────────────────────────────────────────────────────
void Router::init() {
    memset(s_dup_cache,   0, sizeof(s_dup_cache));
    memset(s_retry_queue, 0, sizeof(s_retry_queue));
    memset(s_relay_queue, 0, sizeof(s_relay_queue));
    memset(s_app_dedup,   0, sizeof(s_app_dedup));
    // Seed packet ID with a pseudorandom value derived from millis() to reduce
    // collisions with other nodes that also boot close in time.
    s_next_packet_id = (millis() & 0xFFFF) | (MeshNode::nodeId() << 8);
}

// ── buildOtaHeader() ─────────────────────────────────────────────────────────
// Serialize the §8.2 OTA wire header into buf[0..15].
static void buildOtaHeader(uint8_t* buf, const OtaHeader& hdr) {
    memcpy(buf + 0, &hdr.dest_node_id, 4);
    memcpy(buf + 4, &hdr.src_node_id,  4);
    memcpy(buf + 8, &hdr.packet_id, 4);
    buf[12] = hdr.flags;
    buf[13] = hdr.channel_hash;
    buf[14] = hdr.next_hop_low;
    buf[15] = hdr.relay_node_low;
}

// ── Router::processIncoming() ─────────────────────────────────────────────────
void Router::processIncoming(const uint8_t* raw, uint8_t raw_len,
                             float rssi, float snr,
                             QueueHandle_t tx_queue) {
    if (raw_len < OTA_HEADER_BYTES) return;

    DcTracker::tick();

    // Parse OTA header (§8.2).
    OtaHeader hdr;
    memcpy(&hdr.dest_node_id, raw + 0, 4);
    memcpy(&hdr.src_node_id,  raw + 4, 4);
    memcpy(&hdr.packet_id, raw + 8, 4);
    hdr.flags          = raw[12];
    hdr.channel_hash   = raw[13];
    hdr.next_hop_low   = raw[14];
    hdr.relay_node_low = raw[15];

    const uint8_t* payload     = raw + OTA_HEADER_BYTES;
    uint8_t        payload_len = raw_len - OTA_HEADER_BYTES;

    if (payload_len > MESH_MAX_PAYLOAD_BYTES) return;

    // Find matching channel by hash (§8.6). Hash collisions are possible, so try
    // every active slot with this hash and accept the one that decrypts to Data.
    const ChannelSlot* slot = nullptr;
    uint8_t decrypted[MESH_MAX_PAYLOAD_BYTES];
    DataMetadata data_meta;
    bool data_valid = false;
    uint8_t search_index = 0;
    while (const ChannelSlot* candidate = ChannelManager::findByHash(hdr.channel_hash, search_index)) {
        search_index = candidate->index + 1;
        memcpy(decrypted, payload, payload_len);

        if (candidate->psk_len > 0) {
            uint8_t nonce[AES_NONCE_BYTES];
            Crypto::buildNonce(hdr.packet_id, hdr.src_node_id, nonce);
            Crypto::crypt(candidate->aes_key, nonce, decrypted, payload_len);
        }

        DataMetadata candidate_meta;
        if (parseDataMetadata(decrypted, payload_len, candidate_meta)) {
            slot = candidate;
            data_meta = candidate_meta;
            data_valid = true;
            break;
        }
    }
    if (!slot) return; // unknown channel or undecryptable payload — discard

    uint32_t dup_key = hdr.packet_id;
    uint32_t now_ms  = millis();

    uint32_t portnum_value = data_valid ? static_cast<uint32_t>(data_meta.portnum) : 0;

    // Implicit ACK: if we overhear a relay of our own ACKNOWLEDGED packet,
    // cancel the pending retry for the logical Data.request_id (§8.9).
    if (hdr.src_node_id == MeshNode::nodeId()) {
        if (data_valid && data_meta.request_id != 0) {
            handleAck(data_meta.request_id);
        }
        return;
    }

    // Check duplicate-suppression cache (§8.7).
    // Signed-difference comparison is safe across millis() wrap for 60 s TTL.
    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        auto& e = s_dup_cache[i];
        if (!e.in_use) continue;
        if ((int32_t)(now_ms - e.expires_ms) >= 0) {
            e.in_use = false; // expired
            continue;
        }
        if (e.src_id == hdr.src_node_id && e.packet_id == dup_key) {
            // Duplicate: attempt heard-by relay suppression (§8.7).
            handleOverheardRelay(hdr.src_node_id, dup_key, snr);
            return;
        }
    }

    // Add to duplicate-suppression cache.  If full, evict the entry with the
    // earliest expiry (FIFO) so the most recent traffic stays suppressed.
    {
        size_t slot_idx = defaults::ROUTER_DUP_CACHE_SIZE;
        uint32_t earliest_ms = UINT32_MAX;
        size_t earliest_idx = 0;
        for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
            if (!s_dup_cache[i].in_use) { slot_idx = i; break; }
            if (s_dup_cache[i].expires_ms < earliest_ms) {
                earliest_ms = s_dup_cache[i].expires_ms;
                earliest_idx = i;
            }
        }
        if (slot_idx == defaults::ROUTER_DUP_CACHE_SIZE)
            slot_idx = earliest_idx;
        s_dup_cache[slot_idx] = { hdr.src_node_id, dup_key,
                                  now_ms + defaults::ROUTER_DUP_CACHE_TTL_MS,
                                  snr, true };
    }

    bool app_duplicate = false;
    if (data_valid && data_meta.want_response && data_meta.request_id != 0 &&
        isAcknowledgedPortnum(data_meta.portnum)) {
        app_duplicate = isAppDuplicate(hdr.src_node_id, data_meta.request_id, now_ms);
    }

    // Deliver to BLE MESH_RX if uplink is enabled for this slot AND this packet
    // is addressed to us (OTA dest is broadcast or matches us; Data.dest is broadcast or matches us).
    // Without this check, intermediate relay nodes would deliver unicast packets to their BLE
    // client even when the packet is destined for another node (§8.7, §8.9).
    // The BLE payload is: portnum hint byte + 16-byte OTA header + decrypted Data protobuf.
    if (slot->uplink_enabled && data_valid && isPacketAddressedToMe(hdr, data_meta)
        && !app_duplicate && ::g_ble_tx_queue) {
        // Prefix an internal queue tag so BLEServer routes the packet to MESH_RX.
        PooledPacket* bpkt = PacketPool::alloc();
        if (bpkt) {
            bpkt->data[0] = ble::BLE_QUEUE_MESH_RX;
            bpkt->data[1] = static_cast<uint8_t>(portnum_value & 0xFF);
            memcpy(bpkt->data + 2, raw, OTA_HEADER_BYTES);
            memcpy(bpkt->data + 2 + OTA_HEADER_BYTES, decrypted, payload_len);
            bpkt->length = 2 + OTA_HEADER_BYTES + payload_len;
            if (xQueueSendToBack(::g_ble_tx_queue, &bpkt, 0) != pdTRUE) {
                PacketPool::free(bpkt);
            }
        }
    }

    // ── NODEINFO_APP dispatch (§2.2) ──────────────────────────────────────────
    // Track remote node IDs so knownNodeCount() stays accurate for the display.
    if (!app_duplicate && portnum_value == static_cast<uint32_t>(Portnum::NODEINFO_APP)) {
        MeshNode::onNodeInfoReceived(hdr.src_node_id);
    }

    // ── AIRTAK_POSITION_REQUEST dispatch (§2.12) ──────────────────────────────
    // When we receive a PositionRequestPacket addressed to us,
    // respond with a unicast POSITION_APP within 2 s, bypassing the beaconing timer.
    if (!app_duplicate && portnum_value == static_cast<uint32_t>(Portnum::AIRTAK_POSITION_REQUEST)) {
        uint32_t target_node_id = 0;
        if (readPositionRequestTarget(decrypted, payload_len, target_node_id) &&
            target_node_id == MeshNode::nodeId()) {
            MeshNode::handlePositionRequest(hdr.src_node_id, tx_queue);
        }
        // AIRTAK_POSITION_REQUEST is BEST_EFFORT; still relay below if hop_limit > 0.
    }

    // ── AIRTAK_ACK receive: cancel pending retry queue entry (§8.9) ───────────
    // Correlation is via Data.request_id (field 6, tag 0x30) which the ACK sender
    // sets to the ack_request_id so the original sender can match it.
    // Only process if the ACK is addressed to us (OTA dest is us or broadcast).
    if (portnum_value == static_cast<uint32_t>(Portnum::AIRTAK_ACK)) {
        if (data_meta.request_id != 0 && isPacketAddressedToMe(hdr, data_meta)) {
            handleAck(data_meta.request_id);
        }
        // Still relay AIRTAK_ACK so it can reach back to the original sender.
    }

    // ── Generate AIRTAK_ACK when sender requested acknowledgment (§8.9) ───────
    // Never ACK a AIRTAK_ACK itself (prevents infinite ACK loops).
    // Only ACK if this packet is addressed to us — intermediate relays must not
    // ACK unicast packets destined for another node (§8.9).
    if (hdr.wantAck() && data_valid && isPacketAddressedToMe(hdr, data_meta)
        && portnum_value != static_cast<uint32_t>(Portnum::AIRTAK_ACK)) {
        uint32_t req_id = data_meta.request_id;
        if (req_id != 0) {
            // Build Data proto bytes for AIRTAK_ACK (portnum 261 = 0x85 0x02 as varint).
            uint8_t ack_data[64]; uint8_t ap = 0;
            ack_data[ap++] = 0x08; ack_data[ap++] = 0x85; ack_data[ap++] = 0x02; // portnum=261
            // Build inner AirTAKAck sub-message.
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
            ack_hdr.packet_id     = ack_pkt_id;
            ack_hdr.flags         = ((ack_hop & 0x07) << 5) | (ack_hop & 0x07); // no want_ack on ACK
            ack_hdr.channel_hash  = slot->channel_hash;
            ack_hdr.next_hop_low  = 0;
            ack_hdr.relay_node_low = 0;
            uint8_t ack_enc[64];
            memcpy(ack_enc, ack_data, ap);
            if (slot->psk_len > 0) {
                uint8_t nonce2[AES_NONCE_BYTES];
                Crypto::buildNonce(ack_hdr.packet_id, ack_hdr.src_node_id, nonce2);
                Crypto::crypt(slot->aes_key, nonce2, ack_enc, ap);
            }
            PooledPacket* ack_pkt = PacketPool::alloc();
            if (ack_pkt) {
                buildOtaHeader(ack_pkt->data, ack_hdr);
                memcpy(ack_pkt->data + OTA_HEADER_BYTES, ack_enc, ap);
                ack_pkt->length = OTA_HEADER_BYTES + ap;
                ack_pkt->phy_profile_index = slot->phy_profile_index;
                if (xQueueSendToBack(tx_queue, &ack_pkt, 0) != pdTRUE) {
                    PacketPool::free(ack_pkt);
                } else {
                    xEventGroupSetBits(::g_radio_event_group, RADIO_EVENT_TX_READY);
                }
            }
        }
    }

    if (hdr.hopLimit() > 0) {
        // Check portnum to block audio relay (already parsed above as portnum_value).
        bool is_audio = (portnum_value == static_cast<uint32_t>(Portnum::AIRTAK_AUDIO));

        if (!is_audio) {
            // SNR-based relay delay: delay_ms = (SNR_max - SNR_received) × 50 + random(0, 50) (§8.7).
            // SNR_max varies by PHY profile (higher SF tolerates lower SNR).
            float snr_max = defaults::PHY_SNR_MAX[0]; // fallback: Long/Mesh
            if (slot->phy_profile_index < 5) {
                snr_max = defaults::PHY_SNR_MAX[slot->phy_profile_index];
            }
            uint32_t delay_ms = (uint32_t)(max(0.0f, snr_max - snr) * 50.0f)
                                + (uint32_t)(random(0, 50));

            // Build relay packet: decrement hop_limit, preserve hop_start.
            uint8_t relay_buf[LORA_MAX_PAYLOAD_BYTES];
            OtaHeader relay_hdr = hdr;
            relay_hdr.setHopLimit(hdr.hopLimit() - 1);
            relay_hdr.next_hop_low = 0; // cleared by relay node
            relay_hdr.relay_node_low = static_cast<uint8_t>(MeshNode::nodeId() & 0xFF);
            buildOtaHeader(relay_buf, relay_hdr);
            // Re-encrypt with the original (still-encrypted) payload for relay.
            memcpy(relay_buf + OTA_HEADER_BYTES, payload, payload_len);

            // DC-aware relay suppression gate (§8.7.1, Roadmap 6.10).
            // Applied after heard-by suppression: only packets that survive
            // heard-by cancellation reach this gate. Source traffic never
            // reaches here (returned at self-origin check above).
            {
                DcLevel dc_level = DcTracker::currentLevel();
                if (dc_level >= DcLevel::DC_WARNING) {
                    bool is_tier1 = DcTracker::isTier1Relay(
                        static_cast<Portnum>(portnum_value));
                    if (portnum_value == static_cast<uint32_t>(Portnum::AIRTAK_PLAYER)) {
                        // Promote state transitions to Tier 1; heartbeats stay Tier 2.
                        is_tier1 = airtakPlayerIsStateTransition(decrypted, payload_len);
                    }
                    // Tier 1: relayed at all levels (including CRITICAL, per spec decision flow).
                    // Tier 2: dropped at WARNING and above.
                    if (!is_tier1) {
                        DcTracker::incrementDroppedRelays();
                        return;
                    }
                }
            }

            // Add to pending relay queue. If full, evict the entry with the earliest
            // send_after_ms (oldest queued relay) so the most recent packet — likely
            // more relevant — stays queued.
            {
                size_t target = 16;
                for (size_t j = 0; j < 16; j++) {
                    if (!s_relay_queue[j].in_use) { target = j; break; }
                }
                if (target == 16) {
                    uint32_t earliest = UINT32_MAX;
                    for (size_t j = 0; j < 16; j++) {
                        if (s_relay_queue[j].send_after_ms < earliest) {
                            earliest = s_relay_queue[j].send_after_ms;
                            target = j;
                        }
                    }
                }
                uint8_t relay_len = OTA_HEADER_BYTES + payload_len;
                memcpy(s_relay_queue[target].data, relay_buf, relay_len);
                s_relay_queue[target].length       = relay_len;
                s_relay_queue[target].send_after_ms = now_ms + delay_ms;
                s_relay_queue[target].rx_snr        = snr;
                s_relay_queue[target].phy_profile_index = slot->phy_profile_index;
                s_relay_queue[target].in_use        = true;
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
        // Signed-difference comparison is safe across millis() wrap for
        // deadlines up to ~24.8 days (all relay delays are < 10 s).
        if ((int32_t)(now_ms - e.send_after_ms) < 0) continue;

        // Queue for radio TX.
        PooledPacket* pkt = PacketPool::alloc();
        if (pkt) {
            memcpy(pkt->data, e.data, e.length);
            pkt->length = e.length;
            pkt->phy_profile_index = e.phy_profile_index;
            if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
                PacketPool::free(pkt);
            } else {
                xEventGroupSetBits(::g_radio_event_group, RADIO_EVENT_TX_READY);
            }
        }
        e.in_use = false;
    }
}

static bool queueOtaFrame(const ChannelSlot* slot, uint32_t dest, uint8_t hop_limit,
                          bool want_ack, uint32_t packet_id,
                          const uint8_t* data_pb, uint16_t data_len,
                          QueueHandle_t tx_queue) {
    if (!slot || !tx_queue || data_len > MESH_MAX_PAYLOAD_BYTES) return false;

    // For the originator, hop_limit == hop_start (§8.2).  Both are set to the
    // same value here; the relay path in processIncoming() decrements hop_limit
    // while preserving hop_start so the ACK receiver can mirror it.
    OtaHeader hdr;
    hdr.dest_node_id  = dest;
    hdr.src_node_id   = MeshNode::nodeId();
    hdr.packet_id     = packet_id;
    hdr.flags         = ((hop_limit & 0x07) << 5)   // [7:5] hop_limit
                      | (want_ack ? (1 << 4) : 0)    // [4]   want_ack
                      | (hop_limit & 0x07);           // [2:0] hop_start (= hop_limit for origin)
    hdr.channel_hash  = slot->channel_hash;
    hdr.next_hop_low  = 0;
    hdr.relay_node_low = 0;

    uint8_t enc[MESH_MAX_PAYLOAD_BYTES];
    memcpy(enc, data_pb, data_len);
    if (slot->psk_len > 0) {
        uint8_t nonce[16];
        Crypto::buildNonce(hdr.packet_id, hdr.src_node_id, nonce);
        Crypto::crypt(slot->aes_key, nonce, enc, data_len);
    }

    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return false;
    buildOtaHeader(pkt->data, hdr);
    memcpy(pkt->data + OTA_HEADER_BYTES, enc, data_len);
    pkt->length = OTA_HEADER_BYTES + data_len;
    pkt->phy_profile_index = slot->phy_profile_index;

    if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
        PacketPool::free(pkt);
        return false;
    }
    xEventGroupSetBits(::g_radio_event_group, RADIO_EVENT_TX_READY);
    return true;
}

static void addRetryEntry(Portnum portnum, uint32_t dest, uint32_t msg_id,
                          uint8_t slot_index,
                          uint8_t hop_start, const uint8_t* data_pb,
                          uint16_t data_len) {
    if (!Router::isAcknowledgedPortnum(portnum) || data_len > sizeof(s_retry_queue[0].payload)) return;

    uint32_t now_ms = millis();

    size_t slot_idx = defaults::ACK_RETRY_QUEUE_SIZE;
    uint32_t oldest_created = UINT32_MAX;
    size_t oldest_idx  = 0;
    for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
        if (!s_retry_queue[i].in_use) { slot_idx = i; break; }
        // Evict by original creation time, NOT timeout_ms.  timeout_ms is the
        // backoff deadline during the in_backoff phase (0–200 ms ahead), which
        // would cause backoff-phase entries to be evicted preferentially over
        // entries still awaiting their first ACK (timeout up to 5600 ms).
        // Using created_ms gives true FIFO eviction regardless of phase.
        if (s_retry_queue[i].created_ms < oldest_created) {
            oldest_created = s_retry_queue[i].created_ms;
            oldest_idx = i;
        }
    }
    if (slot_idx == defaults::ACK_RETRY_QUEUE_SIZE) slot_idx = oldest_idx;

    uint32_t timeout = max(defaults::ACK_TIMEOUT_MIN_MS,
                           2u * static_cast<uint32_t>(hop_start) * defaults::ACK_TIMEOUT_PER_HOP_MS);
    s_retry_queue[slot_idx] = {
        msg_id,
        dest,
        now_ms + timeout,
        now_ms,   // created_ms — FIFO eviction anchor
        0,
        hop_start,
        slot_index,
        static_cast<uint32_t>(portnum),
        {},
        data_len,
        true,
        false     // in_backoff
    };
    memcpy(s_retry_queue[slot_idx].payload, data_pb, data_len);
}

// ── Router::sendPacket() ─────────────────────────────────────────────────────
void Router::sendPacket(Portnum portnum, uint32_t dest,
                        const uint8_t* payload, uint16_t payload_len,
                        bool want_ack, QueueHandle_t tx_queue) {
    const ChannelSlot* slot = selectTxSlot(portnum, false);
    if (!slot) return;

    uint8_t hop_limit = defaultHopLimit(portnum);
    uint32_t packet_id = nextPacketId();
    uint32_t msg_id = packet_id;

    if (portnum == Portnum::AIRTAK_PLAYER) {
        want_ack = playerPayloadIsStateTransition(payload, payload_len);
    }

    uint8_t data_pb[MESH_MAX_PAYLOAD_BYTES];
    uint16_t data_len = 0;
    if (!buildDataMessage(portnum, dest, MeshNode::nodeId(), payload, payload_len,
                          want_ack, msg_id, data_pb, data_len, sizeof(data_pb))) {
        return;
    }

    if (!queueOtaFrame(slot, dest, hop_limit, want_ack, packet_id,
                       data_pb, data_len, tx_queue)) {
        return;
    }

    if (want_ack && isAcknowledgedPortnum(portnum)) {
        addRetryEntry(portnum, dest, msg_id, slot->index, hop_limit, data_pb, data_len);
    }
}

// ── Router::sendDataPacket() ────────────────────────────────────────────────
bool Router::sendDataPacket(const uint8_t* data_pb, uint16_t data_len,
                            QueueHandle_t tx_queue) {
    // Accept up to MESH_MAX_PAYLOAD_BYTES + 1 here because a slot-override
    // prefix byte (stripped below) may be present. The size is re-checked
    // against MESH_MAX_PAYLOAD_BYTES after any prefix is removed.
    if (!data_pb || data_len == 0 || data_len > MESH_MAX_PAYLOAD_BYTES + 1) return false;

    // Slot-override prefix (app → firmware protocol extension, §10.3):
    // If byte[0] is in the range [0, MAX_CHANNEL_SLOTS), the app is requesting
    // a specific channel slot for this packet; the actual Data proto starts at
    // byte[1]. This is safe because valid proto3 field tags always have field
    // number ≥ 1, so the minimum valid first byte of a Data proto is 0x08
    // (field 1, wire type 0). Bytes 0x00–0x07 are therefore unambiguous slot
    // indices and the protocol is backward-compatible with older firmware.
    const ChannelSlot* override_slot = nullptr;
    if (data_pb[0] < MAX_CHANNEL_SLOTS) {
        uint8_t slot_idx = data_pb[0];
        data_pb  += 1;
        data_len -= 1;
        if (data_len == 0) return false;
        const ChannelSlot* candidate = ChannelManager::slotByIndex(slot_idx);
        if (candidate && candidate->role != ChannelRole::DISABLED) {
            override_slot = candidate;
        }
        // If the requested slot is disabled or absent, fall through to normal
        // auto-selection so the packet is not silently dropped.
    }

    // Enforce the per-Data-proto size limit now that any prefix has been stripped.
    if (data_len > MESH_MAX_PAYLOAD_BYTES) return false;

    DataMetadata meta;
    if (!parseDataMetadata(data_pb, data_len, meta)) return false;

    const ChannelSlot* slot = override_slot ? override_slot : selectTxSlot(meta.portnum, true);
    if (!slot) return false;

    // Determine whether this packet requires ACKNOWLEDGED delivery.
    // The authoritative sources are the per-portnum rules below and the
    // is_state_transition field for AIRTAK_PLAYER.  The incoming Data proto's
    // want_response field is advisory only: the firmware strips it when the
    // portnum-specific rule overrides to BEST_EFFORT (A1, A5 fix — see audit.md).
    bool want_ack = false;
    switch (meta.portnum) {
        case Portnum::TEXT_MESSAGE_APP:
            want_ack = meta.want_response || (meta.dest != 0xFFFFFFFF);
            break;
        case Portnum::AIRTAK_TACTICAL:
        case Portnum::AIRTAK_MODERATION:
            want_ack = true;
            break;
        case Portnum::AIRTAK_PLAYER:
            want_ack = airtakPlayerIsStateTransition(data_pb, data_len);
            break;
        default:
            want_ack = meta.want_response;
            break;
    }

    // Extract the inner app payload from the incoming Data proto so that
    // buildDataMessage can re-encode a clean Data protobuf.  Rebuilding
    // avoids duplicate fields and guarantees that want_response only
    // appears when the firmware's authoritative want_ack is true.
    const uint8_t* app_payload = nullptr;
    uint16_t app_payload_len = 0;
    extractDataPayload(data_pb, data_len, app_payload, app_payload_len);

    uint32_t packet_id = nextPacketId();
    uint32_t msg_id = meta.has_request_id ? meta.request_id : packet_id;

    uint8_t outgoing[MESH_MAX_PAYLOAD_BYTES];
    uint16_t outgoing_len = 0;
    if (!buildDataMessage(meta.portnum, meta.dest, MeshNode::nodeId(),
                          app_payload, app_payload_len,
                          want_ack, msg_id,
                          outgoing, outgoing_len, sizeof(outgoing))) {
        return false;
    }

    uint8_t hop_limit = defaultHopLimit(meta.portnum);
    if (!queueOtaFrame(slot, meta.dest, hop_limit, want_ack, packet_id,
                       outgoing, outgoing_len, tx_queue)) {
        return false;
    }

    if (want_ack && isAcknowledgedPortnum(meta.portnum)) {
        addRetryEntry(meta.portnum, meta.dest, msg_id, slot->index, hop_limit, outgoing, outgoing_len);
    }
    return true;
}

// ── Helper: retransmit one entry with a new packet_id (§8.9) ────────────────
// Returns true if the retransmission was queued successfully.
static bool retransmitEntry(AckRetryEntry& e, QueueHandle_t tx_queue, uint32_t now_ms) {
    uint32_t new_packet_id = nextPacketId();
    const ChannelSlot* slot = ChannelManager::slotByIndex(e.slot_index);
    if (!slot || slot->role == ChannelRole::DISABLED) slot = ChannelManager::primarySlot();
    if (!slot) return false;

    OtaHeader hdr;
    hdr.dest_node_id  = e.dest_node_id;
    hdr.src_node_id   = MeshNode::nodeId();
    hdr.packet_id     = new_packet_id;
    hdr.flags         = ((e.hop_start & 0x07) << 5)
                      | (1 << 4)           // want_ack=true
                      | (e.hop_start & 0x07);
    hdr.channel_hash  = slot->channel_hash;
    hdr.next_hop_low  = 0;
    hdr.relay_node_low = 0;

    uint8_t enc[MESH_MAX_PAYLOAD_BYTES];
    memcpy(enc, e.payload, e.payload_len);
    if (slot->psk_len > 0) {
        uint8_t nonce[16];
        Crypto::buildNonce(hdr.packet_id, MeshNode::nodeId(), nonce);
        Crypto::crypt(slot->aes_key, nonce, enc, e.payload_len);
    }

    PooledPacket* pkt = PacketPool::alloc();
    if (pkt) {
        buildOtaHeader(pkt->data, hdr);
        memcpy(pkt->data + OTA_HEADER_BYTES, enc, e.payload_len);
        pkt->length = OTA_HEADER_BYTES + e.payload_len;
        pkt->phy_profile_index = slot->phy_profile_index;
        if (xQueueSendToBack(tx_queue, &pkt, 0) != pdTRUE) {
            PacketPool::free(pkt);
        } else {
            xEventGroupSetBits(::g_radio_event_group, RADIO_EVENT_TX_READY);
        }
    }

    e.attempts++;
    uint32_t ack_timeout = max(defaults::ACK_TIMEOUT_MIN_MS,
                               2u * (uint32_t)e.hop_start * defaults::ACK_TIMEOUT_PER_HOP_MS);
    e.timeout_ms = now_ms + ack_timeout;
    return true;
}

// ── Router::tickRetryQueue() ─────────────────────────────────────────────────
void Router::tickRetryQueue(QueueHandle_t tx_queue) {
    uint32_t now_ms = millis();
    for (size_t i = 0; i < defaults::ACK_RETRY_QUEUE_SIZE; i++) {
        auto& e = s_retry_queue[i];
        if (!e.in_use) continue;

        // Use signed-difference comparison so the deadline check is correct
        // across the ~49.7-day millis() wrap (all deadlines are < 25 days).
        if ((int32_t)(now_ms - e.timeout_ms) < 0) continue;

        // ── Backoff phase: delay expired → retransmit now ─────────────────
        if (e.in_backoff) {
            e.in_backoff = false;
            if (!retransmitEntry(e, tx_queue, now_ms)) {
                e.in_use = false; // no usable TX slot — abandon retry
            }
            continue;
        }

        // ── ACK timeout phase: no ACK received → backoff or give up ──────
        if (e.attempts >= defaults::ACK_MAX_RETRIES) {
            queueDeliveryFailure(e.portnum, e.msg_id);
            e.in_use = false;
            continue;
        }

        // Jittered exponential backoff BEFORE the next retry (§8.9).
        // Formula: random(0, 200 × 2^attempt).  attempt=0 → 0–200 ms.
        uint32_t backoff = (uint32_t)random(0,
            defaults::ACK_RETRY_BASE_MS * (1u << e.attempts));

        if (backoff == 0) {
            // Zero backoff — retransmit immediately without a backoff phase.
            if (!retransmitEntry(e, tx_queue, now_ms)) {
                e.in_use = false; // no usable TX slot — abandon retry
            }
            continue;
        }

        e.in_backoff = true;
        e.timeout_ms = now_ms + backoff;
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
    // Cancel a queued relay if we overheard another node relay the same packet
    // and its SNR was >= ours (they have a better path, §8.7).
    // Match on BOTH src_id AND the reconstructed dup_key so that two simultaneously
    // pending relays from the same source node are not incorrectly conflated.
    for (size_t i = 0; i < 16; i++) {
        auto& e = s_relay_queue[i];
        if (!e.in_use) continue;
        uint32_t relay_src;
        memcpy(&relay_src, e.data + 4, 4);
        if (relay_src != src_id) continue;
        uint32_t relay_dup_key;
        memcpy(&relay_dup_key, e.data + 8, 4);
        if (relay_dup_key == dup_key && overheard_snr >= e.rx_snr) {
            e.in_use = false;
        }
    }
}

// ── Router::clearPendingRelays() ─────────────────────────────────────────────
void Router::clearPendingRelays() {
    for (size_t i = 0; i < 16; i++) {
        s_relay_queue[i].in_use = false;
    }
}

// ── Router::defaultHopLimit() ────────────────────────────────────────────────
uint8_t Router::defaultHopLimit(Portnum portnum) {
    switch (portnum) {
        case Portnum::TEXT_MESSAGE_APP:    return defaults::HOP_LIMIT_TEXT_MESSAGE;
        case Portnum::POSITION_APP: {
            uint8_t override_hop = config::ConfigManager::positionHopLimitOverride();
            return override_hop == 0 ? defaults::HOP_LIMIT_POSITION : override_hop;
        }
        case Portnum::NODEINFO_APP:        return defaults::HOP_LIMIT_NODEINFO;
        case Portnum::TELEMETRY_APP:       return defaults::HOP_LIMIT_TELEMETRY;
        case Portnum::TRACEROUTE_APP:      return defaults::HOP_LIMIT_TRACEROUTE;
        case Portnum::NEIGHBORINFO_APP:    return defaults::HOP_LIMIT_NEIGHBORINFO;
        case Portnum::AIRTAK_PLAYER:         return defaults::HOP_LIMIT_AIRTAK_PLAYER;
        case Portnum::AIRTAK_TACTICAL:        return defaults::HOP_LIMIT_AIRTAK_TACTICAL;
        case Portnum::AIRTAK_MODERATION:      return defaults::HOP_LIMIT_AIRTAK_MODERATION;
        case Portnum::AIRTAK_ACK:            return defaults::HOP_LIMIT_AIRTAK_ACK;
        case Portnum::AIRTAK_POSITION_REQUEST: return defaults::HOP_LIMIT_POSITION_REQUEST;
        case Portnum::AIRTAK_AUDIO:          return defaults::HOP_LIMIT_AIRTAK_AUDIO;
        case Portnum::AIRTAK_SYNC_REQUEST:  return defaults::HOP_LIMIT_AIRTAK_SYNC_REQUEST;
        case Portnum::AIRTAK_OVERLAY_ALIGNMENT: return defaults::HOP_LIMIT_OVERLAY_ALIGNMENT;
        case Portnum::AIRTAK_MARKER_SNAPSHOT_REQUEST: return defaults::HOP_LIMIT_MARKER_SNAPSHOT;
        default:                           return 7;
    }
}

#ifdef AIRTAK_NATIVE_TEST
size_t Router::testPendingRelayCount() {
    size_t count = 0;
    for (const auto& entry : s_relay_queue) {
        if (entry.in_use) count++;
    }
    return count;
}

size_t Router::testRetryEntryCount() {
    size_t count = 0;
    for (const auto& entry : s_retry_queue) {
        if (entry.in_use) count++;
    }
    return count;
}

size_t Router::testAppDedupCount() {
    uint32_t now_ms = millis();
    size_t count = 0;
    for (const auto& entry : s_app_dedup) {
        if (entry.in_use && entry.expires_ms >= now_ms) count++;
    }
    return count;
}

bool Router::testIsPacketAddressedToMe(const OtaHeader& hdr,
                                       uint32_t data_dest) {
    DataMetadata meta;
    meta.dest = (data_dest == 0) ? 0xFFFFFFFF : data_dest;
    return isPacketAddressedToMe(hdr, meta);
}

bool Router::testFirstRelay(uint8_t* out, uint8_t& out_len,
                            uint32_t& send_after_ms, float& rx_snr) {
    for (const auto& entry : s_relay_queue) {
        if (!entry.in_use) continue;
        if (out) memcpy(out, entry.data, entry.length);
        out_len = entry.length;
        send_after_ms = entry.send_after_ms;
        rx_snr = entry.rx_snr;
        return true;
    }
    return false;
}

bool Router::testFirstRetry(uint32_t& msg_id, uint8_t& attempts,
                            uint32_t& timeout_ms, uint8_t& hop_start,
                            uint16_t& payload_len) {
    for (const auto& entry : s_retry_queue) {
        if (!entry.in_use) continue;
        msg_id = entry.msg_id;
        attempts = entry.attempts;
        timeout_ms = entry.timeout_ms;
        hop_start = entry.hop_start;
        payload_len = entry.payload_len;
        return true;
    }
    return false;
}

size_t Router::testDupCacheCount() {
    uint32_t now_ms = millis();
    size_t count = 0;
    for (size_t i = 0; i < defaults::ROUTER_DUP_CACHE_SIZE; i++) {
        if (s_dup_cache[i].in_use &&
            (int32_t)(now_ms - s_dup_cache[i].expires_ms) < 0) {
            count++;
        }
    }
    return count;
}
#endif

} // namespace airtak
