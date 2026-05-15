// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/GPS.h — L76K GPS UART driver
// Planning: §9.5, §7.1 | Roadmap: 1.3
//
// Initializes the Quectel L76K at 9600 baud, upgrades to 115200 after first valid
// NMEA sentence, configures GNGGA + GNRMC only via PMTK commands.
// Tracks fix quality (HDOP, satellite count). Drives smart beaconing (§8.7).
// PPS pin (P1.04) is recorded but not used in logic — reserved for future use.

#pragma once

#include <cstdint>
#include <event_groups.h>

namespace mash {

// Event bits in g_system_event_group related to GPS
constexpr EventBits_t GPS_EVENT_FIX_ACQUIRED = (1 << 8);
constexpr EventBits_t GPS_EVENT_FIX_LOST     = (1 << 9);
constexpr EventBits_t GPS_EVENT_NEW_POSITION  = (1 << 10);

struct GpsFix {
    bool     valid;          // true when HDOP ≤ 5.0 and satellite count sufficient
    double   latitude;       // WGS-84 decimal degrees
    double   longitude;
    float    altitude_m;
    float    hdop;
    uint8_t  satellites;
    float    accuracy_m;     // estimated from HDOP
    uint64_t timestamp_ms;   // millis() at fix time
};

class GPS {
public:
    // Phase 1.3: Initialize L76K UART at 9600 baud, upgrade to 115200 baud after
    // first valid NMEA, configure GNGGA + GNRMC only.
    static void init();

    // Phase 1.3: Process available UART bytes. Called from task_gps_parser().
    // Sets GPS_EVENT_NEW_POSITION in system_event_group when a new valid fix arrives.
    static void process(EventGroupHandle_t system_event_group);

    // Returns the most recent GPS fix. valid=false if no fix acquired.
    static const GpsFix& currentFix();

    // Returns true if the L76K has a valid fix (HDOP ≤ 5.0, ≥ 4 satellites).
    static bool hasFix();

    // Phase 6.2: Enable/disable GPS power via GPIO ENABLE pin (P1.02) for duty cycling.
    static void setPower(bool on);

    // Displacement in meters from the previous position TX location.
    // Used by the smart beaconing logic in Router/MeshNode (§8.7).
    static float displacementFromLastTxMeters();

    // Called by MeshNode after each POSITION_APP TX to reset the displacement baseline.
    static void markPositionTransmitted();
};

} // namespace mash
