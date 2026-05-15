// MASH — Mesh Airsoft Situational Hub
// firmware/src/system/RecoveryMode.h — Boot-time safe mode and recovery

#pragma once

namespace mash {

class RecoveryMode {
public:
    static bool requestedAtBoot();
    [[noreturn]] static void run();
};

} // namespace mash
