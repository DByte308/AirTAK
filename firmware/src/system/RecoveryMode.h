// AirTAK — Airsoft Team Awareness Kit
// firmware/src/system/RecoveryMode.h — Boot-time safe mode and recovery

#pragma once

namespace airtak {

class RecoveryMode {
public:
    static bool requestedAtBoot();
    [[noreturn]] static void run();
};

} // namespace airtak
