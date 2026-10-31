# Runtime board selection (#22)

## Current implementation stage

The descriptor registry, identity-record codec, startup selection state machine
and opt-in V4 NVS/USB provisioning test are implemented. Normal firmware targets
still use compile-time BoardConfig selection. There is not yet a shared runtime
HAL, universal image, or runtime OLED/TFT dispatcher.

Do not flash a generic partition layout over an existing board on the strength
of this foundation. The tested V4 firmware remains the known-good baseline.

## Registry

`firmware/src/platform/BoardDescriptor.h/.cpp` contains exact identifiers:
`heltec_v3`, `heltec_v4`, `t3s3`, and `t_deck`. Unknown, null, case-mismatched or
otherwise unsupported identifiers return a descriptor with no peripherals and
all pins absent (-1). Lookup performs no hardware probing or GPIO access.

Native tests include one isolated translation unit per legacy board header,
checking the actual header macros against the descriptor values. Pin, polarity,
ADC attenuation and display metadata changes must update both representations
consistently until the runtime migration removes the duplicated configuration.

The T3S3 descriptor models its SX1262 primary path and records that the existing
SX1276 fallback must be retained. Radio.cpp does not implement SX1280/LR1121
probing, despite their pin definitions in the legacy header. Alternate-radio
pin dispatch still needs to be carried into the runtime HAL conversion.

## Persisted identity format

`BoardIdentity.h/.cpp` encodes bytes explicitly, independent of C++ padding:

| Offset | Size | Meaning |
|---|---|---|
| 0 | 4 | ASCII ATKB |
| 4 | 1 | Format version, currently 1 |
| 5 | 1 | Family identifier, currently 1 for ESP32-S3 |
| 6 | 1 | Board-id length, 1 to 16 |
| 7 | 1 | Reserved, must be zero |
| 8 | 16 | Lowercase ASCII/digit/underscore id, zero-padded |
| 24 | 4 | CRC32-IEEE over bytes 0 through 23, little endian |

Decoding requires exactly 28 bytes and rejects unsupported versions/families,
invalid padding or characters, unknown identifiers and checksum failures. All
rejections return the safe unknown descriptor. CRC detects accidental corruption;
it does not authenticate provisioning or prove that the selected physical board
matches the record. Explicit trusted selection is still required.

## Verification

```sh
./tools/pio_firmware.sh test -e native
```

The native suite passes 125 tests, including registry/header parity, all-board
record round-trips, CRC reference data, every single-bit record corruption,
truncation, invalid versions/families and unknown identifiers. Both portable
translation units also pass strict C++17 compilation with pedantic errors enabled.
The legacy V4 target and selection-test image build and retain the BLE startup
constructor. The physical selection test below is V4-only, not evidence that the
other boards or a universal runtime image have been hardware-verified.

## Constrained physical provisioning test

`heltec_v4_selection_test` inherits the existing V4 HAL and 16 MB layout. Before
LED, recovery-button or peripheral setup it calls the provisioning gate. Only a
persisted `heltec_v4` record can start this image; another valid registry id is
rejected. This constraint is mandatory until the HAL is actually runtime-bound.
The normal `heltec_v4` target remains available and does not require provisioning.

The byte store uses NVS namespace `airtak_board`, blob `identity`. A write must
commit and read back exactly before the profile is accepted. Missing/invalid
records and storage errors stay in the USB-only application startup gate; the
adapter never erases NVS on failure. The selection is sealed before startup and
cannot subsequently be loaded or provisioned through that state machine.

At boot, send newline-terminated ASCII commands over native USB serial:

- `BOARD GET`: print current selection state and image constraint.
- `BOARD LIST`: list only ids allowed by this image.
- `BOARD SET heltec_v4`: persist, verify and reboot.
- `BOARD BOOT`: leave the startup console only if a valid profile is ready.

A valid stored profile starts automatically after a two-second grace period if
no console input arrives. Input pauses automatic startup until an explicit BOOT
or successful SET/reboot. Commands are boot-only, not a live pin-remapping API.
Malformed/oversized lines are discarded. An unknown board stays at the console
indefinitely. The record checksum is not authentication; use a trusted USB host.

Physical V4 checks: full 16 MB flash backup made before testing, with private
permissions because it may contain bonds/keys; new partition bytes matched the
actual backup. Unprovisioned boot held application startup, LIST showed only V4,
and SET t_deck was rejected. SET heltec_v4 committed/read back, rebooted, loaded
and sealed the profile, then reached normal AirTAK boot, LoRa init and successful
TX completions. Device/game config loaded from flash and the phone returned to
LINKED with its saved callsign. These startup checks do not measure every GPIO
with external equipment and do not prove LoRa peer reception or calibration.

## Next stages

1. Lazy radio construction, runtime power/ADC/GPS/recovery/sleep paths and isolated
   OLED/TFT backends. Only after conversion may a shared image remove the compiled
   board constraint. Keep legacy targets until the replacement is verified.
2. Compatible image/partition strategy for 8 MB and 16 MB boards, preserving
   existing LittleFS configuration and OTA layouts. Back up the V4 before changes.
3. Build/guard and physical regression checks on the runtime image. Other hardware
   and nRF runtime support remain unverified.
