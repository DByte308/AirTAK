// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Touch.h — I2C touchscreen gesture detection

#pragma once

#include <cstdint>

namespace airtak {

enum class TouchGesture : uint8_t {
    NONE,
    TAP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
};

struct TouchPoint {
    uint16_t x;
    uint16_t y;
    bool     pressed;
};

class Touch {
public:
    static void init();
    static TouchGesture poll();

    static uint8_t touchCount();
    static const TouchPoint& point(uint8_t index);

    static constexpr int16_t SWIPE_THRESHOLD = 8;

private:
    static bool probeCST328();
    static bool probeGT911();
    static bool readCST328();
    static bool readGT911();

    static constexpr uint8_t MAX_POINTS = 2;

    static TouchPoint  s_points[MAX_POINTS];
    static uint8_t     s_count;
    static uint8_t     s_i2c_addr;
    static bool        s_initialized;

    static int16_t  s_touch_start_x;
    static int16_t  s_touch_start_y;
    static uint16_t s_touch_raw_x;
    static uint16_t s_touch_raw_y;
    static bool     s_tracking;
};

} // namespace airtak
