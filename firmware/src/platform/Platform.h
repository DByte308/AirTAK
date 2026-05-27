// AirTAK - Airsoft Team Awareness Kit
// firmware/src/platform/Platform.h - board and MCU integration boundary

#pragma once

#include <cstdint>
#include "platform/BoardConfig.h"

namespace airtak {
namespace platform {

const char* boardName();
const char* mcuFamilyName();

// Board-level early boot hooks. Keeps main.cpp free of board pin details.
void initBoard();

// Stable local identity used by the mesh layer. Each MCU family implements this
// from its own hardware unique ID source.
uint32_t nodeId();
void macAddress(uint8_t out[6]);

// Meshtastic HardwareModel enum value for this board.
uint8_t meshtasticHwModel();

} // namespace platform
} // namespace airtak
