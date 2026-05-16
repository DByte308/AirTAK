#pragma once

#include <cstdint>

using BaseType_t = int;
using TickType_t = uint32_t;
using UBaseType_t = unsigned int;

using TaskFunction_t = void (*)(void*);
using TaskHandle_t = void*;

constexpr uint16_t configMINIMAL_STACK_SIZE = 128;

constexpr BaseType_t pdTRUE = 1;
constexpr BaseType_t pdFALSE = 0;
constexpr BaseType_t pdPASS = 1;
constexpr BaseType_t pdFAIL = 0;
constexpr TickType_t portMAX_DELAY = 0xFFFFFFFFu;

#define pdMS_TO_TICKS(ms) (static_cast<TickType_t>(ms))

inline void vTaskDelay(TickType_t) {}
inline BaseType_t xTaskCreate(TaskFunction_t, const char*, uint16_t, void*, UBaseType_t, TaskHandle_t*) { return pdPASS; }