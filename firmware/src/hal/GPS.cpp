// AirTAK — Airsoft Team Awareness Kit
// firmware/src/hal/GPS.cpp — L76K GPS UART driver implementation
// Planning: §9.5, §7.1 | Roadmap: 1.3

#include "hal/GPS.h"
#include "config/ConfigManager.h"
#include "config/Defaults.h"
#include "config/constants/Constants.h"
#include "config.h"
#include <Arduino.h>
#if AIRTAK_BOARD_HAS_GPS
#include <TinyGPS++.h>  // TinyGPS++ ≥ 1.0.3 (platformio.ini dependency)
#endif
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
// Runtime: board identity comes from the descriptor, not the compiled board macros.
#include "platform/esp32/system/BoardProvisioning.h"
#endif
#include <cstring>

namespace airtak {

// ── Static state ──────────────────────────────────────────────────────────────
#if AIRTAK_BOARD_HAS_GPS
static TinyGPSPlus s_gps;
#endif
static GpsFix      s_current_fix = {};
static double      s_last_tx_lat = 0.0;
static double      s_last_tx_lng = 0.0;
#if AIRTAK_BOARD_HAS_GPS
static bool        s_baud_upgraded = false;
static uint32_t    s_current_baud = GPS_BAUD_INITIAL;
static uint32_t    s_last_valid_sentence_ms = 0;
static uint32_t    s_last_baud_probe_ms = 0;
#endif

// External GPS fix (phone GPS via BLE SET_PHONE_GPS).
static GpsFix      s_external_fix = {};
static bool        s_using_external = false;

#if AIRTAK_BOARD_HAS_GPS
static constexpr uint32_t GPS_BAUD_CANDIDATES[] = {
    GPS_BAUD_INITIAL,
    GPS_BAUD_CANDIDATE_1,
    GPS_BAUD_CANDIDATE_4,
    GPS_BAUD_FAST,
};
static uint8_t s_baud_probe_index = 0;

#ifdef AIRTAK_GPS_DEBUG
static uint32_t    s_debug_raw_bytes    = 0;  // total bytes read from GPS UART
static uint32_t    s_debug_last_print_ms = 0; // last time we printed diagnostics
static char        s_debug_sentence[GPS_DEBUG_SENTENCE_BUF_SIZE] = {};
static uint8_t     s_debug_sentence_len = 0;
static uint8_t     s_debug_sentence_prints = 0;
#endif
#endif // AIRTAK_BOARD_HAS_GPS

#if AIRTAK_BOARD_HAS_GPS
// Board-selected UART used for the GNSS module. Under runtime the UART is the
// ESP32 hardware UART1 (Serial1) bound to the descriptor's gpsTx/gpsRx pins;
// the compiled-board macro applies only to the legacy build.
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
static HardwareSerial& GPS_SERIAL = Serial1;
#else
static HardwareSerial& GPS_SERIAL = AIRTAK_GPS_SERIAL;
#endif

static void begin_gps_serial(uint32_t baud) {
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    // Runtime: bind the descriptor's RX/TX pins on UART1. Guarded by gpsActive()
    // at the call site; only Serial1 is touched when hasGps and both pins present.
    Serial1.begin(baud, SERIAL_8N1,
                  airtak::platform::selectedBoard().gpsRx,
                  airtak::platform::selectedBoard().gpsTx);
#else
#if defined(AIRTAK_GPS_SERIAL_RX_PIN) && AIRTAK_GPS_SERIAL_RX_PIN >= 0
    GPS_SERIAL.begin(baud, SERIAL_8N1,
                     AIRTAK_GPS_SERIAL_RX_PIN, AIRTAK_GPS_SERIAL_TX_PIN);
#else
    GPS_SERIAL.begin(baud, SERIAL_8N1);
#endif
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
#endif // AIRTAK_BOARD_HAS_GPS

#if AIRTAK_BOARD_HAS_GPS
// ── PMTK command helpers ──────────────────────────────────────────────────────
static void send_pmtk(const char* cmd) {
    GPS_SERIAL.print(cmd);
    GPS_SERIAL.print("\r\n");
}
#endif

#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
// Runtime: GPS is only ever brought up when the selected board reports hasGps
// with valid gpsTx/gpsRx pins. Otherwise GPS stays a no-op (phone/external fix
// is the only position source) — no fake GPS on other boards.
static bool gpsActive() {
    const auto& bd = airtak::platform::selectedBoard();
    return bd.hasGps && bd.gpsTx >= 0 && bd.gpsRx >= 0;
}
#endif

// ── GPS::init() ───────────────────────────────────────────────────────────────
void GPS::init() {
    // If the device is configured to use phone GPS, don't power up the L76K.
    s_using_external = (config::ConfigManager::positionGpsSource() == 1);

#if AIRTAK_BOARD_HAS_GPS
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    // No hardware GPS on the selected board: keep phone fix only, no UART/pins.
    if (!gpsActive()) return;
#endif
    // Enable GPS power via dedicated GPIO (T-Echo Plus only; -1 = no pin).
#if defined(AIRTAK_PIN_GPS_ENABLE) && AIRTAK_PIN_GPS_ENABLE >= 0
    pinMode(AIRTAK_PIN_GPS_ENABLE, OUTPUT);
    digitalWrite(AIRTAK_PIN_GPS_ENABLE, s_using_external ? LOW : HIGH);
#endif
#if defined(AIRTAK_PIN_GPS_RESET) && AIRTAK_PIN_GPS_RESET >= 0
    pinMode(AIRTAK_PIN_GPS_RESET, OUTPUT);
    digitalWrite(AIRTAK_PIN_GPS_RESET, s_using_external ? LOW : HIGH);
#endif
    // PPS pin — configure as input but do not use in logic (reserved, §9.5)
#if defined(AIRTAK_PIN_GPS_PPS) && AIRTAK_PIN_GPS_PPS >= 0
    pinMode(AIRTAK_PIN_GPS_PPS, INPUT);
#endif

    // Start UART at initial baud rate (§9.5). If a previous firmware left the
    // module at GPS_BAUD_FAST, process() will auto-probe and recover.
    begin_gps_serial(GPS_BAUD_INITIAL);
#endif // AIRTAK_BOARD_HAS_GPS
}

// ── GPS::process() ───────────────────────────────────────────────────────────
void GPS::process(EventGroupHandle_t system_event_group) {
#if AIRTAK_BOARD_HAS_GPS
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    if (!gpsActive()) { (void)system_event_group; return; }
#endif
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
            if (!s_baud_upgraded && s_gps.charsProcessed() > GPS_MIN_CHARS_BEFORE_CONFIG) {
                // Restrict output to GNGGA + GNRMC only (saves ~80 % of UART traffic).
                // PMTK314 field order: GLL, RMC, VTG, GGA, GSA, GSV, ... (17 more) = 19 fields.
                send_pmtk("$PMTK314,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0*28");

                // Confirm 1 Hz update rate.
                send_pmtk("$PMTK220,1000*1F");

                s_baud_upgraded = true;
            }

            if (s_gps.location.isValid() && s_gps.hdop.isValid()) {
                float hdop = s_gps.hdop.hdop();
                s_current_fix.valid      = (hdop <= GPS_FIX_HDOP_MAX && s_gps.satellites.value() >= GPS_FIX_MIN_SATELLITES);
                s_current_fix.latitude   = s_gps.location.lat();
                s_current_fix.longitude  = s_gps.location.lng();
                s_current_fix.altitude_m = s_gps.altitude.meters();
                s_current_fix.hdop       = hdop;
                s_current_fix.satellites = s_gps.satellites.value();
                s_current_fix.accuracy_m = hdop * GPS_HDOP_TO_ACCURACY_MULT; // rough estimate
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
    if (!s_baud_upgraded && now - s_last_valid_sentence_ms >= GPS_BAUD_SWITCH_TIMEOUT_MS &&
        now - s_last_baud_probe_ms >= GPS_BAUD_SWITCH_TIMEOUT_MS) {
        s_last_baud_probe_ms = now;
        s_baud_probe_index = (s_baud_probe_index + 1) %
                             (sizeof(GPS_BAUD_CANDIDATES) / sizeof(GPS_BAUD_CANDIDATES[0]));
        uint32_t next_baud = GPS_BAUD_CANDIDATES[s_baud_probe_index];
#ifdef AIRTAK_GPS_DEBUG
        Serial.printf("[GPS] no valid NMEA yet; probing baud %lu\n",
                      (unsigned long)next_baud);
#endif
        GPS_SERIAL.end();
        delay(GPS_BAUD_SWITCH_DELAY_MS);
        begin_gps_serial(next_baud);
    }

#ifdef AIRTAK_GPS_DEBUG
    // Print diagnostics once every 10 seconds regardless of fix status.
    if (now - s_debug_last_print_ms >= GPS_DEBUG_PRINT_INTERVAL_MS) {
        s_debug_last_print_ms = now;
        Serial.printf("[GPS] uptime=%lus baud=%lu raw_bytes=%lu chars_parsed=%lu passed=%lu bad_checksums=%lu\n",
                      (unsigned long)(now / MS_TO_SECS),
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
#else
    (void)system_event_group;  // unused when no hardware GPS
#endif // AIRTAK_BOARD_HAS_GPS
}

// ── GPS::currentFix() ────────────────────────────────────────────────────────
const GpsFix& GPS::currentFix() {
    return s_using_external ? s_external_fix : s_current_fix;
}
bool GPS::hasFix() {
    return s_using_external ? s_external_fix.valid : s_current_fix.valid;
}

// ── GPS::setPower() ──────────────────────────────────────────────────────────
void GPS::setPower(bool on) {
    // Phase 6.2: GPS duty cycling via ENABLE pin (§9.5)
#if defined(AIRTAK_RUNTIME_BOARD) && defined(AIRTAK_PLATFORM_ESP32)
    // No GPS-ENABLE pin in the descriptor; power control is a no-op.
    (void)on;
#else
#if defined(AIRTAK_PIN_GPS_ENABLE) && AIRTAK_PIN_GPS_ENABLE >= 0
    digitalWrite(AIRTAK_PIN_GPS_ENABLE, on ? HIGH : LOW);
#else
    (void)on;
#endif
#endif
}

// ── External GPS fix (phone GPS) ─────────────────────────────────────────────

void GPS::setExternalFix(double lat, double lng, float alt_m, float acc_m) {
    s_external_fix.valid       = true;
    s_external_fix.latitude    = lat;
    s_external_fix.longitude   = lng;
    s_external_fix.altitude_m  = alt_m;
    s_external_fix.accuracy_m  = acc_m;
    s_external_fix.hdop        = (acc_m > 0.0f) ? (acc_m / GPS_HDOP_TO_ACCURACY_MULT) : GPS_HDOP_DEFAULT_FALLBACK;
    s_external_fix.satellites  = 0;   // not applicable for phone GPS
    s_external_fix.timestamp_ms = millis();
}

void GPS::setExternalSource(bool enabled) {
    s_using_external = enabled;
    if (enabled) {
        setPower(false);  // shut down L76K to save power
    } else {
        setPower(true);   // re-enable L76K
    }
}

bool GPS::usingExternalFix() {
    return s_using_external;
}

// ── Displacement and smart beaconing support ─────────────────────────────────
static double haversine_m(double lat1, double lon1, double lat2, double lon2);

float GPS::displacementFromLastTxMeters() {
    if (s_last_tx_lat == 0.0 && s_last_tx_lng == 0.0) return GPS_DISPLACEMENT_FORCE_TX_M; // force first TX
    const GpsFix& fix = currentFix();
    return (float)haversine_m(s_last_tx_lat, s_last_tx_lng,
                               fix.latitude, fix.longitude);
}

void GPS::markPositionTransmitted() {
    const GpsFix& fix = currentFix();
    s_last_tx_lat = fix.latitude;
    s_last_tx_lng = fix.longitude;
}

// Haversine formula — returns distance in meters
static double haversine_m(double lat1, double lon1, double lat2, double lon2) {
    double dlat = (lat2 - lat1) * M_PI / 180.0;
    double dlon = (lon2 - lon1) * M_PI / 180.0;
    double a = sin(dlat / 2) * sin(dlat / 2)
             + cos(lat1 * M_PI / 180.0) * cos(lat2 * M_PI / 180.0)
             * sin(dlon / 2) * sin(dlon / 2);
    return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
}

} // namespace airtak
