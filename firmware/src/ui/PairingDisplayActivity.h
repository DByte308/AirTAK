#pragma once
#include <atomic>

namespace airtak::ui {
// BLE may request the passkey screen from a different task. Coalesce requests;
// the display loop consumes them and restarts the panel's inactivity timer.
inline std::atomic<bool> pairingDisplayRequested{false};
inline void requestPairingDisplayActivity() { pairingDisplayRequested.store(true); }
inline bool consumePairingDisplayActivity() { return pairingDisplayRequested.exchange(false); }
} // namespace airtak::ui
