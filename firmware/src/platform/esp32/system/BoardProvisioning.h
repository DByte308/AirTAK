#pragma once
#include "platform/BoardDescriptor.h"

namespace airtak::platform {
// Call before ALL application GPIO initialization. Blocks in USB-only safe
// provisioning if no valid identity is stored. No automatic NVS erasure.
// Legacy-HAL selection-test images MUST pass their compiled board identifier.
void prepareBoardSelection(const char* imageBoardId);
const BoardDescriptor& selectedBoard();
} // namespace airtak::platform
