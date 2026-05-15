// MASH — Mesh Airsoft Situational Hub
// firmware/src/hal/GPS.cpp — L76K GPS UART driver implementation
// Planning: §9.5, §7.1 | Roadmap: 1.3

#include "hal/GPS.h"
#include "config/Defaults.h"
#include "firmware/include/config.h"
#include <Arduino.h>
#include <TinyGPS++.h>  // TinyGPS++ ≥ 1.0.3 (platformio.ini dependency)

namespace mash {

// ── Static state ──────────────────────────────────────────────────────────────
static TinyGPSPlus s_gps;
static GpsFix      s_current_fix = {};
static double      s_last_tx_lat = 0.0;
static double      s_last_tx_lng = 0.0;
static bool        s_baud_upgraded = false;

// UART2 used for L76K on the T-Echo Plus
static HardwareSerial& GPS_SERIAL = Serial1;

// ── PMTK command helpers ──────────────────────────────────────────────────────
static void send_pmtk(const char* cmd) {
    GPS_SERIAL.print(cmd);
    GPS_SERIAL.print("\r\n");
}

// ── GPS::init() ───────────────────────────────────────────────────────────────
void GPS::init() {
    // Enable L76K via GPIO
    pinMode(PIN_GPS_ENABLE, OUTPUT);
    digitalWrite(PIN_GPS_ENABLE, HIGH);

    // PPS pin — configure as input but do not use in logic (reserved, §9.5)
    pinMode(PIN_GPS_PPS, INPUT);

    // Start UART at initial baud rate (§9.5)
    GPS_SERIAL.begin(GPS_BAUD_INITIAL, SERIAL_8N1, PIN_GPS_RX_MCU, PIN_GPS_TX_MCU);
}

// ── GPS::process() ───────────────────────────────────────────────────────────
void GPS::process(EventGroupHandle_t system_event_group) {
    // Feed all available UART bytes to TinyGPS++
    while (GPS_SERIAL.available()) {
        char c = GPS_SERIAL.read();
        if (s_gps.encode(c)) {
            // A complete NMEA sentence was decoded.

            // Upgrade baud rate on first valid sentence (§9.5)
            if (!s_baud_upgraded && s_gps.charsProcessed() > 10) {
                // TODO Phase 1.3: Send PMTK command to change L76K baud to 115200,
                // then re-init GPS_SERIAL at GPS_BAUD_FAST.
                // send_pmtk("$PMTK251,115200*1F");
                // GPS_SERIAL.begin(GPS_BAUD_FAST, ...);
                // Configure GNGGA + GNRMC only via PMTK314
                // send_pmtk("$PMTK314,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0*28");
                s_baud_upgraded = true;
            }

            if (s_gps.location.isValid() && s_gps.hdop.isValid()) {
                float hdop = s_gps.hdop.hdop();
                s_current_fix.valid      = (hdop <= 5.0f && s_gps.satellites.value() >= 4);
                s_current_fix.latitude   = s_gps.location.lat();
                s_current_fix.longitude  = s_gps.location.lng();
                s_current_fix.altitude_m = s_gps.altitude.meters();
                s_current_fix.hdop       = hdop;
                s_current_fix.satellites = s_gps.satellites.value();
                s_current_fix.accuracy_m = hdop * 5.0f; // rough estimate
                s_current_fix.timestamp_ms = millis();

                if (s_current_fix.valid && system_event_group) {
                    xEventGroupSetBits(system_event_group, GPS_EVENT_NEW_POSITION);
                }
            }
        }
    }
}

// ── GPS::currentFix() ────────────────────────────────────────────────────────
const GpsFix& GPS::currentFix() { return s_current_fix; }
bool GPS::hasFix() { return s_current_fix.valid; }

// ── GPS::setPower() ──────────────────────────────────────────────────────────
void GPS::setPower(bool on) {
    // Phase 6.2: GPS duty cycling via ENABLE pin (§9.5)
    digitalWrite(PIN_GPS_ENABLE, on ? HIGH : LOW);
}

// ── Displacement and smart beaconing support ─────────────────────────────────
static double haversine_m(double lat1, double lon1, double lat2, double lon2);

float GPS::displacementFromLastTxMeters() {
    if (s_last_tx_lat == 0.0 && s_last_tx_lng == 0.0) return 9999.0f; // force first TX
    return (float)haversine_m(s_last_tx_lat, s_last_tx_lng,
                               s_current_fix.latitude, s_current_fix.longitude);
}

void GPS::markPositionTransmitted() {
    s_last_tx_lat = s_current_fix.latitude;
    s_last_tx_lng = s_current_fix.longitude;
}

// Haversine formula — returns distance in meters
static double haversine_m(double lat1, double lon1, double lat2, double lon2) {
    constexpr double R = 6371000.0; // Earth radius in meters
    double dlat = (lat2 - lat1) * M_PI / 180.0;
    double dlon = (lon2 - lon1) * M_PI / 180.0;
    double a = sin(dlat / 2) * sin(dlat / 2)
             + cos(lat1 * M_PI / 180.0) * cos(lat2 * M_PI / 180.0)
             * sin(dlon / 2) * sin(dlon / 2);
    return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
}

} // namespace mash
