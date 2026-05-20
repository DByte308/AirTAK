# Firmware Platform Layer

The firmware keeps mesh, config, game, BLE framing, and public HAL APIs in the shared tree. Code that depends on a specific MCU SDK, BLE stack, bootloader, flash driver, or board pin map lives under `src/platform/`.

## Layout

- `BoardConfig.h` selects the active board from PlatformIO build flags.
- `boards/<board>/BoardConfig.h` owns board pins and feature flags.
- `boards/<board>/hal/Display.cpp` implements the board-specific display driver.
- `Platform.h` exposes board identity, MCU family name, boot init, node ID, and MAC address helpers.
- `nrf52/` contains implementations tied to the Adafruit nRF52 Arduino BSP and Bluefruit stack.
- `esp32/` contains implementations tied to the Arduino-ESP32 framework and NimBLE stack.
- `native/` contains host-side implementations used by unit tests.

## Adding A Board

1. Create `firmware/boards/<board>.json` — PlatformIO custom board definition with MCU, framework, upload, and pin mapping metadata.
2. Create `firmware/variants/<board>/variant.h` + `variant.cpp` — Arduino framework variant pin defines (required for new nRF52 boards; ESP32 boards use a standard variant).
3. Add `src/platform/boards/<board>/BoardConfig.h` with `AIRTAK_BOARD_ID`, feature flags (`AIRTAK_BOARD_HAS_*`), and all `AIRTAK_PIN_*` pin defines.
4. Add `src/platform/boards/<board>/hal/Display.cpp` — board-specific display driver implementing `hal/Display.h`.
5. Add a PlatformIO environment in `platformio.ini` that defines `AIRTAK_BOARD_<NAME>` and excludes unrelated platform/board folders.
6. Add a selector in `src/platform/BoardConfig.h` for `AIRTAK_BOARD_<NAME>`.
7. If the board uses a new MCU family, add platform implementations for identity, BLE, DFU/recovery, and config storage under `src/platform/<arch>/`.
8. Keep public interfaces in `src/hal/`, `src/ble/`, and `src/config/` stable so mesh and application code do not need target-specific branches.

## Device Variants (same board, different radio / peripheral)

Some boards ship with multiple hardware variants that differ only in the radio module (e.g. LilyGo T3 S3: SX1262 / SX1276 / SX1280 / LR1121). AirTAK handles these via a **single BoardConfig.h with multi-radio pin defines** and **runtime radio detection** in `Radio.cpp`:

1. Define all possible radio pins in the single `BoardConfig.h` (SX1262, SX1276, SX1280, etc.).
2. `Radio::init()` probes each supported radio type on the shared SPI bus in order; the first to respond becomes the active radio.
3. One build target serves all variants — no need for separate PlatformIO environments per radio.

When frequency differs between variants (e.g. SX1262 sub-GHz vs SX1280 2.4 GHz), the frequency band is determined by which radio was detected and LoraConfig.

This is preferred over per-radio-variant build targets because:
- Users flash one firmware binary regardless of which T3 S3 variant they received.
- Board pins (display, battery, buttons, SD card) are identical across variants — only radio DIO/BUSY pins differ.
- RadioLib supports runtime probing via SPI chip-ID registers.

Contrast with Meshtastic's approach: all radio defines in one `variant.h`, with runtime probing in `RadioInterface.cpp`. AirTAK follows the same strategy but keeps the radio abstraction inside `Radio.cpp` rather than scattered through `NodeDB.cpp` and `configuration.h`.

## Flash Test Firmware

To verify that OTA (BLE / USB serial) or OTG flashing via the Android app actually succeeds, build a flash-test variant of the firmware. When the `AIRTAK_FLASH_TEST` compile flag is set, the display header renders **FLASH TEST OK** instead of **AirTAK** and the serial boot log prints a `FLASH TEST` prefix — giving an immediate visual confirmation on the device that the new image was written correctly.

### Build

Each production environment in `firmware/platformio.ini` has a commented-out `-DAIRTAK_FLASH_TEST` flag. Uncomment it for the target board, then build as usual:

```ini
; Uncomment to build flash-test firmware (display shows "FLASH TEST OK"):
; -DAIRTAK_FLASH_TEST
```

```
./tools/pio_firmware.sh run -e t_echo        # T-Echo (nRF52840)
./tools/pio_firmware.sh run -e t_echo_plus    # T-Echo Plus (nRF52840)
./tools/pio_firmware.sh run -e heltec_t114    # Heltec T114 (nRF52840)
./tools/pio_firmware.sh run -e t3s3           # T3 S3 (ESP32-S3)
./tools/pio_firmware.sh run -e t_deck         # T-Deck (ESP32-S3)
```

The flag is the only difference from a production build — all other settings, libraries, and source filters are identical. Artifact format is unchanged:

- **nRF52 boards** output a `.hex` file (`.pio/build/<env>/firmware.hex`). Package it into a DFU zip with `adafruit-nrfutil` before flashing via the Android app's BLE DFU or USB serial DFU path.
- **ESP32 boards** output a `.bin` file (`.pio/build/<env>/firmware.bin`) that starts with the ESP OTA app magic byte (`0xE9`). Use it directly with the Android app's USB serial flasher or BLE OTA path.

### Affected source files

The flag is gated in the following files:

| File | Change |
|---|---|
| `src/platform/boards/*/hal/Display.cpp` (all 5 boards) | `renderMainScreen()` header text |
| `src/main.cpp` | Serial boot messages (`"AirTAK boot"` → `"FLASH TEST boot"`, etc.) |

The flag does **not** alter any radio, mesh, config, BLE service, recovery, or OTA protocol behaviour — the test firmware is functionally identical to production and can be left on the device indefinitely.
