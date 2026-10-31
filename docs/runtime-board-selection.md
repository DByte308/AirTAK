# Runtime board selection (#22)

## Current implementation stage

The portable foundation is implemented: an ESP32-S3 descriptor registry and a
versioned identity-record codec. Existing firmware targets still use their
compile-time BoardConfig selection. There is not yet a unified runtime image,
NVS provisioning flow, or runtime OLED/TFT dispatcher.

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

The native suite passes 113 tests, including registry/header parity, all-board
record round-trips, CRC reference data, every single-bit record corruption,
truncation, invalid versions/families and unknown identifiers. Both portable
translation units also pass strict C++17 compilation with pedantic errors enabled.
The legacy V4 target builds and retains its BLE startup constructor in the ELF.
These are software/build checks, not runtime multi-board hardware verification.

## Next stages

1. Persistent selection and bounded USB provisioning before any board-specific
   GPIO, LED, recovery-button, radio or display initialization. Storage errors
   must not trigger automatic erasure of existing NVS/bonds.
2. Lazy radio construction, runtime power/ADC/GPS/recovery/sleep paths and isolated
   OLED/TFT backends. Keep legacy targets until the replacement is verified.
3. Compatible image/partition strategy for 8 MB and 16 MB boards, preserving
   existing LittleFS configuration and OTA layouts. Back up the V4 before changes.
4. Builds, guards and physical unknown/provisioned/reboot/BLE/OLED tests on the
   V4. Other hardware and nRF runtime support remain unverified.
