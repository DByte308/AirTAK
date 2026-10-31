// AirTAK — Airsoft Team Awareness Kit
// platform/boards/esp32s3_runtime/hal/dispatcher.cpp — runtime Display dispatcher
// for a shared ESP32-S3 image.
//
// Implements the board-agnostic airtak::Display interface by selecting, from
// selectedBoard(), the single compatible backend (OLED or TFT) and forwarding
// each call to it. An unknown board has no compatible backend: every call is a
// no-op (currentScreen() returns MAIN) and NO backend/GPIO/I2C/SPI is touched.
//
// Serialization: a recursive FreeRTOS mutex (created idempotently) guarding all
// backend calls. The lead's main change calls Display::init() before the BLE/UI
// tasks so the mutex exists and the first render is committed before a pairing
// passkey can drive the screen (avoids the pairing-before-buffer race).
//
// Safety: actual render / brightness / sleep / wake are gated on the backend's
// ready(), so a failed begin() (null OLED buffer) is never dereferenced.
//
// Guard: compiled ONLY under AIRTAK_RUNTIME_BOARD. The per-board Display.cpp
// (chosen by src_filter) must be excluded for the runtime image, else there are
// two definitions of airtak::Display.

#include "hal/Display.h"

#ifdef AIRTAK_RUNTIME_BOARD

#include "platform/RuntimeDisplayRoute.h"
#include "platform/esp32/system/BoardProvisioning.h"
#include "platform/boards/esp32s3_runtime/hal/RuntimeDisplayBackends.h"
#include <Arduino.h>
#include <FreeRTOS.h>
#include <semphr.h>

namespace airtak {
namespace {

SemaphoreHandle_t s_mutex = nullptr;

void ensureMutex() {
    if (s_mutex == nullptr) {
        s_mutex = xSemaphoreCreateRecursiveMutex();
    }
}

RuntimeTftWiring compiledTftWiring() {
    RuntimeTftWiring w;
#if defined(TFT_SCLK)
    w.sck  = TFT_SCLK;  w.miso = TFT_MISO; w.mosi = TFT_MOSI;
    w.cs   = TFT_CS;    w.dc   = TFT_DC;   w.rst  = TFT_RST; w.bl = TFT_BL;
#endif
    // Logical (post rotation-1) geometry: prefer DISP_W/H, else derive from the
    // TFT_eSPI physical flags (rotation 1 swaps W/H).
#if defined(DISP_WIDTH) && defined(DISP_HEIGHT)
    w.width  = DISP_WIDTH;
    w.height = DISP_HEIGHT;
#elif defined(TFT_WIDTH) && defined(TFT_HEIGHT)
    w.width  = TFT_HEIGHT;
    w.height = TFT_WIDTH;
#endif
    return w;
}

enum class Backend : uint8_t { None, Oled, Tft };

Backend activeBackend() {
    const auto& bd = airtak::platform::selectedBoard();
    if (runtimeOledDisplayCompatible(bd)) return Backend::Oled;
    if (runtimeTftDisplayCompatible(bd, compiledTftWiring())) return Backend::Tft;
    return Backend::None;
}

} // namespace

void Display::init() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: RuntimeOledDisplay::init(); break;
        case Backend::Tft:  RuntimeTftDisplay::init();  break;
        case Backend::None: break;   // unknown: no backend, no I/O
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::refresh(EventGroupHandle_t system_event_group) {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::refresh(system_event_group); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::refresh(system_event_group);  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::nextScreen() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: RuntimeOledDisplay::nextScreen(); break;
        case Backend::Tft:  RuntimeTftDisplay::nextScreen();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::previousScreen() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: RuntimeOledDisplay::previousScreen(); break;
        case Backend::Tft:  RuntimeTftDisplay::previousScreen();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

ui::ScreenId Display::currentScreen() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    ui::ScreenId id = ui::ScreenId::MAIN;
    switch (activeBackend()) {
        case Backend::Oled: id = RuntimeOledDisplay::currentScreen(); break;
        case Backend::Tft:  id = RuntimeTftDisplay::currentScreen();  break;
        case Backend::None: break;   // unknown -> MAIN
    }
    xSemaphoreGiveRecursive(s_mutex);
    return id;
}

void Display::markDirty(uint16_t element_mask) {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: RuntimeOledDisplay::markDirty(element_mask); break;
        case Backend::Tft:  RuntimeTftDisplay::markDirty(element_mask);  break;
        case Backend::None: break;   // no I/O on unknown
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::clearDirty() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: RuntimeOledDisplay::clearDirty(); break;
        case Backend::Tft:  RuntimeTftDisplay::clearDirty();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::forceRefresh() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::forceRefresh(); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::forceRefresh();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::showPasscode(uint32_t passkey) {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::showPasscode(passkey); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::showPasscode(passkey);  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::showRecoveryMode(bool config_erased, bool bonds_cleared) {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::showRecoveryMode(config_erased, bonds_cleared); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::showRecoveryMode(config_erased, bonds_cleared);  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::setBrightness(uint8_t pct) {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::setBrightness(pct); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::setBrightness(pct);  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::sleep() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::sleep(); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::sleep();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::wake() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::wake(); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::wake();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

void Display::showShutdownWarning() {
    ensureMutex();
    xSemaphoreTakeRecursive(s_mutex, portMAX_DELAY);
    switch (activeBackend()) {
        case Backend::Oled: if (RuntimeOledDisplay::ready()) RuntimeOledDisplay::showShutdownWarning(); break;
        case Backend::Tft:  if (RuntimeTftDisplay::ready())  RuntimeTftDisplay::showShutdownWarning();  break;
        case Backend::None: break;
    }
    xSemaphoreGiveRecursive(s_mutex);
}

} // namespace airtak

#endif // AIRTAK_RUNTIME_BOARD
