// AirTAK — Airsoft Team Awareness Kit
// firmware/src/game/GameState.h — Stub (replaced by TacticalMarker in Phase 5)
// Planning: §12.3 | Roadmap: 5.3
//
// The old GameState module (game phases, zones, objectives, admin, scoring)
// has been removed in the Phase 5 tactical coordination redesign.
// Tactical markers are handled by the Android app via AIRTAK_TACTICAL (portnum 257);
// the firmware relays them transparently through the Router.
// This stub exists for build system compatibility only.

#pragma once

namespace airtak {

class GameState {
public:
    static void init() {}
};

} // namespace airtak
