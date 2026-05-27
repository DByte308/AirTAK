// AirTAK — Airsoft Team Awareness Kit
// firmware/src/mesh/ChannelManager.cpp — Channel slot table implementation
// Planning: §8.5, §8.6 | Roadmap: 2.7

#include "mesh/ChannelManager.h"
#include "mesh/Crypto.h"
#include "config/ConfigManager.h"
#include "config/ConfigGroups.h"
#include "config/Defaults.h"
#include <cstring>

namespace airtak {

static ChannelSlot s_slots[MAX_CHANNEL_SLOTS];

static void seedDefaultSlots() {
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        const auto& def = defaults::CHANNEL_SLOTS[i];
        auto& slot = s_slots[i];
        slot.index              = def.index;
        strncpy(slot.name, def.name, 16);
        slot.name[16]           = '\0';
        slot.psk_len            = 0;
        memset(slot.psk, 0, sizeof(slot.psk));
        slot.role               = static_cast<ChannelRole>(def.role);
        slot.uplink_enabled     = def.uplink_enabled;
        slot.downlink_enabled   = def.downlink_enabled;
        slot.position_precision = def.position_precision;
        slot.phy_profile_index  = def.phy_profile_index;
        slot.channel_hash       = 0;
        memset(slot.aes_key, 0, sizeof(slot.aes_key));
        slot.aes_key_len        = 0;
    }
}

// ── parseChannelSlot() ───────────────────────────────────────────────────────
// Parse a single ChannelConfig sub-message (field layout matches encodeChannelConfig).
// Fields: 1=index(varint), 2=name(string), 3=psk(bytes), 4=role(varint),
//         5=uplink_enabled(varint), 6=downlink_enabled(varint),
//         7=position_precision(varint), 8=phy_profile_index(varint).
// Returns the slot index encoded in field 1, or 0xFF if not present or out of range.
static uint8_t parseChannelSlot(const uint8_t* sub, size_t sub_len, ChannelSlot& slot) {
    // First pass: locate the index field.
    uint8_t idx = 0xFF;
    size_t  sp  = 0;
    while (sp < sub_len) {
        uint32_t tag = 0; uint8_t shift = 0;
        while (sp < sub_len) { uint8_t b = sub[sp++]; tag |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
        uint8_t wire  = tag & 0x07;
        uint8_t field = (uint8_t)(tag >> 3);
        if (wire == 0 && field == 1) {
            uint32_t v = 0; shift = 0;
            while (sp < sub_len) { uint8_t b = sub[sp++]; v |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
            idx = (uint8_t)v;
        } else if (wire == 0) { while (sp < sub_len && (sub[sp++] & 0x80)) {} }
        else if (wire == 2) { uint32_t fl = 0; shift = 0; while (sp < sub_len) { uint8_t b = sub[sp++]; fl |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; } sp += fl; }
        else if (wire == 5) { sp += 4; }
        else if (wire == 1) { sp += 8; }
    }
    if (idx >= MAX_CHANNEL_SLOTS) return 0xFF;

    // Second pass: populate slot fields.
    sp = 0;
    while (sp < sub_len) {
        uint32_t tag = 0; uint8_t shift = 0;
        while (sp < sub_len) { uint8_t b = sub[sp++]; tag |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
        uint8_t wire  = tag & 0x07;
        uint8_t field = (uint8_t)(tag >> 3);
        if (wire == 0) {
            uint32_t v = 0; shift = 0;
            while (sp < sub_len) { uint8_t b = sub[sp++]; v |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
            switch (field) {
                case 4: slot.role               = static_cast<ChannelRole>(v); break;
                case 5: slot.uplink_enabled     = (v != 0); break;
                case 6: slot.downlink_enabled   = (v != 0); break;
                case 7: slot.position_precision = (uint8_t)v; break;
                case 8: slot.phy_profile_index  = (uint8_t)v; break;
                default: break;
            }
        } else if (wire == 2) {
            uint32_t flen = 0; shift = 0;
            while (sp < sub_len) { uint8_t b = sub[sp++]; flen |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
            if (field == 2 && flen > 0) { // name
                uint8_t cplen = (uint8_t)(flen < 16 ? flen : 16);
                memcpy(slot.name, sub + sp, cplen);
                slot.name[cplen] = '\0';
            } else if (field == 3 && flen > 0 && flen <= 32) { // psk
                memcpy(slot.psk, sub + sp, flen);
                slot.psk_len = (uint8_t)flen;
            }
            sp += flen;
        } else if (wire == 5) { sp += 4; }
        else if (wire == 1) { sp += 8; }
    }
    return idx;
}

static void loadConfiguredSlots() {
    seedDefaultSlots();

    // Load LongFast channel config from flash if available, otherwise from
    // ConfigManager.  If flash/config has valid data, overlay it on the defaults.
    uint8_t  buf[512];
    size_t   buf_len = config::ConfigManager::readGroup(
                           config::ConfigGroup::CHANNELS, buf, sizeof(buf));
    if (buf_len > 0) {
        size_t pos = 0;
        while (pos < buf_len) {
            uint32_t tag = 0; uint8_t shift = 0;
            while (pos < buf_len) { uint8_t b = buf[pos++]; tag |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
            uint8_t wire  = tag & 0x07;
            uint8_t field = (uint8_t)(tag >> 3);
            if (wire == 2 && field == 1) {
                uint32_t submsg_len = 0; shift = 0;
                while (pos < buf_len) { uint8_t b = buf[pos++]; submsg_len |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
                if (pos + submsg_len > buf_len) break;
                ChannelSlot parsed{};
                uint8_t idx = parseChannelSlot(buf + pos, submsg_len, parsed);
                if (idx < MAX_CHANNEL_SLOTS) {
                    parsed.index = idx;
                    s_slots[idx] = parsed;
                }
                pos += submsg_len;
            } else {
                if (wire == 0) { while (pos < buf_len && (buf[pos++] & 0x80)) {} }
                else if (wire == 2) { uint32_t fl = 0; shift = 0; while (pos < buf_len) { uint8_t b = buf[pos++]; fl |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; } pos += fl; }
                else if (wire == 5) { pos += 4; }
                else if (wire == 1) { pos += 8; }
            }
        }
    }

    // Force slot 0 to LongFast defaults for interop testing.
    {
        auto& s = s_slots[0];
        strncpy(s.name, "LongFast", 16);
        s.name[16] = '\0';
        static const uint8_t key[6] = {0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29};
        memcpy(s.psk, key, 6);
        s.psk_len = 6;
        s.role = ChannelRole::PRIMARY;
        s.uplink_enabled = true;
        s.downlink_enabled = true;
        s.position_precision = 32;
        s.phy_profile_index = 0;
    }
}

void ChannelManager::init() {
    rekey();
}

void ChannelManager::rekey() {
    loadConfiguredSlots();

    static const uint8_t MESHTASTIC_DEFAULT_PSK[6] = {
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29
    };

    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        auto& slot = s_slots[i];
        if (slot.role == ChannelRole::DISABLED) continue;

        if (slot.psk_len == 1 && slot.psk[0] == 0x01) {
            memcpy(slot.psk, MESHTASTIC_DEFAULT_PSK, sizeof(MESHTASTIC_DEFAULT_PSK));
            slot.psk_len = sizeof(MESHTASTIC_DEFAULT_PSK);
        }

        if (slot.psk_len == 0) {
            slot.channel_hash = Crypto::channelHash(
                reinterpret_cast<const uint8_t*>(slot.name),
                strnlen(slot.name, sizeof(slot.name)), i);
            memset(slot.aes_key, 0, sizeof(slot.aes_key));
            slot.aes_key_len = 16;
            continue;
        }
        slot.channel_hash = Crypto::channelHash(slot.psk, slot.psk_len, i,
            (i == 0) ? slot.name : nullptr);
        Crypto::deriveKey(slot.psk, slot.psk_len, slot.aes_key, slot.aes_key_len);
    }
}

const ChannelSlot* ChannelManager::findByHash(uint8_t hash) {
    return findByHash(hash, 0);
}

const ChannelSlot* ChannelManager::findByHash(uint8_t hash, uint8_t start_index) {
    for (uint8_t i = start_index; i < MAX_CHANNEL_SLOTS; i++) {
        if (s_slots[i].role != ChannelRole::DISABLED &&
            s_slots[i].channel_hash == hash) {
            return &s_slots[i];
        }
    }
    return nullptr;
}

const ChannelSlot* ChannelManager::primarySlot() {
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        if (s_slots[i].role == ChannelRole::PRIMARY) return &s_slots[i];
    }
    return nullptr;
}

const ChannelSlot* ChannelManager::slotByIndex(uint8_t index) {
    if (index >= MAX_CHANNEL_SLOTS) return nullptr;
    return &s_slots[index];
}

bool ChannelManager::requiresMultiPhy() {
    uint8_t first_phy = 0xFF;
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        if (s_slots[i].role == ChannelRole::DISABLED) continue;
        if (first_phy == 0xFF) { first_phy = s_slots[i].phy_profile_index; continue; }
        if (s_slots[i].phy_profile_index != first_phy) return true;
    }
    return false;
}

} // namespace airtak
