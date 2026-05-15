// MASH — Mesh Airsoft Situational Hub
// firmware/include/config.h — global compile-time constants
// Planning: §7.1, §8.1, §8.2, §9.1 | Roadmap: 1.1
//
// Board and MCU-specific values live under src/platform/boards/. All user-
// configurable values live in config/Defaults.h and config/ConfigManager.
// Do not add runtime state or application logic here.

#pragma once

#include "platform/BoardConfig.h"

// ── Firmware identity ─────────────────────────────────────────────────────────
#define MASH_FIRMWARE_VERSION_MAJOR  1
#define MASH_FIRMWARE_VERSION_MINOR  0
#define MASH_FIRMWARE_VERSION_PATCH  0
#define MASH_FIRMWARE_VERSION_STR    "1.0.0"

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

// ── GPS UART ──────────────────────────────────────────────────────────────────
#define GPS_BAUD_INITIAL    9600     // initial baud rate
#define GPS_BAUD_FAST       115200   // upgraded baud rate after first valid NMEA sentence

// ── LoRa packet size limits ────────────────────────────────────────────────────
#define LORA_MAX_PAYLOAD_BYTES  255  // SX1262 hardware maximum
#define MESH_MAX_PAYLOAD_BYTES  237  // LoRa max minus headers and overhead
