#include "Arduino.h"
#include "FreeRTOS.h"
#include "queue.h"
#include "event_groups.h"

#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "mesh/ChannelManager.h"
#include "mesh/MeshNode.h"
#include "mesh/PacketPool.h"
#include "hal/Radio.h"

#include <cstring>

QueueHandle_t g_ble_tx_queue = nullptr;
QueueHandle_t g_radio_tx_queue = nullptr;
EventGroupHandle_t g_radio_event_group = nullptr;

static uint32_t s_now_ms = 1000;

uint32_t millis() { return s_now_ms; }
void delay(uint32_t ms) { s_now_ms += ms; }
long random(long min_value, long max_value) { return min_value < max_value ? min_value : 0; }

namespace mash {

static ChannelSlot s_primary_slot = {
    0,
    "MASH",
    {0},
    0,
    ChannelRole::PRIMARY,
    true,
    true,
    32,
    0,
    0,
    {0}
};

void PacketPool::init() {}
PooledPacket* PacketPool::alloc() {
    static PooledPacket packet;
    memset(&packet, 0, sizeof(packet));
    packet.in_use = true;
    return &packet;
}
void PacketPool::free(PooledPacket*) {}
size_t PacketPool::usedCount() { return 0; }

void MeshNode::init() {}
void MeshNode::broadcastNodeInfo(QueueHandle_t) {}
void MeshNode::tick(QueueHandle_t) {}
uint32_t MeshNode::nodeId() { return 0x12345678; }
void MeshNode::maybeTransmitPosition(QueueHandle_t, EventGroupHandle_t) {}
void MeshNode::handlePositionRequest(uint32_t, QueueHandle_t) {}
uint32_t MeshNode::knownNodeCount() { return 0; }
void MeshNode::onNodeInfoReceived(uint32_t) {}

void ChannelManager::init() {}
void ChannelManager::rekey() {}
const ChannelSlot* ChannelManager::findByHash(uint8_t hash) {
    return hash == s_primary_slot.channel_hash ? &s_primary_slot : nullptr;
}
const ChannelSlot* ChannelManager::primarySlot() { return &s_primary_slot; }
const ChannelSlot* ChannelManager::slotByIndex(uint8_t index) { return index == 0 ? &s_primary_slot : nullptr; }
bool ChannelManager::requiresMultiPhy() { return false; }

void Radio::init() {}
void Radio::reinit() {}
void Radio::process(QueueHandle_t, QueueHandle_t, EventGroupHandle_t) {}
bool Radio::transmit(const uint8_t*, uint8_t, uint8_t) { return true; }
void Radio::pause() {}
void Radio::resume() {}
bool Radio::isPaused() { return true; }
float Radio::lastSnr() { return 0.0f; }
float Radio::lastRssi() { return 0.0f; }
uint32_t Radio::packetsRx() { return 0; }
uint32_t Radio::packetsTx() { return 0; }

namespace config {

void ConfigManager::init() {}
int ConfigManager::applyGroup(ConfigGroup, const uint8_t*, size_t, bool) { return 0; }
size_t ConfigManager::readGroup(ConfigGroup, uint8_t*, size_t) { return 0; }
void ConfigManager::resetGroup(ConfigGroup) {}
void ConfigManager::registerCallback(ConfigChangedCallback) {}
void ConfigManager::radioRestartProcedure() {}
bool ConfigManager::isRadioRestartPending() { return false; }
const char* ConfigManager::deviceLongName() { return "MASH Node"; }
const char* ConfigManager::deviceShortName() { return "MASH"; }
uint32_t ConfigManager::positionBroadcastIntervalSecs() { return defaults::POSITION_BROADCAST_INTERVAL_SECS; }
uint32_t ConfigManager::positionMinTxIntervalSecs() { return defaults::POSITION_MIN_TX_INTERVAL_SECS; }
uint32_t ConfigManager::positionMovementThresholdM() { return defaults::POSITION_MOVEMENT_THRESHOLD_M; }
uint32_t ConfigManager::positionPrecisionBits() { return defaults::POSITION_GPS_PRECISION_BITS; }
uint8_t ConfigManager::positionHopLimitOverride() { return 0; }
float ConfigManager::loraFrequencyMhz() { return 869.525f; }
uint8_t ConfigManager::loraTxPowerDbm() { return defaults::LORA_TX_POWER_DBM; }
uint8_t ConfigManager::primarySlotPhyProfileIndex() { return 0; }
bool ConfigManager::loraPhyProfile(uint8_t, uint8_t&, uint32_t&, uint8_t&) { return false; }

} // namespace config
} // namespace mash