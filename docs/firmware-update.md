# AirTAK — Multi-MCU Firmware Update

## Status: Draft — 2026-05-19
### Source: PLANNING.md v1.7 §6.1 / ROADMAP.md deliverable 6.1 (revised)

This document defines the architecture, protocols, and implementation plan for flashing AirTAK firmware onto any supported board (nRF52840 or ESP32) via the Android app — USB-C for first flash and recovery, BLE wireless for subsequent updates. The user sees one "Update Firmware" button. The app selects the correct protocol automatically based on device identity and connection transport.

---

## Table of Contents

1. [Overview](#1-overview)
2. [User Journeys](#2-user-journeys)
3. [USB-C Wired Flashing](#3-usb-c-wired-flashing)
4. [BLE Wireless OTA](#4-ble-wireless-ota)
5. [Firmware Architecture](#5-firmware-architecture)
6. [Android Architecture](#6-android-architecture)
7. [Proto Changes](#7-proto-changes)
8. [Speed & UX](#8-speed--ux)
9. [Security](#9-security)
10. [Recovery Mode Interaction](#10-recovery-mode-interaction)
11. [Board Feature Flags](#11-board-feature-flags)
12. [Planning Amendment Impact](#12-planning-amendment-impact)

---

## 1. Overview

### 1.1 Design Principles

- **Single user entry point.** One "Update Firmware" button in Settings → About. The app auto-detects transport (USB-C or BLE) and device type (nRF52 or ESP32) and selects the correct protocol.
- **USB-C is the primary first-flash path.** No desktop, no PlatformIO, no script. Plug device into phone, open app, flash.
- **BLE is the field-update path.** After the first wire flash, all subsequent updates are wireless.
- **Platform-agnostic interfaces in firmware.** Shared header (`OtaService.h`) with per-architecture implementations, matching the existing `DFUService.h`, `FlashStore.h`, and `RecoveryMode.h` pattern.
- **Reuse proven protocols where they exist.** nRF52 uses the Nordic DFU bootloader already in flash. ESP32 uses its ROM bootloader (esptool) for wired and `esp_ota_begin/write/end` for wireless.

### 1.2 Supported Boards

| Board | MCU | Wired Path | Wireless Path |
|-------|-----|-----------|---------------|
| LilyGo T-Echo Plus | nRF52840 | USB-C serial (Nordic DFU protocol over CDC-ACM) | BLE (Nordic Buttonless DFU → SoftDevice bootloader) |
| LilyGo T-Echo | nRF52840 | Same as T-Echo Plus | Same |
| LilyGo T-Deck | ESP32-S3 | USB-C serial (esptool SLIP protocol) | BLE (custom OTA service → `esp_ota_*`) |
| LilyGo T3 S3 | ESP32-S3 | Same as T-Deck | Same |

### 1.3 Transport Matrix

```
                    ┌──────────┬─────────────────────┬──────────────────┐
                    │ nRF52840 │      ESP32-S3        │   Android API    │
┌───────────────────┼──────────┼─────────────────────┼──────────────────┤
│ USB-C serial      │ Nordic   │ esptool SLIP over   │ UsbManager +     │
│                   │ DFU over │ CDC-ACM             │ UsbDevice-       │
│                   │ CDC-ACM  │                     │ Connection        │
├───────────────────┼──────────┼─────────────────────┼──────────────────┤
│ BLE               │ Nordic   │ Custom BLE OTA      │ BluetoothGatt    │
│                   │ DFU      │ (AirTAK OTA chars)  │ + Nordic DFU lib │
└───────────────────┴──────────┴─────────────────────┴──────────────────┘
```

---

## 2. User Journeys

### Journey 1: First flash (factory device)

```
1. User receives a factory T-Echo Plus / T-Deck.
2. User plugs device into phone USB-C port with USB-C cable.
3. Android system dialog: "Open AirTAK to handle this device?"
4. User taps "Always" → AirTAK opens to Firmware screen.
5. App detects: "nRF52840 (T-Echo Plus) — Bootloader — No app firmware"
6. User taps "Select Firmware" → Android file picker for .bin / .uf2 / .zip
7. Progress bar: "Flashing firmware... 45%"
8. "Firmware flashed. Device will restart and connect over BLE."
9. App auto-connects via BLE → first-time setup flow begins.
```

### Journey 2: Wireless field update

```
1. User has a working AirTAK device paired over BLE.
2. User taps Settings → About → Update Firmware.
3. App shows current version and prompts for new firmware file.
4. User selects .bin / .zip from Downloads.
5. App reads file, calculates CRC32, sends OTA_ENTER command.
6. Progress bar with estimated time: "Updating T-Echo Plus... 60% (2 min left)"
7. Device reboots. App shows "Waiting for device..."
8. BLE reconnects. App verifies new version from DEVICE_STATUS.
9. "Updated to v1.2.3. All settings preserved."
```

### Journey 3: Recovery (corrupted firmware)

```
1. User holds USER button while powering on → Recovery Mode.
2. Device shows "SAFE MODE" on display, advertises as "AirTAK Recovery".
3. Android app detects recovery advertisement.
4. Same flow as Journey 1 from step 4 onward.
```

---

## 3. USB-C Wired Flashing

The Android app uses `android.hardware.usb.UsbManager` to communicate with the device bootloader over a USB CDC-ACM serial interface. No ADB, no root, no special kernel modules required — standard Android USB Host API.

### 3.1 Android USB Device Detection

AndroidManifest.xml declares intent filters with USB vendor IDs:

```xml
<!-- nRF52840 Adafruit bootloader (CDC-ACM serial DFU) -->
<usb-device vendor-id="0x239A" />

<!-- ESP32-S3 USB-Serial-JTAG (built-in) -->
<usb-device vendor-id="0x303A" />

<!-- CP210x USB-UART (ESP32 dev boards) -->
<usb-device vendor-id="0x10C4" />
<usb-device vendor-id="0x1A86" />
```

The app registers a broadcast receiver for `UsbManager.ACTION_USB_DEVICE_ATTACHED`. When a matching device is plugged in, the app auto-opens if the user has previously granted the "always open" permission.

### 3.2 nRF52840 — Nordic DFU over USB Serial

The Adafruit nRF52 bootloader (present on all AirTAK nRF52 boards) exposes a CDC-ACM serial endpoint alongside the UF2 mass storage device. The serial protocol is byte-for-byte identical to Nordic DFU over BLE — init packet, firmware chunks, CRC validation — transported over USB serial instead of BLE GATT.

**Protocol flow:**

```
Android (USB CDC-ACM)              nRF52 Bootloader
    │                                     │
    ├── DFU_OPCODE_START (0x01) ────────►│ Handshake + capabilities
    │◄── DFU_RESPONSE_SUCCESS ──────────┤
    │                                     │
    ├── CREATE_OBJECT (0x01) ───────────►│ Init packet (firmware metadata)
    │   type=INIT (0x01), size=N         │  • device type
    │   N bytes of init packet data       │  • firmware version
    │◄── CRC_OK ────────────────────────┤  • SoftDevice compatibility
    │                                     │
    ├── CREATE_OBJECT (0x01) ───────────►│ Firmware image (binary)
    │   type=DATA (0x02), size=M         │  Chunk size: 512 bytes
    │   M bytes of firmware data          │  PRN (Packet Receipt Notification)
    │   ... repeated ...                  │  after every 10 packets
    │◄── PRN(n) ◄───────────────────────┤  "got N packets, continue"
    │   ... more chunks ...              │
    │◄── CRC_OK ────────────────────────┤
    │                                     │
    ├── EXECUTE (0x04) ────────────────►│ Flashes the image
    │◄── DFU_RESPONSE_SUCCESS ──────────┤
    │                                     │
    ├── DFU_OPCODE_RESET (0x08) ───────►│ Reboot into new firmware
    │◄── (device disconnects) ──────────┤
```

**OPCODE reference:**

| Opcode | Value | Description |
|--------|-------|-------------|
| START | 0x01 | Initialize DFU session |
| INIT_DFU_PARAMS | 0x02 | Set PRN interval |
| RECEIVE_FIRMWARE | 0x03 | Deprecated — not used |
| CREATE_OBJECT | 0x01 | Create init or data object |
| SET_PRN | 0x02 | Set packet receipt notification |
| CALCULATE_CRC | 0x03 | Request CRC of received object |
| EXECUTE | 0x04 | Execute the received object |
| SELECT_OBJECT | 0x06 | Select object type |
| GET_MTU | 0x07 | Negotiate maximum transfer unit |
| WRITE_PACKET | 0x08 | Write a data packet |
| PING | 0x09 | Keep-alive |
| RESET_OBJECT | 0x0A | Abort current object |
| DFU_OPCODE_RESET | 0x08 | Reboot device |

**Baud rate negotiation:** Initial connection at 115200. After handshake, negotiate to 921600 if the bootloader supports it. Typical flash time for ~600 KB firmware: 35–60 seconds.

### 3.3 ESP32 — esptool Protocol over USB Serial

The ESP32 ROM bootloader (in mask ROM, always available) speaks the esptool SLIP protocol over the USB-Serial-JTAG or CP210x UART bridge.

**Protocol flow:**

```
Android (USB CDC-ACM)               ESP32 ROM Bootloader
    │                                        │
    ├── SYNC (0x08000000, 36× 0x55) ──────►│ Autobaud detection
    │◄── 0x00 (OK) ────────────────────────┤ Response at 115200
    │                                        │
    ├── CHANGE_BAUDRATE (0x0F) ───────────►│ Switch to 921600
    │   new=921600, old=115200              │
    │◄── 0x00 (OK) ────────────────────────┤
    │   (app reconfigures baud)             │
    │                                        │
    ├── SPI_FLASH_PARAMS ─────────────────►│ Read flash size, block size
    │◄── flash_size=16MB, block=4096 ─────┤
    │                                        │
    ├── FLASH_BEGIN (0x02) ───────────────►│
    │   offset=0x10000, size=N,            │ Erase flash region
    │   block_size=4096                     │
    │◄── 0x00 (OK) ────────────────────────┤
    │                                        │
    ├── FLASH_DATA (0x03) ────────────────►│ Write flash chunk
    │   seq=0, data=[4096 bytes]           │ (uncompressed)
    │◄── 0x00 (OK) ────────────────────────┤
    │   ... repeated ...                    │
    │                                        │
    ├── FLASH_END (0x04) ────────────────►│ Verify + reboot
    │   reboot=true                         │
    │◄── (device disconnects) ─────────────┤
```

**SLIP framing:** All commands and responses are wrapped in SLIP (`0xC0` frame delimiter, `0xDB 0xDC` for escaped `0xC0`, `0xDB 0xDD` for escaped `0xDB`). Command format: `[0x00] [opcode] [length_lo] [length_hi] [checksum] [data...] [0xC0]`. Response format: `[0x01] [opcode] [length_lo] [length_hi] [value] [data...] [0xC0]`.

**ESP32 bootloader entry:** On ESP32-S3, the ROM bootloader is entered by holding GPIO 0 low during reset. The T-Deck BOOT button (GPIO 0) is physically accessible. The T3 S3 also uses GPIO 0. For devices without an accessible BOOT button, the ROM bootloader can be entered via `esp_restart()` from a running firmware image that sets the strapping pin — but this requires firmware already on the device. The first-flash path therefore depends on the user pressing the BOOT button. The Android app displays a prompt: "Hold BOOT button and press RESET on your T-Deck."

**Android implementation:** The esptool protocol is implemented in Kotlin directly (no JNI). The protocol is ~10 commands with well-defined SLIP framing. A reference implementation exists in `esptool.js` (Espressif's Web Flasher, MIT licensed).

---

## 4. BLE Wireless OTA

### 4.1 Protocol Selection by Device Type

When the Android app is connected over BLE, it reads `DEVICE_STATUS` to determine the device identity. The `bluetooth_address` field and advertised manufacturer data identify the MCU:

| Device | BLE Advertisement | Protocol |
|--------|------------------|----------|
| nRF52840 | Adafruit Bluefruit (manufacturer 0x0822) | Nordic DFU over BLE |
| ESP32-S3 | NimBLE with AirTAK service UUID | Custom BLE OTA |

### 4.2 nRF52840 — Nordic DFU over BLE

The nRF52840 already has the Nordic Buttonless DFU service registered alongside the AirTAK GATT service (see `firmware/src/platform/nrf52/ble/DFUService.cpp`).

**Flow:**

```
Android App                         nRF52840 (App Mode)
    │                                     │
    ├── ADMIN_OTA_ENTER (BLE) ──────────►│ AdminRequest{command=OTA_ENTER}
    │◄── AdminResponse{success=true} ────┤
    │                                     │
    │   (device disconnects,              │ calls enterOTADfu()
    │    reboots into bootloader)         │ → SoftDevice bootloader
    │                                     │
    │   (app scans for bootloader)        │ Advertises as "DfuTarg"
    │◄── DFU Service discovered ────────┤ UUID: 0000FE59-...
    │                                     │
    │   === Nordic DFU protocol ===       │
    │   Init packet + firmware upload     │ (identical to USB serial flow §3.2)
    │                                     │
    │   (device reboots into new fw)     │
    │◄── AirTAK Service discovered ──────┤
    │   Verify version from DEVICE_STATUS │
```

**Nordic DFU BLE GATT Service:** UUID `0000FE59-0000-1000-8000-00805F9B34FB`
- **DFU Control Point** (Write/Notify): same UUID — write commands, read/notify responses
- **DFU Packet** (Write w/o Response): `8EC90003-F315-4F60-9FB8-838830DAEA50`
- **DFU Version** (Read): `8EC90004-F315-4F60-9FB8-838830DAEA50`

**Buttonless DFU trigger** (already registered by `DFUService::init()`):
- Service UUID: `8EC90004-F315-4F60-9FB8-838830DAEA50`
- Enter Bootloader char: `8E400001-F315-4F60-9FB8-838830DAEA50` — write `0x01`

**Android library:** `no.nordicsemi.android:dfu:2.11.0` handles the full protocol including init-packet parsing, firmware image chunking, CRC validation, and progress callbacks.

**Speed:** BLE 5.0 with MTU 517 → ~4–7 KB/s. A ~600 KB firmware image takes ~90–150 seconds.

### 4.3 ESP32 — Custom BLE OTA

ESP32 has no standard BLE DFU service. AirTAK implements a custom OTA service that leverages ESP-IDF's `esp_ota_begin/write/end` APIs.

**New GATT characteristics** (registered in the existing AirTAK service on ESP32 only):

| Characteristic | UUID | Properties | Description |
|---------------|------|------------|-------------|
| `OTA_COMMAND` | `4d415348-0006-1000-8000-00805f9b34fb` | Write, Notify | Enter OTA mode, finish, report status |
| `OTA_DATA` | `4d415348-0007-1000-8000-00805f9b34fb` | Write w/o Response | Firmware chunk data |

Both require authenticated connection (Security Mode 1, Level 3).

**Protocol flow:**

```
Android App                         ESP32 (AirTAK Firmware)
    │                                         │
    ├── OTA_COMMAND write ──────────────────►│ AdminRequest{command=OTA_ENTER}
    │   OtaStart{image_size=N, crc32=K}      │ → OtaService::begin()
    │◄── OTA_COMMAND notify ────────────────┤   OtaStatus{state=RECEIVING}
    │                                         │
    ├── OTA_DATA write ─────────────────────►│ OtaService::writeChunk()
    │   chunk[0..511] (512 bytes)            │ → esp_ota_write(handle, data, 512)
    ├── OTA_DATA write ─────────────────────►│
    │   chunk[512..1023]                     │
    │   ... repeated ...                      │
    │   (every 50 chunks, request status)   │
    │                                         │
    ├── OTA_COMMAND write ──────────────────►│ Read progress
    │   OtaStatusRequest{}                   │
    │◄── OTA_COMMAND notify ────────────────┤
    │   OtaStatus{bytes_written=262144,       │
    │             total=N}                    │
    │                                         │
    │   ... all chunks sent ...              │
    │                                         │
    ├── OTA_COMMAND write ──────────────────►│
    │   OtaFinish{crc32=K}                   │
    │◄── OTA_COMMAND notify ────────────────┤ validates CRC
    │   OtaStatus{state=VERIFIED}            │ → esp_ota_end()
    │                                         │ → esp_ota_set_boot_partition()
    │                                         │ → esp_restart()
    │   (device disconnects)                  │
    │                                         │
    │   === ESP32 reboots into new fw ===     │
    │◄── AirTAK Service discovered ──────────┤
    │   Verify version from DEVICE_STATUS     │
```

**OTA partition layout (ESP32):** The ESP32 partition table must define two OTA app slots, an OTA data partition, and a factory partition:

```
# partitions.csv (ESP32 target)
nvs,      data, nvs,     0x9000,   0x6000
otadata,  data, ota,     0xF000,   0x2000
phy_init, data, phy,     0x11000,  0x1000
factory,  app,  factory, 0x12000,  0x1E0000
ota_0,    app,  ota_0,   0x1F2000, 0x1E0000
ota_1,    app,  ota_1,   0x3D2000, 0x1E0000
coredump, data, coredump,0x5B2000, 0x10000
```

This is already standard for any ESP32 build; no change needed.

**Rollback protection:** The ESP32 OTA mechanism supports rollback. The new image boots in `ESP_OTA_IMG_PENDING_VERIFY` state. The app firmware must call `esp_ota_mark_app_valid_cancel_rollback()` on first successful boot. If the device crashes during boot, the bootloader automatically reverts to the previous image.

**Speed:** BLE 5.0 with MTU 517 → ~3–5 KB/s (ESP32 NimBLE stack is slightly slower than nRF52 SoftDevice). A ~1.5 MB ESP32 firmware image takes ~5–7 minutes. Progress is reported every 50 chunks (~2.5% granularity).

### 4.4 OTA_COMMAND / OTA_DATA Payload Format

**OTA_COMMAND (Write):**

```protobuf
// Sent by Android app to control the OTA session.
message OtaCommand {
    oneof command {
        OtaStart start = 1;        // begin OTA: image_size, crc32
        OtaFinish finish = 2;      // finalize OTA: crc32 of received data
        OtaStatusRequest status = 3; // request current progress
        OtaCancel cancel = 4;      // abort (any state)
    }
}

message OtaStart {
    uint32 image_size = 1;  // total firmware binary size in bytes
    uint32 crc32 = 2;       // expected CRC32 of full firmware
}

message OtaFinish {
    uint32 crc32 = 1;       // CRC32 computed by app of all sent chunks
}

message OtaStatusRequest {}

message OtaCancel {}
```

**OTA_COMMAND (Notify — device → app):**

```protobuf
message OtaStatus {
    OtaState state = 1;         // current state
    uint32 bytes_written = 2;   // bytes received and flashed so far
    uint32 total_size = 3;      // expected total from OtaStart
    uint32 last_error = 4;      // ESP error code (0 = no error)
    string error_text = 5;      // human-readable (optional)
}

enum OtaState {
    OTA_IDLE = 0;           // idle, no OTA session active
    OTA_RECEIVING = 1;      // accepting data chunks
    OTA_VERIFYING = 2;      // CRC checking after all chunks received
    OTA_WRITING = 3;        // final flash write
    OTA_COMPLETE = 4;       // success, rebooting
    OTA_ERROR = 5;          // failed (see last_error / error_text)
}
```

**OTA_DATA (Write w/o Response):** Raw binary chunk. No protobuf wrapping — direct byte write for speed. Chunk size is `MTU - 3` (3 bytes for the GATT write header). At MTU 517, chunk size = 514 bytes.

---

## 5. Firmware Architecture

### 5.1 OtaService — Shared Interface

`firmware/src/ota/OtaService.h`

```cpp
// AirTAK — Airsoft Team Awareness Kit
// firmware/src/ota/OtaService.h — platform-agnostic OTA firmware update
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Called by AirTAKService when an OTA_COMMAND or OTA_DATA write is received
// over BLE.  Platform-specific implementation under src/platform/<arch>/ota/.

#pragma once

#include <cstddef>
#include <cstdint>

namespace airtak {
namespace ota {

// Matches OtaState in ble.proto
enum class State : uint8_t {
    Idle       = 0,
    Receiving  = 1,
    Verifying  = 2,
    Writing    = 3,
    Complete   = 4,
    Error      = 5,
};

class OtaService {
public:
    // Begin OTA session.  Called when OtaStart command is received.
    // Returns false if OTA is not supported or partition is invalid.
    static bool begin(size_t imageSize, uint32_t expectedCrc);

    // Write a chunk of firmware data.  Called for each OTA_DATA write.
    // Returns false on flash write failure.
    static bool writeChunk(const uint8_t* data, size_t len);

    // Finalize OTA.  Validates CRC, writes boot partition, returns true
    // if rebooting is imminent.  Never returns on success (esp_restart / reboot).
    // Returns false if CRC mismatch.
    static bool finish(uint32_t expectedCrc);

    // Cancel in-progress OTA.  Safe to call from any state.
    static void cancel();

    // Current state and progress.
    static State  state();
    static size_t bytesWritten();
    static size_t totalSize();
    static uint32_t lastError();   // platform-specific error code
    static const char* errorText();
};

} // namespace ota
} // namespace airtak
```

### 5.2 nRF52840 Implementation

`firmware/src/platform/nrf52/ota/OtaService.cpp`

The nRF52 implementation **delegates to the existing bootloader**. The firmware does not write its own flash — it triggers the bootloader entry via `DFUService::enterDfu()`.

```cpp
bool OtaService::begin(size_t /*imageSize*/, uint32_t /*expectedCrc*/) {
    // nRF52 OTA is handled by the SoftDevice bootloader.
    // begin() is a no-op here — the actual flash occurs after bootloader entry.
    _state = State::Receiving;
    return true;
}

bool OtaService::writeChunk(const uint8_t* /*data*/, size_t /*len*/) {
    // No-op on nRF52.  Chunks are sent via Nordic DFU protocol after
    // the device enters bootloader mode.
    return true;
}

bool OtaService::finish(uint32_t /*expectedCrc*/) {
    // Trigger bootloader entry.  The Android app will then use the
    // Nordic DFU BLE protocol to upload the firmware image.
    _state = State::Complete;
    ble::DFUService::enterDfu();   // calls enterOTADfu() → never returns
    return true;
}
```

The static methods silently accept all state transitions. The Android app is responsible for the actual data transfer — it switches to Nordic DFU protocol after the `OTA_ENTER` ACK. This approach avoids duplicating flash programming logic that the bootloader already handles correctly.

### 5.3 ESP32 Implementation

`firmware/src/platform/esp32/ota/OtaService.cpp`

```cpp
#include "ota/OtaService.h"
#include <esp_ota_ops.h>
#include <esp_partition.h>
#include <esp_system.h>

static esp_ota_handle_t s_otaHandle = 0;
static const esp_partition_t* s_otaPart = nullptr;
static size_t s_bytesWritten = 0;
static size_t s_totalSize = 0;
static uint32_t s_expectedCrc = 0;

bool OtaService::begin(size_t imageSize, uint32_t expectedCrc) {
    s_totalSize    = imageSize;
    s_expectedCrc  = expectedCrc;
    s_bytesWritten = 0;

    s_otaPart = esp_ota_get_next_update_partition(nullptr);
    if (!s_otaPart) {
        _lastError = ESP_ERR_NOT_FOUND;
        _state = State::Error;
        return false;
    }

    esp_err_t err = esp_ota_begin(s_otaPart, imageSize, &s_otaHandle);
    if (err != ESP_OK) {
        _lastError = err;
        _state = State::Error;
        return false;
    }

    _state = State::Receiving;
    return true;
}

bool OtaService::writeChunk(const uint8_t* data, size_t len) {
    esp_err_t err = esp_ota_write(s_otaHandle, data, len);
    if (err != ESP_OK) {
        _lastError = err;
        _state = State::Error;
        return false;
    }
    s_bytesWritten += len;
    return true;
}

bool OtaService::finish(uint32_t expectedCrc) {
    // CRC validated by Android app before calling finish().
    // The app sends OtaFinish only after computing CRC of all sent chunks.
    // We compare as a final sanity check.
    if (expectedCrc != s_expectedCrc) {
        _lastError = 0xFFFFFFFF;
        _state = State::Error;
        return false;
    }

    _state = State::Verifying;

    esp_err_t err = esp_ota_end(s_otaHandle);
    if (err != ESP_OK) {
        _lastError = err;
        _state = State::Error;
        return false;
    }

    _state = State::Writing;

    err = esp_ota_set_boot_partition(s_otaPart);
    if (err != ESP_OK) {
        _lastError = err;
        _state = State::Error;
        return false;
    }

    _state = State::Complete;
    esp_restart(); // never returns
    return true;
}

void OtaService::cancel() {
    if (s_otaHandle) {
        esp_ota_end(s_otaHandle);
        s_otaHandle = 0;
    }
    _state = State::Idle;
}
```

**CRC consideration:** The firmware does not compute CRC of incoming chunks in real time (ESP32 CRC32 hardware acceleration could do it, but doing it per-514-byte-chunk on BLE write callback adds jitter). Instead, the Android app computes CRC32 of the entire firmware file before sending and passes it in `OtaFinish`. The firmware compares the received `expectedCrc` against the original `expectedCrc` from `OtaStart` as a final gate — ensuring the same file that was CRC-verified by the app is what the firmware expects.

### 5.4 Integration with AirTAKService

The existing `AirTAKService` (BLE GATT server) handles the new OTA characteristics. On ESP32, `AirTAKService::init()` registers two additional characteristics when `AIRTAK_BOARD_HAS_BLE_OTA` is defined. Write callbacks are routed to `OtaService::writeChunk()` and `OtaService::begin()`/`OtaService::finish()`.

---

## 6. Android Architecture

### 6.1 Module: `data/ota/`

```
android/app/src/main/kotlin/com/airtak/data/ota/
├── FirmwareUpdateManager.kt    // single entry point, transport auto-detection
├── UpdateTransport.kt           // sealed interface for USB / BLE I/O
├── NrfSerialDfuEngine.kt      // Nordic DFU over USB CDC-ACM
├── NrfBleDfuEngine.kt          // Nordic DFU over BLE (wraps Nordic library)
├── EspSerialFlasher.kt         // esptool SLIP over USB CDC-ACM
├── EspBleOtaEngine.kt          // Custom BLE OTA for ESP32
├── SlipFramer.kt               // SLIP encode/decode utility
└── ProgressReporter.kt         // shared progress tracking
```

### 6.2 FirmwareUpdateManager

```kotlin
class FirmwareUpdateManager(
    private val context: Context
) {
    // Auto-detects transport from current connection state
    suspend fun prepareUpdate(firmwareUri: Uri): Result<UpdateSession>

    inner class UpdateSession(
        val deviceInfo: DeviceInfo,    // from DEVICE_STATUS or USB descriptor
        val transport: Transport,       // USB-C or BLE
        private val engine: FirmwareUpdateEngine
    ) {
        // Returns a cold Flow emitting progress updates
        suspend fun execute(): Flow<UpdateProgress>

        // Current state
        val canRetry: Boolean          // true on failure, transport still connected
        val requiresUserAction: String? // "Hold BOOT, press RESET" etc.
    }
}

sealed class Transport {
    class UsbCdc(
        val connection: UsbDeviceConnection,
        val intf: UsbInterface
    ) : Transport()

    class BleGatt(
        val bluetoothGatt: BluetoothGatt
    ) : Transport()
}

sealed class UpdateProgress {
    data class Initializing(val step: String) : UpdateProgress()
    data class Transferring(
        val bytesWritten: Long,
        val totalBytes: Long,
        val etaSeconds: Int
    ) : UpdateProgress()
    data class Verifying(val progress: Float) : UpdateProgress()
    object Rebooting : UpdateProgress()
    data class Reconnected(val newVersion: String) : UpdateProgress()
    data class Error(val message: String, val canRetry: Boolean) : UpdateProgress()
}

interface FirmwareUpdateEngine {
    suspend fun initialize(): Result<DeviceInfo>
    suspend fun transfer(firmware: ByteArray, onProgress: (UpdateProgress) -> Unit): Result<Unit>
    suspend fun finalize(): Result<Unit>
    suspend fun waitForReconnect(timeoutMs: Long): Result<String> // new version
}
```

### 6.3 NrfSerialDfuEngine (USB-C)

Implements the Nordic DFU protocol over a `UsbDeviceConnection` raw bulk transfer. Uses the protocol defined in §3.2.

**Chunk size:** 512 bytes (bootloader maximum per `WRITE_PACKET`).
**PRN interval:** 10 packets (acknowledge every 5 KB).
**Baud rate:** Start at 115200, negotiate to 921600 if bootloader supports it.

**Dependencies:**
- `android.hardware.usb.UsbManager`
- `android.hardware.usb.UsbDeviceConnection`
- No third-party DFU library — implements the protocol directly for USB transport (the Nordic DFU BLE library is BLE-only)

### 6.4 NrfBleDfuEngine (BLE wireless)

Wraps `no.nordicsemi.android:dfu:2.11.0`.

**Flow:**
1. Send `AdminRequest{command=OTA_ENTER}` via AirTAK ADMIN characteristic
2. Wait for `AdminResponse{success=true}` + BLE disconnect
3. Scan for bootloader advertisement (device name "DfuTarg", service `0000FE59-...`)
4. Initiate `DfuServiceInitiator` with firmware `.zip` URI
5. Nordic library handles init packet parsing, image chunking, CRC, and progress
6. On completion, scan for AirTAK service, reconnect, read `DEVICE_STATUS.firmware_version`

### 6.5 EspSerialFlasher (USB-C)

Implements the esptool SLIP protocol over USB CDC-ACM. Written in pure Kotlin (no JNI).

**SLIP Framer** (`SlipFramer.kt`):
```kotlin
object SlipFramer {
    const val END = 0xC0.toByte()
    const val ESC = 0xDB.toByte()
    const val ESC_END = 0xDC.toByte()
    const val ESC_ESC = 0xDD.toByte()

    fun encode(payload: ByteArray): ByteArray
    fun decode(buffer: ByteArray): List<ByteArray>
}
```

**EspSerialFlasher** structure:
```kotlin
class EspSerialFlasher(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface
) : FirmwareUpdateEngine {

    companion object {
        const val DEFAULT_BAUD = 115200
        const val HIGH_BAUD = 921600
        const val BLOCK_SIZE = 4096  // ESP32 flash block size
    }

    override suspend fun initialize(): Result<DeviceInfo> {
        // 1. Open USB interface, claim endpoint
        // 2. Send 36× 0x55 sync sequence
        // 3. Receive ESP_OK response at 115200
        // 4. Send CHANGE_BAUDRATE → switch to 921600
        // 5. Send SPI_FLASH_PARAMS → read chip info
        // 6. Return DeviceInfo(mcuid="ESP32-S3", flashSize=..., ...)
    }

    override suspend fun transfer(firmware: ByteArray, onProgress: (UpdateProgress) -> Unit): Result<Unit> {
        // 1. Send FLASH_BEGIN(offset=0x10000, size=firmware.size, block=4096)
        // 2. For each 4096-byte block:
        //    a. FLASH_DATA(seq, data) → ESP_OK
        //    b. onProgress(Transferring(bytesWritten, ...))
        // 3. Send FLASH_END(reboot=false)
        // 4. Wait for ESP_OK + digest
    }

    override suspend fun finalize(): Result<Unit> {
        // Send FLASH_END with reboot=true OR send a separate reset command
    }
}
```

### 6.6 EspBleOtaEngine (BLE wireless)

Implements the custom BLE OTA protocol defined in §4.3.

```kotlin
class EspBleOtaEngine(
    private val bluetoothGatt: BluetoothGatt
) : FirmwareUpdateEngine {

    companion object {
        const val CHUNK_SIZE = 514  // MTU(517) - 3
        const val STATUS_EVERY_N_CHUNKS = 50
    }

    override suspend fun initialize(): Result<DeviceInfo> {
        // Already connected — read DEVICE_STATUS for device info
    }

    override suspend fun transfer(firmware: ByteArray, onProgress: (UpdateProgress) -> Unit): Result<Unit> {
        val crc = CRC32().apply { update(firmware) }.value

        // 1. Send OtaStart{image_size, crc32} via OTA_COMMAND
        // 2. Wait for OtaStatus{state=RECEIVING}
        // 3. Split firmware into CHUNK_SIZE chunks
        // 4. Write each chunk to OTA_DATA (Write w/o Response)
        // 5. Every 50 chunks: request OTA_STATUS, update progress
        // 6. Send OtaFinish{crc32} via OTA_COMMAND
        // 7. Wait for OtaStatus{state=COMPLETE}
    }
}
```

---

## 7. Proto Changes

### 7.1 Extended AdminCommand

`proto/airtak/ble.proto` — add two commands:

```protobuf
enum AdminCommand {
    // ... existing entries 0–6 ...
    OTA_ENTER = 7;   // enter OTA mode; BLE OTA or Nordic DFU depending on platform
    OTA_FINISH = 8;  // finalize OTA (CRC check + reboot on ESP32; no-op on nRF52)
}
```

**`OTA_ENTER` behavior by platform:**

| Platform | Action |
|----------|--------|
| nRF52840 | Send ACK. Android switches to Nordic DFU BLE protocol after this. |
| ESP32 | Calls `OtaService::begin()`. Sends ACK with `OtaStatus{state=RECEIVING}`. |

**`OTA_FINISH` behavior by platform:**

| Platform | Action |
|----------|--------|
| nRF52840 | No-op (Nordic DFU protocol handles finalization). |
| ESP32 | Calls `OtaService::finish()`. Sends ACK with `OtaStatus{state=COMPLETE}`. |

### 7.2 New Messages for ESP32 OTA

Added to `proto/airtak/ble.proto`:

```protobuf
// ── OTA (firmware update over BLE, ESP32) ─────────────────────────────
// OtaCommand, OtaStatus, and OtaState as defined in §4.4 above.
```

Full definitions as in §4.4. These are only used by the ESP32 BLE OTA path; nRF52 OTA uses the Nordic DFU protocol outside of AirTAK's protobuf channel.

### 7.3 Extended DeviceStatus

Add a field to `DeviceStatus` for board identification:

```protobuf
message DeviceStatus {
    // ... existing fields 1–15 ...
    string board_id = 16;   // matches AIRTAK_BOARD_ID: "t_echo_plus", "t_deck", etc.
    string platform = 17;   // "nRF52840", "ESP32-S3", etc.
}
```

These fields allow the Android app to select the correct OTA protocol without relying on BLE advertisement heuristics.

---

## 8. Speed & UX

### 8.1 Transfer Speed Estimates

| Path | MCU | Firmware Size | Transport | Throughput | Time |
|------|-----|--------------|-----------|------------|------|
| USB-C | nRF52840 | ~600 KB | USB CDC-ACM @ 921600 | ~75 KB/s | ~8 s |
| USB-C | ESP32 | ~1.5 MB | USB CDC-ACM @ 921600 | ~90 KB/s | ~17 s |
| BLE | nRF52840 | ~600 KB | BLE 5.0 MTU 517 | ~5 KB/s | ~2 min |
| BLE | ESP32 | ~1.5 MB | BLE 5.0 MTU 517 | ~4 KB/s | ~6.5 min |

USB-C is fast — the bottleneck is flash write speed, not USB throughput.

### 8.2 User Interface States

```
[Idle] ──→ [Detecting] ──→ [Ready: "T-Echo Plus, v1.2.0 → v1.2.3"]
                               │
                     ┌─────────┴─────────┐
                     ▼                   ▼
          [Transferring: ████░░ 52%]   [Error: "Device disconnected.
            "3 min remaining"            Reconnect and retry?"]
                     │                   │
                     ▼                   └──→ [Ready] (retry)
          [Verifying...]                      │
                     │                        └──→ [Aborted]
                     ▼
          [Rebooting... "Device restarting,
           reconnecting in a few seconds..."]
                     │
                     ▼
          [Done: "Updated to v1.2.3"]
```

### 8.3 Error Recovery

| Error | Recovery |
|-------|----------|
| USB disconnect during flash | App prompts to reconnect. Bootloader remains active (nRF52: until power cycle; ESP32: until timeout/reset). Retry from FLASH_BEGIN. |
| BLE disconnect during OTA | App reconnects. On ESP32: `OtaService::cancel()` aborts the partial write; OTA must restart from `OtaStart`. Bootloader rollback not triggered because `esp_ota_end()` was never called. |
| CRC mismatch on ESP32 | `OtaService::finish()` returns `false`. App retries the full transfer. |
| Device fails to boot after OTA | ESP32 rollback: bootloader reverts to previous image. nRF52: no automatic rollback; user must enter Recovery Mode (hold USER button at boot). |
| OTA interrupted (ESP32 power loss) | Partial write at `ota_0`. Bootloader sees `ota_0` as invalid (no `esp_ota_end()` called), stays on current image. Retry OTA. |

---

## 9. Security

### 9.1 BLE Security

- **All OTA characteristics require Security Mode 1, Level 3** (LE Secure Connections, encrypted + authenticated). This is the same requirement as the existing `CONFIG_RW`, `MESH_TX`, and `ADMIN` characteristics.
- **No anonymous OTA.** Only bonded devices can enter OTA mode.
- **Pairing is LE Secure Connections + Numeric Comparison** per §10.6 of PLANNING.md. MITM protection is mandatory.

### 9.2 Firmware Integrity

- **CRC32 end-to-end.** The Android app computes CRC32 of the firmware file before transmission. For ESP32 BLE OTA, this CRC is sent in `OtaStart` and validated in `OtaFinish`. For nRF52 Nordic DFU, CRC is built into the init packet and validated by the bootloader.
- **No firmware signing (Phase 1–6).** Secure boot with EC/RSA signatures is deferred. At this stage, physical possession of the device + authenticated BLE bond is the security boundary. Signed firmware is a Phase 7+ enhancement.
- **Rollback protection (ESP32 only).** The ESP32 bootloader supports automatic rollback if the new image crashes during boot. nRF52 does not have automatic rollback; Recovery Mode provides the manual recovery path.

### 9.3 Admin Confirm Token

`OTA_ENTER` and `OTA_FINISH` do not require a confirm token (unlike `FACTORY_RESET` and `PAIR_CLEAR`). The authenticated BLE bond is sufficient. In-field firmware updates are a normal operations workflow, not a destructive action.

---

## 10. Recovery Mode Interaction

The existing Recovery Mode (`firmware/src/system/RecoveryMode.h`, per-platform implementations in `firmware/src/platform/<arch>/system/RecoveryMode.cpp`) is the fallback when a firmware update fails catastrophically.

**Recovery Mode BLE advertisement:**

| Platform | BLE Name | Services |
|----------|----------|----------|
| nRF52840 | "AirTAK Recovery" | Nordic DFU service (Adafruit bootloader) |
| ESP32 | "AirTAK Recovery" | AirTAK GATT service (no OTA chars — flash via USB-C only in recovery) |

**Recovery Mode firmware update paths:**

1. **nRF52840:** The Android app detects "AirTAK Recovery" advertisement → connects → sends `DFU_ENTER` → Nordic DFU BLE flow.
2. **ESP32:** Recovery Mode on ESP32 does not support BLE OTA (no OTA characteristics registered). The Android app prompts: "Connect USB-C cable to recover this device." The user plugs in USB-C and the USB-C flashing flow (§3.3) begins.

**Config preservation:** Recovery Mode erases no config by default (10-second hold required for factory reset per PLANNING.md). A firmware update through Recovery Mode preserves all existing channel configuration, BLE bonds, and game settings.

---

## 11. Board Feature Flags

Add to each `BoardConfig.h`:

```cpp
#define AIRTAK_BOARD_HAS_BLE_OTA   1  // supports wireless firmware update
```

| Board | Existing `AIRTAK_BOARD_HAS_BLE_DFU` | New `AIRTAK_BOARD_HAS_BLE_OTA` | Behavior |
|-------|-------------------------------------|-------------------------------|----------|
| T-Echo Plus | 1 | 1 | Nordic DFU over BLE (bootloader delegation) |
| T-Echo | 1 | 1 | Same |
| Heltec T114 | 1 | 1 | Same |
| T-Deck | 0 | 1 | Custom BLE OTA via `esp_ota_*` |
| T3 S3 | 0 | 1 | Same |

`AIRTAK_BOARD_HAS_BLE_OTA` gates:
- Registration of `OTA_COMMAND` and `OTA_DATA` GATT characteristics in `AirTAKService::init()`
- Compilation of the platform-specific `OtaService.cpp`
- Handling of `AdminCommand.OTA_ENTER` / `OTA_FINISH` in the BLE write callback

The existing `AIRTAK_BOARD_HAS_BLE_DFU` flag continues to gate the Nordic Buttonless DFU service registration. These flags are independent — a board can support only one, both, or neither.

---

## 12. Planning Amendment Impact

### 12.1 PLANNING.md Changes

- **§6 System Architecture:** Add OTA/firmware-update subsection showing Android data/ota module and firmware OtaService
- **§10.1 GATT Service:** Add `OTA_COMMAND` (`0006`) and `OTA_DATA` (`0007`) characteristics (ESP32 only, gated by board flag)
- **§10.5 Admin Commands:** Add `OTA_ENTER` (7) and `OTA_FINISH` (8)
- **§14.4 BLE Proto Schema:** Add `OtaCommand`, `OtaStart`, `OtaFinish`, `OtaStatus`, `OtaState` messages; add `board_id` and `platform` to `DeviceStatus`

### 12.2 ROADMAP.md Changes

- **Deliverable 6.1:** Replace single-line "Nordic DFU OTA firmware update" with multi-MCU scope:
  - 6.1a: Firmware `OtaService` abstraction + nRF52 bootloader-delegation + ESP32 `esp_ota_*` implementation
  - 6.1b: Android `FirmwareUpdateManager` with transport auto-detection
  - 6.1c: USB-C wired flashing — `EspSerialFlasher` (esptool SLIP) + `NrfSerialDfuEngine` (Nordic DFU over CDC-ACM)
  - 6.1d: BLE wireless OTA — `NrfBleDfuEngine` + `EspBleOtaEngine`
  - 6.1e: Recovery Mode OTA integration (nRF52 BLE DFU in recovery, ESP32 USB-C prompt)
- **New dependency:** 6.1e depends on existing recovery mode (Phase 1)
- **T.3 (BLE proto):** Updated to include OTA messages
- **Cross-dependency table:** 6.1 now depends on T.3 (proto), 1.5 (BLE GATT server)

### 12.3 New Deliverable: T.10

| ID | Deliverable | Description |
|----|-------------|-------------|
| **T.10** | Documentation — `docs/firmware-update.md` | This document. Multi-MCU firmware update architecture, protocols, and Android integration. |

---

*End of Firmware Update Architecture*
*This document is a draft for review. Acceptance as a PLANNING.md amendment requires a new versioned entry in the amendment history.*
