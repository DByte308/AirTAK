# ESP32-S3 BLE startup on Arduino 3.3.12

## Root cause and fix

Arduino 3.3.12's `initArduino()` calls `btMemRelease(BT_MODE_BLE)` before
`setup()` when `bleInUse()` is false. NimBLE-Arduino 1.4 predates the new
`esp32-hal-alloc-ble-mem.h` usage marker, so it does not tell Arduino to retain
controller memory. Starting the controller after that release can overwrite
heap allocations and crash inside TLSF or controller cleanup.

`firmware/src/platform/esp32/ble/BLEServer.cpp` now includes the framework's
allocation header when available. Its pre-main constructor marks BLE in use
before Arduino's release decision. Older frameworks without the header retain
their existing path. On newer cores an early `configASSERT(bleInUse())` and
`[BLE] controller memory retained` log verify the startup contract.

This is **not** an ESP32-S3 rev v0.2 silicon limitation. A healthy heap just
before controller initialization does not exclude corruption during init.
NimBLE's old `btStarted()` linkage workaround does not set the new usage marker.
Releasing Classic BT memory is not the missing fix: NimBLE already does that.
Controller activity limits in `custom_sdkconfig` are resource policy, not the
startup fix.

## Controlled device evidence

Physical device: Heltec WiFi LoRa 32 V4.3, ESP32-S3 QFN56 rev v0.2,
embedded 2 MB PSRAM, 16 MB flash.

| Test | Outcome |
|---|---|
| Minimal controller-only program, IDF 5.5.5, stock S3 board definition, no usage marker | controller-init panic |
| Same program and framework, only adding allocation header | controller init and BLE enable both `ESP_OK` |
| Minimal program, IDF 4.4.7 | controller init and BLE enable both `ESP_OK` |
| Full AirTAK, IDF 5.5.5, allocation header | normal boot, radio init OK, successful TX completions, phone reconnects |

The minimal newer-framework reproduction contains neither AirTAK nor
NimBLE-Arduino; this isolates the missing startup declaration, rather than a
NimBLE/controller protocol incompatibility. Full newer-framework phone logs
show connection status 0, MTU 517, and service-discovery status 0 after flashing.
The working older-framework full build additionally passed PC GATT discovery,
DeviceStatus reads, and phone disconnect/reconnect. The operator confirmed its
OLED rendered AirTAK. The newer-framework port uses the same OLED power/reset
and rendering code, without the disproven GPIO-floating experiment.

Heltec V4 selects `qio_qspi`, not `qio_opi`. `BOARD_HAS_PSRAM` is not enabled;
external-memory allocation/PSRAM operation has **not** been verified. No peer
LoRa reception, calibrated battery readings, or long-duration endurance is
claimed by these startup tests. The current V4 FEM port keeps CTX high for the
TX PA path and uses RX bypass rather than its LNA; TX/RX-aware FEM switching
and RF receive sensitivity remain separate work.

## Two PlatformIO copies can break clean builds

The project toolchain is pinned to PlatformIO **6.2.0**. The pioarduino hybrid
SDK rebuild can start a second `pio` from its managed Python environment.
On the verification host the outer copy was 6.2.0, but the managed copy reported
`6.2.0.post1` (`6.2.0-post.1` in its compatibility diagnostic). That copy rejected
the platform's `>=6.2.0` requirement and removed the active platform. A subsequent
`.sconsign314.tmp` missing-file error was secondary, not the root failure.

Check **both** versions when diagnosing a clean-build failure:

```sh
firmware/.venv/bin/platformio --version
# Typical managed location; use the interpreter/path shown by your build log.
~/.platformio/penv/bin/platformio --version
```

If the managed copy has this mismatch, explicitly align that user-local copy:

```sh
~/.platformio/penv/bin/python -m pip install 'platformio==6.2.0'
```

Do not patch SCons or mask errors with unconditional build retries. After aligning
the versions, first-try SDK rebuilds and Arduino builds passed for `heltec_v3`,
`t3s3`, and `t_deck`; a clean V4 Arduino build also passed.

## Regression checks

```sh
./tools/pio_firmware.sh run -e heltec_v4
python3 tools/check_ble_memory.py firmware/.pio/build/heltec_v4/firmware.elf
python3 tools/test_check_ble_memory.py
./tools/pio_firmware.sh test -e native
```

The stdlib-only ELF guard requires the `_bleLibraryInUse` flag, the framework's
`_setBleLibraryInUse` constructor, and that constructor's address in the actual
startup array. It handles Xtensa's folded init array using its linker symbols.
It is intended for the pinned newer-framework ESP32 builds, not nRF52/native or
older Arduino images without this marker contract. Run it on all four ESP32-S3
ELFs after a framework or BLE-library upgrade. Its malformed-ELF and negative
fixture tests run in the offline CI tooling suite.
