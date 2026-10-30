# Firmware build verification (issue #2)

The firmware on this branch was never compiled because no PlatformIO toolchain was installed. It
now is — user-local, no sudo, no global packages — and everything compiles and the native suite
passes. This records the resolved versions and the exact result so the build is reproducible.

## Toolchain (pinned, user-local, no sudo)

- **PlatformIO Core** `6.1.18` — pinned in `firmware/requirements-dev.txt`,
  installed into a venv at `firmware/.venv` by `tools/setup_firmware_env.sh`.
  No `sudo`, no global pip install.
- **ziglang** `0.13.0` — also pinned in `requirements-dev.txt` (used by the build for the
  native/time tooling).

## Resolved platform versions (installed under `~/.platformio/platforms`)

| platform | version |
|----------|---------|
| `nordicnrf52` | 11.0.0 |
| `espressif32` | 8.0.0 |
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

## How to reproduce

```bash
./tools/setup_firmware_env.sh            # user-local venv, no sudo
for b in t_echo t_echo_plus heltec_t114 heltec_v3 t_deck t3s3; do
  ./tools/pio_firmware.sh run -e "$b"
done
./tools/pio_firmware.sh test -e native
```
