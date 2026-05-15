// MASH — Mesh Airsoft Situational Hub
// firmware/src/mesh/PacketPool.cpp — Fixed-size preallocated packet buffer pool implementation
// Planning: §9.2 | Roadmap: 1.7, 2.5

#include "mesh/PacketPool.h"
#include <FreeRTOS.h>
#include <semphr.h>
#include <cstring>

namespace mash {

static PooledPacket   s_pool[PACKET_POOL_SIZE];
static SemaphoreHandle_t s_mutex = nullptr;
static size_t         s_used = 0;

void PacketPool::init() {
    memset(s_pool, 0, sizeof(s_pool));
    s_mutex = xSemaphoreCreateMutex();
    s_used  = 0;
}

PooledPacket* PacketPool::alloc() {
    if (!s_mutex) return nullptr;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    PooledPacket* result = nullptr;
    for (size_t i = 0; i < PACKET_POOL_SIZE; i++) {
        if (!s_pool[i].in_use) {
            s_pool[i].in_use = true;
            s_pool[i].length = 0;
            result = &s_pool[i];
            s_used++;
            break;
        }
    }
    xSemaphoreGive(s_mutex);
    return result;
}

void PacketPool::free(PooledPacket* pkt) {
    if (!pkt || !s_mutex) return;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (pkt->in_use) {
        pkt->in_use = false;
        if (s_used > 0) s_used--;
    }
    xSemaphoreGive(s_mutex);
}

size_t PacketPool::usedCount() { return s_used; }

} // namespace mash
