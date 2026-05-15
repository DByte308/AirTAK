// AirTAK - Airsoft Team Awareness Kit
// firmware/src/ble/BLEQueue.cpp - BLE queue utility implementation

#include "ble/BLEQueue.h"
#include "mesh/PacketPool.h"
#include "airtak/ble.pb.h"
#include <pb_encode.h>
#include <FreeRTOS.h>
#include <queue.h>
#include <cstring>

extern QueueHandle_t g_ble_tx_queue;

namespace airtak {
namespace ble {

void queueAdminError(const char* error) {
    if (!::g_ble_tx_queue || !error) return;

    airtak_AdminResponse response = airtak_AdminResponse_init_zero;
    response.command = airtak_AdminCommand_RADIO_RESTART;
    response.success = false;
    strncpy(response.error, error, sizeof(response.error) - 1);

    PooledPacket* pkt = PacketPool::alloc();
    if (!pkt) return;
    pkt->data[0] = BLE_QUEUE_ADMIN;
    pb_ostream_t stream = pb_ostream_from_buffer(pkt->data + 1, PACKET_POOL_BUF_SIZE - 1);
    if (!pb_encode(&stream, airtak_AdminResponse_fields, &response)) {
        PacketPool::free(pkt);
        return;
    }
    pkt->length = static_cast<uint16_t>(1 + stream.bytes_written);
    if (xQueueSendToBack(::g_ble_tx_queue, &pkt, 0) != pdTRUE) {
        PacketPool::free(pkt);
    }
}

} // namespace ble
} // namespace airtak
