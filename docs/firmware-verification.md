# Firmware build verification (issue #2)

Issue #2 established the user-local firmware toolchain and original six-board build baseline.
The newer ESP32-S3 framework and Heltec V4 verification below supersede that baseline for the
ESP32 targets. No sudo or system Python packages are required.

## Toolchain (pinned, user-local, no sudo)

- **PlatformIO Core** `6.2.0` — pinned in `firmware/requirements-dev.txt`,
  installed into a venv at `firmware/.venv` by `tools/setup_firmware_env.sh`.
  No `sudo`, no global pip install.
- **ziglang** `0.13.0` — also pinned in `requirements-dev.txt` (used by the build for the
  native/time tooling).

## Resolved platform versions (installed under `~/.platformio/platforms`)

| platform | version |
|----------|---------|
| `nordicnrf52` | 11.0.0 |
| ESP32-S3 pioarduino platform | `55.03.312-1` (Arduino `3.3.12`, ESP-IDF `5.5.5`) |
| `native` | 1.2.1 |

## Boards built (`pio run`, release mode)

Each produced `firmware/.pio/build/<board>/firmware.elf`:

| board | firmware.elf |
|-------|--------------|
| `t_echo` | built (~300K) |
| `t_echo_plus` | built (~300K) |
| `heltec_t114` | built (~292K) |
| `t_deck` | built (~15M) |
| `t3s3` | built (~15M) |
| `heltec_v3` | built (~15M) |
| `heltec_v4` | built; flashed and BLE-connected on physical V4.3 |

The `Battery.cpp` translation unit (the battery work the issue calls out) compiles to an object
file under every board environment, so the macro-ordering mistake the issue described (the
`AIRTAK_BATTERY_DIVIDER` default had to move out of `Constants.h`) is now caught by the compiler,
not by hand-reasoning.

## Native tests (`pio test -e native`)

All suites pass:

```
native  test_config   PASSED
native  test_crypto   PASSED
native  test_framing  PASSED
native  test_router   PASSED
```

## Newer-framework regression verification

The four ESP32-S3 environments (`heltec_v4`, `heltec_v3`, `t3s3`, `t_deck`)
were rebuilt on the pinned newer framework. The nRF52 rows above record the
original baseline, not a claim of renewed hardware testing. Native tests pass
93/93; Android tests pass 251/251 with the discovery audit clean.

See [ESP32 BLE startup](esp32-ble-startup.md) for the verified pre-main BLE-memory
fix, clean-build toolchain mismatch, hardware evidence, and ELF regression guard.

## How to reproduce

```bash
./tools/setup_firmware_env.sh            # user-local venv, no sudo
for b in t_echo t_echo_plus heltec_t114 heltec_v3 heltec_v4 t_deck t3s3; do
  ./tools/pio_firmware.sh run -e "$b"
done
./tools/pio_firmware.sh test -e native
```
