#pragma once

#include "FreeRTOS.h"
#include <cstddef>

using QueueHandle_t = void*;

inline QueueHandle_t xQueueCreate(size_t, size_t) { return reinterpret_cast<QueueHandle_t>(0x1); }
inline BaseType_t xQueueSend(QueueHandle_t, const void*, TickType_t) { return pdTRUE; }
inline BaseType_t xQueueSendToBack(QueueHandle_t, const void*, TickType_t) { return pdTRUE; }
inline BaseType_t xQueueReceive(QueueHandle_t, void*, TickType_t) { return pdFALSE; }