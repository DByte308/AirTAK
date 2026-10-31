// Link-only HAL migration fixture. No display driver or hardware-verification
// claim. The runtime_hal_check environment explicitly disables uploading.
#ifdef AIRTAK_RUNTIME_HAL_COMPILE_TEST
#include "hal/Display.h"
namespace airtak {
void Display::init() {}
void Display::refresh(EventGroupHandle_t) {}
void Display::nextScreen() {}
void Display::previousScreen() {}
ui::ScreenId Display::currentScreen() { return ui::ScreenId::MAIN; }
void Display::markDirty(uint16_t) {}
void Display::clearDirty() {}
void Display::forceRefresh() {}
void Display::showPasscode(uint32_t) {}
void Display::showRecoveryMode(bool, bool) {}
void Display::setBrightness(uint8_t) {}
void Display::sleep() {}
void Display::wake() {}
void Display::showShutdownWarning() {}
} // namespace airtak
#endif
