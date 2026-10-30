// AirTAK Android — config/constants/AdcConstants.kt
// Issue #1: per-device ADC battery-voltage multiplier.
//
// Each hardware device has a different default ADC multiplier (battery voltage
// divider calibration), historically hardcoded in firmware. AirTAK exposes it as
// a PowerConfig field (adc_multiplier, field 6) so the app can set it per device
// and sync it to the firmware over BLE.
//
// This file holds the canonical per-device default table (from the issue) and a
// board-id → multiplier lookup used to prefill the settings field. The firmware
// keeps its own compile-time per-board default and only uses a value from the
// config when it is non-zero (see ConfigManager::powerAdcMultiplier). A board
// that is not in the table falls back to ADC_MULTIPLIER_DEFAULT in the app, and
// to the board's own compile-time default on the device.

package com.airtak.config.constants

/**
 * Per-device default ADC multipliers, keyed by normalized board id.
 *
 * [defaultFor] normalizes the input (lowercase, dashes → underscores) so both the
 * firmware's AIRTAK_BOARD_ID strings (e.g. "heltec_v3", "t_deck", "t_echo_plus")
 * and hyphenated/human names ("t-deck", "t-echo") resolve to the same entry.
 */
object AdcMultiplierDefaults {

    private val TABLE: Map<String, Float> = mapOf(
        "chatter2" to 5.0f,
        "diy" to 1.85f,
        "esp32_s3_pico" to 3.1f,
        "heltec_v1" to 3.2f,
        "heltec_v2" to 3.2f,
        "heltec_v3" to 5.1205f,
        "heltec_wsl_v3" to 5.1205f,
        "heltec_v4" to 5.1205f,
        "heltec_wireless_paper" to 2.0f,
        "heltec_wireless_tracker" to 5.1205f,   // tracker v1.0/v1.1
        "heltec_t114" to 4.916f,
        "m5stack_coreink" to 5.0f,
        "nano_g1_explorer" to 2.0f,
        "nano_g2_ultra" to 2.0f,
        "picomputer_s3" to 3.1f,
        "rak4631" to 1.73f,
        "rpipico" to 3.1f,
        "rpipicow" to 3.1f,
        "station_g1" to 6.45f,
        "tlora_v2_1_16" to 2.0f,
        "tlora_v2_1_18" to 2.11f,
        "tlora_t3s3_v1" to 2.11f,
        "t3s3" to 2.11f,       // T3S3 board (matches tlora_t3s3_v1)
        "t_deck" to 2.11f,     // t-deck
        "t_echo" to 2.0f,      // t-echo
        // t_echo_plus is not in the issue table; match its firmware default (2.0)
        // so the app does not push the wrong value on apply.
        "t_echo_plus" to 2.0f,
    )

    /** Returns the table value for [boardId], or [ADC_MULTIPLIER_DEFAULT] if unknown. */
    fun defaultFor(boardId: String?): Float {
        if (boardId.isNullOrBlank()) return ADC_MULTIPLIER_DEFAULT
        val normalized = boardId.trim().lowercase().replace('-', '_')
        return TABLE[normalized] ?: ADC_MULTIPLIER_DEFAULT
    }
}
