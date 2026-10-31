// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/Touch.cpp — I2C touchscreen driver (GT911 / CST328)

#include "hal/Touch.h"
#include "config/constants/Constants.h"
#include "config.h"

#if (defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)) || defined(AIRTAK_PIN_TOUCH_INT)
#include <Arduino.h>
#include <Wire.h>
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
#include "platform/esp32/system/BoardProvisioning.h"
#endif

namespace airtak {

#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
// Runtime: touchscreen is only active when the descriptor reports a touch
// interrupt and a valid shared I2C bus (i2cSda/i2cScl). Otherwise Touch is a
// no-op (TouchGesture::NONE) and no I2C pins are driven.
static bool touchActive() {
    const auto& bd = airtak::platform::selectedBoard();
    return bd.touchInt >= 0 && bd.i2cSda >= 0 && bd.i2cScl >= 0 &&
           bd.display == DisplayKind::Tft && bd.tftWidth > 0 && bd.tftHeight > 0;
}
#endif

TouchPoint  Touch::s_points[TOUCH_MAX_POINTS];
uint8_t     Touch::s_count          = 0;
uint8_t     Touch::s_i2c_addr       = 0;
bool        Touch::s_initialized    = false;
int16_t     Touch::s_touch_start_x  = -1;
int16_t     Touch::s_touch_start_y  = -1;
uint16_t    Touch::s_touch_raw_x    = 0;
uint16_t    Touch::s_touch_raw_y    = 0;
bool        Touch::s_tracking       = false;

static void gt911WriteReg(uint8_t addr, uint16_t reg, uint8_t val) {
    Wire.beginTransmission(addr);
    Wire.write(reg >> 8);
    Wire.write(reg & 0xFF);
    Wire.write(val);
    Wire.endTransmission(true);
}

static uint8_t gt911ReadReg(uint8_t addr, uint16_t reg) {
    Wire.beginTransmission(addr);
    Wire.write(reg >> 8);
    Wire.write(reg & 0xFF);
    if (Wire.endTransmission(false) != 0) return 0;
    if (Wire.requestFrom(addr, (uint8_t)1) < 1) return 0;
    return Wire.read();
}

static uint8_t gt911ReadBuf(uint8_t addr, uint16_t reg, uint8_t* buf, uint8_t len) {
    Wire.beginTransmission(addr);
    Wire.write(reg >> 8);
    Wire.write(reg & 0xFF);
    if (Wire.endTransmission(false) != 0) return 0;
    size_t n = Wire.requestFrom(addr, len);
    if (n < len) return (uint8_t)n;
    for (uint8_t i = 0; i < len; i++) buf[i] = Wire.read();
    return len;
}

void Touch::init() {
    if (s_initialized) return;
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    // Runtime: only bring up the touch controller on the descriptor's shared I2C
    // bus and interrupt. No touch -> no I2C pins driven.
    if (!touchActive()) return;
    const auto& bd = airtak::platform::selectedBoard();
    Wire.begin(bd.i2cSda, bd.i2cScl);
    pinMode(bd.touchInt, INPUT_PULLUP);
#else
    pinMode(AIRTAK_PIN_TOUCH_INT, INPUT_PULLUP);
#endif

    // GT911 can appear at 0x5D or 0x14 depending on INT pin state at boot
    const uint8_t gt911_addrs[] = {GT911_ADDR_PRIMARY, GT911_ADDR_SECONDARY};
    const uint8_t cst328_addr = CST328_ADDR;

    for (uint8_t a : gt911_addrs) {
        Wire.beginTransmission(a);
        if (Wire.endTransmission(true) == 0) {
            s_i2c_addr = a;
            break;
        }
    }
    if (!s_i2c_addr) {
        Wire.beginTransmission(cst328_addr);
        if (Wire.endTransmission(true) == 0) {
            s_i2c_addr = cst328_addr;
        }
    }

    if (!s_i2c_addr) {
        return;
    }

    if (s_i2c_addr == GT911_ADDR_PRIMARY || s_i2c_addr == GT911_ADDR_SECONDARY) {
        gt911WriteReg(s_i2c_addr, GT911_REG_CMD, GT911_CMD_IDLE); // ensure idle
        delay(GT911_RESET_DELAY_MS);
        gt911WriteReg(s_i2c_addr, GT911_REG_CMD, GT911_CMD_SOFT_RESET); // soft reset
        delay(GT911_SOFT_RESET_DELAY_MS);
        uint8_t sw1 = gt911ReadReg(s_i2c_addr, GT911_REG_MOD_SW1);
        if ((sw1 & 0x0F) == 0 || sw1 == 0xFF) {
            sw1 = (sw1 & 0xF0) | GT911_MOD_SW1_DEFAULT;
        }
        gt911WriteReg(s_i2c_addr, GT911_REG_MOD_SW1, sw1);
        delay(GT911_CONFIG_DELAY_MS);
        uint8_t dummy[GT911_TOUCH_INFO_SIZE];
        gt911ReadBuf(s_i2c_addr, GT911_REG_TOUCH_INFO, dummy, GT911_TOUCH_INFO_SIZE);
        gt911WriteReg(s_i2c_addr, GT911_REG_TOUCH_INFO, 0x00);
        delay(GT911_FLUSH_DELAY_MS);
        gt911WriteReg(s_i2c_addr, GT911_REG_CMD, GT911_CMD_IDLE);
        delay(GT911_POST_FLUSH_DELAY_MS);
    }

    s_initialized = true;
}

TouchGesture Touch::poll() {
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    // Runtime: no touch on this board -> NONE, no I2C access.
    if (!touchActive()) return TouchGesture::NONE;
#endif
    if (!s_initialized) {
        init();
        if (!s_initialized) return TouchGesture::NONE;
    }

    bool was_pressed = s_count > 0 && s_points[0].pressed;
    int16_t prev_x = s_points[0].x;
    int16_t prev_y = s_points[0].y;

    bool now_pressed = false;
    uint16_t x = 0, y = 0;
#ifdef AIRTAK_RUNTIME_BOARD
    const uint16_t displayWidth = platform::selectedBoard().tftWidth;
    const uint16_t displayHeight = platform::selectedBoard().tftHeight;
#else
    const uint16_t displayWidth = DISP_WIDTH;
    const uint16_t displayHeight = DISP_HEIGHT;
#endif

    if (s_i2c_addr == GT911_ADDR_PRIMARY || s_i2c_addr == GT911_ADDR_SECONDARY) {
        uint8_t buf[GT911_TOUCH_INFO_SIZE] = {0};
        uint8_t n = gt911ReadBuf(s_i2c_addr, GT911_REG_TOUCH_INFO, buf, GT911_TOUCH_INFO_SIZE);
        if (n < GT911_TOUCH_INFO_SIZE) return TouchGesture::NONE;
        gt911WriteReg(s_i2c_addr, GT911_REG_TOUCH_INFO, 0x00);

        uint8_t points = buf[0] & 0x0F;
        s_count = (points > 0) ? 1 : 0;
        if (points > 0) {
            uint16_t rx = (uint16_t)buf[2] | ((uint16_t)buf[3] << 8);
            uint16_t ry = (uint16_t)buf[4] | ((uint16_t)buf[5] << 8);
            s_touch_raw_x = rx;
            s_touch_raw_y = ry;
            x = (uint32_t)rx * displayWidth / GT911_RAW_X_MAX;
            y = (uint32_t)ry * displayHeight / GT911_RAW_Y_MAX;
            // Swap XY and mirror Y per LilyGo factory calibration
            uint16_t swp = x;
            x = y;
            y = swp;
            y = displayHeight - y;
            s_points[0] = {x, y, true};
            now_pressed = true;
        } else {
            s_points[0] = {0, 0, false};
        }
    } else {
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
        if (digitalRead(airtak::platform::selectedBoard().touchInt) == HIGH && !was_pressed) {
#else
        if (digitalRead(AIRTAK_PIN_TOUCH_INT) == HIGH && !was_pressed) {
#endif
            return TouchGesture::NONE;
        }
        uint8_t buf[GT911_CONFIG_SIZE] = {0};
        Wire.beginTransmission(s_i2c_addr);
        Wire.write(0x00); // CST328_REG_TOUCH_INFO — keep 0x00 as-is for protocol clarity
        if (Wire.endTransmission(false) != 0) return TouchGesture::NONE;
        if (Wire.requestFrom(s_i2c_addr, (uint8_t)CST328_TOUCH_DATA_SIZE) < CST328_TOUCH_DATA_SIZE) return TouchGesture::NONE;
        for (int i = 0; i < CST328_TOUCH_DATA_SIZE; i++) buf[i] = Wire.read();

        s_count = (buf[0] & 0x04) ? 1 : 0;
        if (s_count) {
            x = ((buf[2] & 0x0F) << 8) | buf[3];
            y = ((buf[4] & 0x0F) << 8) | buf[5];
            s_points[0] = {x, y, true};
            now_pressed = true;
        } else {
            s_points[0] = {0, 0, false};
        }
    }

    if (now_pressed && !was_pressed) {
        s_touch_start_x = s_points[0].x;
        s_touch_start_y = s_points[0].y;
        s_tracking = true;
        return TouchGesture::NONE;
    }

    if (now_pressed && s_tracking) {
        return TouchGesture::NONE;
    }

    if (!now_pressed && was_pressed && s_tracking) {
        int16_t dx = prev_x - s_touch_start_x;
        int16_t dy = prev_y - s_touch_start_y;
        int16_t adx = dx < 0 ? -dx : dx;
        int16_t ady = dy < 0 ? -dy : dy;

        TouchGesture gesture;
        if (adx < TOUCH_SWIPE_THRESHOLD_PX && ady < TOUCH_SWIPE_THRESHOLD_PX) {
            gesture = TouchGesture::TAP;
        } else if (adx > ady) {
            gesture = (dx > 0) ? TouchGesture::SWIPE_RIGHT : TouchGesture::SWIPE_LEFT;
        } else {
            gesture = (dy > 0) ? TouchGesture::SWIPE_DOWN : TouchGesture::SWIPE_UP;
        }

        s_tracking = false;
        s_touch_start_x = -1;
        s_touch_start_y = -1;
        return gesture;
    }

    if (!now_pressed) {
        s_tracking = false;
    }

    return TouchGesture::NONE;
}

uint8_t Touch::touchCount() { return s_count; }
const TouchPoint& Touch::point(uint8_t i) { return s_points[i]; }

} // namespace airtak

#else

namespace airtak {
TouchPoint  Touch::s_points[TOUCH_MAX_POINTS] = {};
uint8_t     Touch::s_count          = 0;
uint8_t     Touch::s_i2c_addr       = 0;
bool        Touch::s_initialized    = false;
int16_t     Touch::s_touch_start_x  = -1;
int16_t     Touch::s_touch_start_y  = -1;
bool        Touch::s_tracking       = false;

void Touch::init() {}
TouchGesture Touch::poll() { return TouchGesture::NONE; }
uint8_t Touch::touchCount() { return 0; }
const TouchPoint& Touch::point(uint8_t) { return s_points[0]; }
} // namespace airtak

#endif
