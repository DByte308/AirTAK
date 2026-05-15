// MASH — Mesh Airsoft Situational Hub
// firmware/include/config.h — Global compile-time constants
// Planning: §7.1, §8.1, §8.2, §9.1 | Roadmap: 1.1
//
// This file contains hardware constants that must NOT change at runtime.
// All user-configurable values live in config/Defaults.h and config/ConfigManager.
// Do not add runtime state or application logic here.

#pragma once

// ── Firmware identity ─────────────────────────────────────────────────────────
#define MASH_FIRMWARE_VERSION_MAJOR  1
#define MASH_FIRMWARE_VERSION_MINOR  0
#define MASH_FIRMWARE_VERSION_PATCH  0
#define MASH_FIRMWARE_VERSION_STR    "1.0.0"

// ── nRF52840 hardware pin assignments (§7.1) ──────────────────────────────────
// MANDATORY: Verify all pins against the T-Echo Plus schematic before compilation.
// See firmware/boards/t_echo_plus.json for the full mapping with schematic notes.

// SX1262 LoRa radio (shared SPI bus)
#define PIN_SX1262_SCK    19   // P0.19
#define PIN_SX1262_MISO   23   // P0.23
#define PIN_SX1262_MOSI   22   // P0.22
#define PIN_SX1262_NSS    24   // P0.24  (chip select)
#define PIN_SX1262_RESET  25   // P0.25
#define PIN_SX1262_DIO1   20   // P0.20  (interrupt / busy)
#define PIN_SX1262_BUSY   17   // P0.17
#define PIN_SX1262_TXEN   45   // P1.13
#define PIN_SX1262_RXEN   46   // P1.14

// SSD1681 E-Ink display (dedicated SPI bus)
#define PIN_EINK_SCK      14   // P0.14
#define PIN_EINK_MOSI     13   // P0.13
#define PIN_EINK_CS       30   // P0.30
#define PIN_EINK_DC       28   // P0.28
#define PIN_EINK_RST       2   // P0.02
#define PIN_EINK_BUSY      3   // P0.03

// L76K GPS (UART)
#define PIN_GPS_RX_MCU    41   // P1.09  (MCU RX = GPS TX)
#define PIN_GPS_TX_MCU    40   // P1.08  (MCU TX = GPS RX)
#define PIN_GPS_PPS       36   // P1.04  (pulse-per-second; reserved, not used in logic)
#define PIN_GPS_ENABLE    34   // P1.02  (active high to power GPS)

// Battery and charging
#define PIN_VBAT_SENSE     4   // P0.04 AIN2 (ADC battery voltage sense)
#define PIN_CHG_STAT       5   // P0.05 (charge IC status, active low)

// User button
#define PIN_USER_BUTTON   42   // P1.10 (active low, requires INPUT_PULLUP)

// NFC (reserved — not used in MASH logic but must not be driven as GPIO)
#define PIN_NFC1           9   // P0.09
#define PIN_NFC2          10   // P0.10

// SPI NOR flash GD25Q32C — CS pin.
// MANDATORY: verify against T-Echo Plus schematic before first hardware flash.
// P1.00 (pin 32) is unallocated in the current mapping and is used as a
// placeholder until the schematic review confirms the actual CS line.
#define PIN_NOR_CS    32   // P1.00 — VERIFY AT SCHEMATIC REVIEW (§7.1)

// ── LoRa global parameters (§8.1) ─────────────────────────────────────────────
// These are hardware-level constants that apply to all channel slots.
// Per-slot PHY profiles (SF/BW/CR) live in LoraConfig (config/Defaults.h).

#define LORA_SYNC_WORD           0x2B    // Meshtastic network identifier — MUST NOT change
#define LORA_PREAMBLE_LENGTH     16      // symbols
#define LORA_TX_POWER_MAX        22      // dBm (SX1262 maximum)
#define LORA_FREQ_EU868          869.525 // MHz (EU 868 MHz, sub-band g1)
#define LORA_FREQ_US915          906.875 // MHz (US 915 MHz)

// ── BLE GATT service UUID ──────────────────────────────────────────────────────
// Service UUID: 4d415348-0000-1000-8000-00805f9b34fb
// Mnemonic: 0x4d415348 = ASCII "MASH"
// Characteristic UUID suffix values (see §10.1):
#define BLE_UUID_MASH_SERVICE     "4d415348-0000-1000-8000-00805f9b34fb"
#define BLE_UUID_CONFIG_RW        "4d415348-0001-1000-8000-00805f9b34fb"
#define BLE_UUID_MESH_TX          "4d415348-0002-1000-8000-00805f9b34fb"
#define BLE_UUID_MESH_RX          "4d415348-0003-1000-8000-00805f9b34fb"
#define BLE_UUID_DEVICE_STATUS    "4d415348-0004-1000-8000-00805f9b34fb"
#define BLE_UUID_ADMIN            "4d415348-0005-1000-8000-00805f9b34fb"

// ── E-Ink display ──────────────────────────────────────────────────────────────
#define EINK_WIDTH          200  // pixels
#define EINK_HEIGHT         200  // pixels
#define EINK_MIN_REFRESH_MS 60000  // 60 s minimum between full refreshes (§9.6)

// ── GPS UART ──────────────────────────────────────────────────────────────────
#define GPS_BAUD_INITIAL    9600     // initial baud rate
#define GPS_BAUD_FAST       115200   // upgraded baud rate after first valid NMEA sentence

// ── LoRa packet size limits ────────────────────────────────────────────────────
#define LORA_MAX_PAYLOAD_BYTES  255  // SX1262 hardware maximum
#define MESH_MAX_PAYLOAD_BYTES  237  // LoRa max minus headers and overhead
