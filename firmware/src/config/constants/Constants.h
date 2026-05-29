// AirTAK — Airsoft Team Awareness Kit
// firmware/src/config/constants/Constants.h — Master centralized constants
//
// Include this header to access all AirTAK configurable constants.
// Each sub-header is fully self-documenting with descriptive comments.
// Use IDE hover to see where a constant is defined and its value.
//
// Categories:
//   CryptoConstants.h   — AES key sizes, nonce formats, CCM parameters, PKI overhead
//   RadioConstants.h    — LoRa PHY profiles, TX power, regions, SX1262 hardware
//   MeshConstants.h     — Hop limits, channel config, ACK params, packet pool, routing
//   DcConstants.h       — Duty cycle thresholds, TX log size, region limits
//   PortnumConstants.h  — Meshtastic + AirTAK mesh port numbers
//   BleConstants.h      — BLE MTU, framing overhead, UUIDs, queue types
//   DisplayConstants.h  — Per-board display dimensions, layout, colors
//   TimingConstants.h   — Task delays, intervals, timeouts, poll rates
//   SystemConstants.h   — Serial baud, task stacks, priorities, queue sizes, WDT
//   GpsConstants.h      — GPS baud rates, event bits, Earth radius
//   ButtonConstants.h   — Debounce, long press, double press timings
//   TouchConstants.h    — GT911 registers, I2C addresses, thresholds, max points
//   BatteryConstants.h  — ADC reference voltage, ADC max counts, calibration
//   GameConstants.h     — Player hit window, player color, status enum
//   AudioConstants.h    — Codec2 frame sizes, sample counts
//   RecoveryConstants.h — Recovery mode button hold timings
//   FlashConstants.h     — Flash block sizes, file paths, buffer sizes
//   FirmwareConstants.h — Firmware version and identity

#pragma once

#include "config/constants/CryptoConstants.h"
#include "config/constants/RadioConstants.h"
#include "config/constants/MeshConstants.h"
#include "config/constants/DcConstants.h"
#include "config/constants/PortnumConstants.h"
#include "config/constants/BleConstants.h"
#include "config/constants/DisplayConstants.h"
#include "config/constants/TimingConstants.h"
#include "config/constants/SystemConstants.h"
#include "config/constants/GpsConstants.h"
#include "config/constants/ButtonConstants.h"
#include "config/constants/TouchConstants.h"
#include "config/constants/BatteryConstants.h"
#include "config/constants/GameConstants.h"
#include "config/constants/AudioConstants.h"
#include "config/constants/RecoveryConstants.h"
#include "config/constants/FlashConstants.h"
#include "config/constants/FirmwareConstants.h"
