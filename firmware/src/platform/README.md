# Firmware Platform Layer

The firmware keeps mesh, config, game, BLE framing, and public HAL APIs in the shared tree. Code that depends on a specific MCU SDK, BLE stack, bootloader, flash driver, or board pin map lives under `src/platform/`.

## Layout

- `BoardConfig.h` selects the active board from PlatformIO build flags.
- `boards/<board>/BoardConfig.h` owns board pins and feature flags.
- `Platform.h` exposes board identity, MCU family name, boot init, node ID, and MAC address helpers.
- `nrf52/` contains implementations tied to the Adafruit nRF52 Arduino BSP and Bluefruit stack.
- `native/` contains host-side implementations used by unit tests.

## Adding A Board

1. Add `src/platform/boards/<board>/BoardConfig.h` with pins, feature flags, and module selections.
2. Add a PlatformIO environment that defines `AIRTAK_BOARD_<BOARD_NAME>` and excludes unrelated platform implementation folders.
3. If the board uses a new MCU family, add platform implementations for identity, BLE, DFU/recovery, and config storage before enabling the full firmware build.
4. Keep public interfaces in `src/hal/`, `src/ble/`, and `src/config/` stable so mesh and application code do not need target-specific branches.
