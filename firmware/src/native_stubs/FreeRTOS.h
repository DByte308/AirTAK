#pragma once

#include <cstdint>

using BaseType_t = int;
using TickType_t = uint32_t;

constexpr BaseType_t pdTRUE = 1;
constexpr BaseType_t pdFALSE = 0;
constexpr TickType_t portMAX_DELAY = 0xFFFFFFFFu;

#define pdMS_TO_TICKS(ms) (static_cast<TickType_t>(ms))