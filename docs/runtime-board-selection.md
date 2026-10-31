# Runtime board selection (#22)

## Current implementation stage

The descriptor registry, identity-record codec, startup selection state machine
and opt-in V4 NVS/USB provisioning test are implemented. Normal firmware targets
still use compile-time BoardConfig selection. Descriptor-driven HAL paths now
compile in a link-only fixture, but there is not yet a deployable universal image
or runtime display dispatcher that selects OLED or TFT from the sealed descriptor.

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

The native suite passes 142 tests, including registry/header parity, all-board
record round-trips, CRC reference data, every single-bit record corruption,
truncation, invalid versions/families and unknown identifiers. Both portable
translation units also pass strict C++17 compilation with pedantic errors enabled.
The legacy V4 target and selection-test image build and retain the BLE startup
constructor. The physical selection test below is V4-only, not evidence that the
other boards or a universal runtime image have been hardware-verified.

## Runtime display dispatch

`runtime_s3_shared` is a compile/link-only shared image containing the runtime
Display dispatcher plus both backends (RuntimeOledDisplay, RuntimeTftDisplay).
Its upload is explicitly blocked because it is not a deployable image and this
is a dispatch feasibility result, not hardware evidence.

The dispatcher picks exactly one compatible backend via the portable route
predicates. An unknown board has no backend: every Display call is a no-op and
currentScreen() returns MAIN with no GPIO/I2C/SPI access. A recursive FreeRTOS
mutex serializes backend calls (created on first init, which main calls before
BLE/display trust). Rendering, passcode, recovery, brightness, sleep and wake
are gated on the backend's ready(), so a failed OLED begin() never dereferences
a null framebuffer.

The OLED backend lazily constructs its SSD1306 from descriptor geometry and only
on first use after the selection is sealed. It drives power and ADC control only
to present pins. The TFT backend builds its wiring from the compiled TFT_eSPI
flags and only instantiates the TFT driver if that wiring matches the descriptor,
then guards backlight writes on a present pin. Runtime startup deasserts radio
and TFT chip selects before starting a shared SPI bus. The native suite covers
the routing predicates, and the actual runtime Battery consumer.

## Runtime HAL compile checkpoint

`runtime_hal_check` builds the runtime power, button, battery, radio, GPS, touch,
recovery and sleep paths against the real ESP32 framework. Its display is a no-op
link fixture, so upload is explicitly disabled. An attempted upload was checked
and rejected; do not bypass that guard or treat its factory binary as deployable.

Radio Module objects capture the selected pins lazily, after the profile is
sealed. SX1276 has explicit DIO/RF-switch metadata and uses its own receive/RSSI
path rather than unconditional SX1262 calls. Runtime SX1276 intentionally binds
Module's fourth slot to DIO1: RadioLib's setDio1Action uses that GPIO, whereas the
legacy constructor used DIO2. Explicit header mode is set on initialization and
restart. The V4 and T3S3 legacy targets build;
SX1276 over-the-air operation has not been hardware-verified.

The actual runtime battery implementation is exercised by native tests with
recording GPIO/ADC functions, including no-I/O on unknown profiles. Scrambled
ADC enum stand-ins verify semantic mapping instead of an unsafe integer cast.
The HAL-facing descriptor remains unknown even for a ready metadata record until
the selection is sealed.

Runtime sleep paths release isolated RTC pads before LED/recovery/SPI startup.
Runtime storage mounts without automatic formatting and halts application startup
on mount failure, so a partition mismatch cannot silently erase config and start
radio tasks with substituted settings. First-install formatting and flash-layout
migration still need an explicit deployment procedure.

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

1. Replace the link-only display fixture with isolated OLED/TFT backends and
   complete the shared runtime application image. Keep legacy targets until the
   replacement is verified. The unconstrained compile fixture must not be deployed.
2. Compatible image/partition strategy for 8 MB and 16 MB boards, preserving
   existing LittleFS configuration and OTA layouts. Back up the V4 before changes.
3. Build/guard and physical regression checks on the runtime image. Other hardware
   and nRF runtime support remain unverified.
