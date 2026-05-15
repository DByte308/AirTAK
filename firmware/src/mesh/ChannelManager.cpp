// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/ChannelManager.cpp — Channel slot table implementation
// Planning: §8.5, §8.6 | Roadmap: 2.7

#include "mesh/ChannelManager.h"
#include "mesh/Crypto.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include <cstring>

namespace mash {

static ChannelSlot s_slots[MAX_CHANNEL_SLOTS];

void ChannelManager::init() {
    // Load default channel configurations from Defaults.h.
    // Phase 2.7: Load from ConfigManager when available.
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        const auto& def = defaults::CHANNEL_SLOTS[i];
        auto& slot = s_slots[i];
        slot.index             = def.index;
        strncpy(slot.name, def.name, 16);
        slot.name[16]          = '\0';
        slot.psk_len           = 0; // PSK set by user at first-time setup
        memset(slot.psk, 0, sizeof(slot.psk));
        slot.role              = static_cast<ChannelRole>(def.role);
        slot.uplink_enabled    = def.uplink_enabled;
        slot.downlink_enabled  = def.downlink_enabled;
        slot.position_precision = def.position_precision;
        slot.phy_profile_index = def.phy_profile_index;
        slot.channel_hash      = 0;
        memset(slot.aes_key, 0, sizeof(slot.aes_key));
    }
    rekey();
}

void ChannelManager::rekey() {
    // Recompute channel hashes and AES keys for all active slots (§8.6).
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
        auto& slot = s_slots[i];
        if (slot.role == ChannelRole::DISABLED) continue;
        if (slot.psk_len == 0) {
            slot.channel_hash = 0;
            memset(slot.aes_key, 0, sizeof(slot.aes_key));
            continue;
        }
        slot.channel_hash = Crypto::channelHash(slot.psk, slot.psk_len, i);
        Crypto::deriveKey(slot.psk, slot.psk_len, slot.aes_key);
    }
}

const ChannelSlot* ChannelManager::findByHash(uint8_t hash) {
    for (uint8_t i = 0; i < MAX_CHANNEL_SLOTS; i++) {
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

} // namespace mash
