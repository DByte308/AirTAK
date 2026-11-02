#pragma once

#include <cstddef>
#include <cstdint>
#include <pb_decode.h>
#include "airtak/config.pb.h"
#include "config/constants/MeshConstants.h"

namespace airtak::config {

// POWER is a whole-group protobuf snapshot. An omitted brightness scalar means
// zero, not the factory default: proto3 omits the field when the slider is at 0%.
inline uint8_t brightnessFromPowerConfig(const uint8_t* bytes, size_t length, uint8_t fallback) {
    if (bytes == nullptr && length != 0) return fallback;
    airtak_PowerConfig config = airtak_PowerConfig_init_zero;
    pb_istream_t stream = pb_istream_from_buffer(bytes, length);
    if (!pb_decode(&stream, airtak_PowerConfig_fields, &config)) return fallback;
    return static_cast<uint8_t>(config.brightness_pct > CONFIG_MAX_BRIGHTNESS_PCT
        ? CONFIG_MAX_BRIGHTNESS_PCT : config.brightness_pct);
}

} // namespace airtak::config
