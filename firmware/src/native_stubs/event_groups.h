#pragma once

#include "FreeRTOS.h"
#include <cstdint>

using EventBits_t = uint32_t;
using EventGroupHandle_t = void*;

inline EventGroupHandle_t xEventGroupCreate() { return reinterpret_cast<EventGroupHandle_t>(0x2); }
inline EventBits_t xEventGroupSetBits(EventGroupHandle_t, EventBits_t bits) { return bits; }
inline EventBits_t xEventGroupClearBits(EventGroupHandle_t, EventBits_t) { return 0; }
inline EventBits_t xEventGroupGetBits(EventGroupHandle_t) { return 0; }
inline EventBits_t xEventGroupWaitBits(EventGroupHandle_t, EventBits_t, BaseType_t, BaseType_t, TickType_t) { return 0; }