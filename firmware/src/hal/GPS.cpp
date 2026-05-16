// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/GPS.cpp — L76K GPS UART driver implementation
// Planning: §9.5, §7.1 | Roadmap: 1.3

#include "hal/GPS.h"
#include "config/Defaults.h"
#include "config.h"
#include <Arduino.h>
#include <TinyGPS++.h>  // TinyGPS++ ≥ 1.0.3 (platformio.ini dependency)
#include <cstring>

namespace airtak {

// ── Static state ──────────────────────────────────────────────────────────────
static TinyGPSPlus s_gps;
static GpsFix      s_current_fix = {};
static double      s_last_tx_lat = 0.0;
static double      s_last_tx_lng = 0.0;
static bool        s_baud_upgraded = false;
static uint32_t    s_current_baud = GPS_BAUD_INITIAL;
static uint32_t    s_last_valid_sentence_ms = 0;
static uint32_t    s_last_baud_probe_ms = 0;

static constexpr uint32_t GPS_BAUD_CANDIDATES[] = {
    GPS_BAUD_INITIAL,
    38400,
    57600,
    GPS_BAUD_FAST,
};
static uint8_t s_baud_probe_index = 0;

#ifdef AIRTAK_GPS_DEBUG
static uint32_t    s_debug_raw_bytes    = 0;  // total bytes read from GPS UART
static uint32_t    s_debug_last_print_ms = 0; // last time we printed diagnostics
static char        s_debug_sentence[128] = {};
static uint8_t     s_debug_sentence_len = 0;
static uint8_t     s_debug_sentence_prints = 0;
#endif

// Board-selected UART used for the GNSS module.
static HardwareSerial& GPS_SERIAL = AIRTAK_GPS_SERIAL;

static void begin_gps_serial(uint32_t baud) {
#if defined(AIRTAK_GPS_SERIAL_RX_PIN) && AIRTAK_GPS_SERIAL_RX_PIN >= 0
    GPS_SERIAL.begin(baud, SERIAL_8N1,
                     AIRTAK_GPS_SERIAL_RX_PIN, AIRTAK_GPS_SERIAL_TX_PIN);
#else
    GPS_SERIAL.begin(baud, SERIAL_8N1);
#endif
    s_current_baud = baud;

#ifdef AIRTAK_GPS_DEBUG
    s_debug_sentence_len = 0;
    s_debug_sentence_prints = 0;
#endif
}

#ifdef AIRTAK_GPS_DEBUG
static int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static void print_debug_sentence() {
    if (s_debug_sentence_prints >= 6 || s_debug_sentence_len == 0) return;

    s_debug_sentence[s_debug_sentence_len] = '\0';
    char* checksum_marker = strchr(s_debug_sentence, '*');
    if (checksum_marker && checksum_marker[1] && checksum_marker[2]) {
        uint8_t calculated = 0;
        for (char* p = s_debug_sentence + 1; p < checksum_marker; ++p) {
            calculated ^= static_cast<uint8_t>(*p);
        }
        int high = hex_value(checksum_marker[1]);
        int low = hex_value(checksum_marker[2]);
        int provided = (high >= 0 && low >= 0) ? ((high << 4) | low) : -1;
        Serial.printf("[GPS RAW baud=%lu] %s | calc=%02X got=%02X %s\n",
                      (unsigned long)s_current_baud,
                      s_debug_sentence,
                      calculated,
                      provided >= 0 ? provided : 0,
                      provided == calculated ? "OK" : "BAD");
    } else {
        Serial.printf("[GPS RAW baud=%lu] %s | no checksum marker\n",
                      (unsigned long)s_current_baud,
                      s_debug_sentence);
    }
    s_debug_sentence_prints++;
}

static void capture_debug_char(char c) {
    if (c == '$') {
        s_debug_sentence_len = 0;
    }

    if (s_debug_sentence_len >= sizeof(s_debug_sentence) - 1) {
        print_debug_sentence();
        s_debug_sentence_len = 0;
        return;
    }

    if (s_debug_sentence_len > 0 || c == '$') {
        if (c == '\r' || c == '\n') {
            print_debug_sentence();
            s_debug_sentence_len = 0;
        } else {
            s_debug_sentence[s_debug_sentence_len++] =
                (c >= 32 && c <= 126) ? c : '.';
        }
    }
}
#endif

// ── PMTK command helpers ──────────────────────────────────────────────────────
static void send_pmtk(const char* cmd) {
    GPS_SERIAL.print(cmd);
    GPS_SERIAL.print("\r\n");
}

// ── GPS::init() ───────────────────────────────────────────────────────────────
void GPS::init() {
    // Enable GPS power via dedicated GPIO (T-Echo Plus only; -1 = no pin).
#if defined(AIRTAK_PIN_GPS_ENABLE) && AIRTAK_PIN_GPS_ENABLE >= 0
    pinMode(AIRTAK_PIN_GPS_ENABLE, OUTPUT);
    digitalWrite(AIRTAK_PIN_GPS_ENABLE, HIGH);
#endif
#if defined(AIRTAK_PIN_GPS_RESET) && AIRTAK_PIN_GPS_RESET >= 0
    pinMode(AIRTAK_PIN_GPS_RESET, OUTPUT);
    digitalWrite(AIRTAK_PIN_GPS_RESET, HIGH);
#endif
    // PPS pin — configure as input but do not use in logic (reserved, §9.5)
#if defined(AIRTAK_PIN_GPS_PPS) && AIRTAK_PIN_GPS_PPS >= 0
    pinMode(AIRTAK_PIN_GPS_PPS, INPUT);
#endif

    // Start UART at initial baud rate (§9.5). If a previous firmware left the
    // module at GPS_BAUD_FAST, process() will auto-probe and recover.
    begin_gps_serial(GPS_BAUD_INITIAL);
}

// ── GPS::process() ───────────────────────────────────────────────────────────
void GPS::process(EventGroupHandle_t system_event_group) {
    // Feed all available UART bytes to TinyGPS++
    while (GPS_SERIAL.available()) {
        char c = GPS_SERIAL.read();
#ifdef AIRTAK_GPS_DEBUG
        s_debug_raw_bytes++;
    capture_debug_char(c);
#endif
        if (s_gps.encode(c)) {
            // A complete NMEA sentence was decoded.
            s_last_valid_sentence_ms = millis();

            // Always update satellite count for diagnostic purposes — this is
            // non-zero as soon as the module starts receiving satellite signals,
            // even before a full position fix is available.
            if (s_gps.satellites.isValid()) {
                s_current_fix.satellites = s_gps.satellites.value();
            }

            // Configure the GPS module once after the first complete sentence is
            // decoded, confirming the module is alive and its UART is working.
            // We intentionally stay at GPS_BAUD_INITIAL (9600) for the entire
            // session: the PMTK251 baud-upgrade command requires the module to
            // internally switch baud rates before the host can follow, and the
            // minimum safe gap is ~100 ms — longer than the 20 ms delay that was
            // here previously.  At 9600 baud, two NMEA sentences (GGA + RMC) at
            // 1 Hz occupy ~150 ms out of every 1000 ms, well within capacity.
            if (!s_baud_upgraded && s_gps.charsProcessed() > 10) {
                // Restrict output to GNGGA + GNRMC only (saves ~80 % of UART traffic).
                // PMTK314 field order: GLL, RMC, VTG, GGA, GSA, GSV, ... (17 more) = 19 fields.
                send_pmtk("$PMTK314,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0*28");

                // Confirm 1 Hz update rate.
                send_pmtk("$PMTK220,1000*1F");

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

    // If the module was previously switched to another baud by older firmware,
    // bytes can arrive while every NMEA checksum fails. Probe common GNSS baud
    // rates until the first clean sentence is decoded, then keep that baud for
    // the rest of the session.
    uint32_t now = millis();
    if (!s_baud_upgraded && now - s_last_valid_sentence_ms >= 8000u &&
        now - s_last_baud_probe_ms >= 8000u) {
        s_last_baud_probe_ms = now;
        s_baud_probe_index = (s_baud_probe_index + 1) %
                             (sizeof(GPS_BAUD_CANDIDATES) / sizeof(GPS_BAUD_CANDIDATES[0]));
        uint32_t next_baud = GPS_BAUD_CANDIDATES[s_baud_probe_index];
#ifdef AIRTAK_GPS_DEBUG
        Serial.printf("[GPS] no valid NMEA yet; probing baud %lu\n",
                      (unsigned long)next_baud);
#endif
        GPS_SERIAL.end();
        delay(10);
        begin_gps_serial(next_baud);
    }

#ifdef AIRTAK_GPS_DEBUG
    // Print diagnostics once every 10 seconds regardless of fix status.
    if (now - s_debug_last_print_ms >= 10000u) {
        s_debug_last_print_ms = now;
        Serial.printf("[GPS] uptime=%lus baud=%lu raw_bytes=%lu chars_parsed=%lu passed=%lu bad_checksums=%lu\n",
                      (unsigned long)(now / 1000u),
                      (unsigned long)s_current_baud,
                      (unsigned long)s_debug_raw_bytes,
                      (unsigned long)s_gps.charsProcessed(),
                      (unsigned long)s_gps.passedChecksum(),
                      (unsigned long)s_gps.failedChecksum());
        Serial.printf("[GPS] sats=%u hdop=%.1f lat=%.6f lng=%.6f fix=%s configured=%s\n",
                      s_current_fix.satellites,
                      s_current_fix.hdop,
                      s_current_fix.latitude,
                      s_current_fix.longitude,
                      s_current_fix.valid ? "YES" : "no",
                      s_baud_upgraded    ? "yes" : "NO");
    }
#endif
}

// ── GPS::currentFix() ────────────────────────────────────────────────────────
const GpsFix& GPS::currentFix() { return s_current_fix; }
bool GPS::hasFix() { return s_current_fix.valid; }

// ── GPS::setPower() ──────────────────────────────────────────────────────────
void GPS::setPower(bool on) {
    // Phase 6.2: GPS duty cycling via ENABLE pin (§9.5)
#if defined(AIRTAK_PIN_GPS_ENABLE) && AIRTAK_PIN_GPS_ENABLE >= 0
    digitalWrite(AIRTAK_PIN_GPS_ENABLE, on ? HIGH : LOW);
#else
    (void)on;
#endif
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

} // namespace airtak
