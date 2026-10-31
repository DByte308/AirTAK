// AirTAK — Airsoft Team Awareness Kit
// platform/boards/esp32s3_runtime/hal/RuntimeDisplayBackends.h — runtime display
// backend interface for a shared ESP32-S3 image.
//
// Declares the two display backends a shared image can route to. Each exposes
// the SAME public API as airtak::Display (hal/Display.h), as static methods,
// PLUS compatible(profile) and ready(). The concrete implementations live in
// platform/boards/esp32s3_runtime/hal/ (adapted by the display-backend worker);
// this header declares the interface so the dispatcher can compile against it.
//
// ready() reports whether the backend's driver/framebuffer was successfully
// initialised (e.g. true only after OLED begin() succeeded) so the dispatcher
// never dereferences a null buffer.

#pragma once
#include "hal/Display.h"
#include "platform/BoardDescriptor.h"
#include <cstdint>
#include <FreeRTOS.h>
#include <event_groups.h>

namespace airtak {

class RuntimeOledDisplay {
public:
    static bool compatible(const BoardDescriptor& profile);
    static bool ready();

    static void init();
    static void refresh(EventGroupHandle_t system_event_group);
    static void nextScreen();
    static void previousScreen();
    static ui::ScreenId currentScreen();
    static void markDirty(uint16_t element_mask);
    static void clearDirty();
    static void forceRefresh();
    static void showPasscode(uint32_t passkey);
    static void showRecoveryMode(bool config_erased, bool bonds_cleared);
    static void setBrightness(uint8_t pct);
    static void sleep();
    static void wake();
    static void showShutdownWarning();
};

class RuntimeTftDisplay {
public:
    static bool compatible(const BoardDescriptor& profile);
    static bool ready();

    static void init();
    static void refresh(EventGroupHandle_t system_event_group);
    static void nextScreen();
    static void previousScreen();
    static ui::ScreenId currentScreen();
    static void markDirty(uint16_t element_mask);
    static void clearDirty();
    static void forceRefresh();
    static void showPasscode(uint32_t passkey);
    static void showRecoveryMode(bool config_erased, bool bonds_cleared);
    static void setBrightness(uint8_t pct);
    static void sleep();
    static void wake();
    static void showShutdownWarning();
};

} // namespace airtak
