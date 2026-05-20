# AirTAK — Airsoft Team Awareness Kit

LoRa mesh radio firmware for **Some devices...** and a native **Android** companion app, purpose-built for airsoft situational awareness.

AirTAK is Meshtastic wire-format compatible on the shared channel and introduces a dedicated tactical coordination protocol on top for unit hierarchy, scoped messaging, and shared tactical markers.

> **Status:** Phase 2 firmware mesh stack is buildable and covered by native config, framing, crypto, and router tests. Automated and hardware signoff status is tracked in [docs/phase2-acceptance.md](docs/phase2-acceptance.md).

---

## What it will do

- Display every player's GPS position on an offline map in real time
- Self-organized unit hierarchy (Fireteam → Squad → Platoon → Company) with scoped information sharing
- Share tactical markers on the map: points, routes, areas, notes, and objectives with team-control status
- Player status tracking (Active/Inactive with optional HIT/RESPAWNING/ELIMINATED flags) — purely informational, no enforced game logic
- Designed for deployment of up to 100 players on a 10 km × 10 km field; smart beaconing helps each device stay within regulatory duty-cycle limits
- Interoperate with stock Meshtastic devices on the shared channel
- PTT voice over LoRa via Codec2 (Phase 7)

## Firmware quick start

The firmware toolchain is pinned in git and installed into a local, ignored venv under `firmware/.venv`.

**Linux:**
```bash
./tools/setup_firmware_env.sh
./tools/pio_firmware.sh test -e native
./tools/pio_firmware.sh run -e t_echo_plus
./tools/pio_firmware.sh run -e t_echo_plus -t upload
```

**Windows:**
```powershell
.\tools\setup_firmware_env.ps1
.\tools\pio_firmware.ps1 test -e native
.\tools\pio_firmware.ps1 run -e t_echo_plus
.\tools\pio_firmware.ps1 run -e t_echo_plus -t upload
```

`setup_firmware_env.sh` / `.ps1` installs PlatformIO plus Zig-based native compiler wrappers so native firmware tests can run without a system C/C++ compiler. The target firmware build still uses PlatformIO's nRF52 ARM toolchain.

## Android quick start

**Prerequisites:** Linux with `apt` and `curl`. The setup script installs JDK 17, the Android SDK, and the Gradle wrapper.

```bash
./tools/setup_android_env.sh          # install JDK, SDK, Gradle wrapper; optionally emulator
./tools/gradle_android.sh assembleDebug   # build debug APK
./tools/gradle_android.sh test            # run unit tests
./tools/emulator_android.sh               # launch API 35 emulator (requires GPU + KVM)
./tools/install_apk_android.sh run        # build, install, and launch on emulator
```

The Gradle wrapper (`android/gradlew`) is committed so a clean clone can build without a global Gradle installation. `android/.android-env` stores machine-specific paths and is gitignored.

## Firmware recovery

Hold the T-Echo Plus user button while powering on or resetting the device. Keep it held for 5 seconds to enter safe mode.

Safe mode runs before the shared peripheral power rail, normal radio, GPS, config apply, and FreeRTOS tasks. It keeps persisted config and BLE bonds intact by default, shows `SAFE MODE` on the e-ink display, and advertises over BLE as `AirTAK Recovery` with Nordic DFU available. Use this when faulty app firmware prevents normal access. Power-cycle without holding the button to boot normally after recovery.

After the `SAFE MODE` screen appears, release the button. Then:

- Tap the button to reset into the Adafruit UF2 USB bootloader.
- Hold for 5 seconds to reset into USB serial DFU bootloader mode.
- Hold for 10 seconds to erase persisted AirTAK config.

Config erase is intentionally a separate confirmation gesture.

If USB/BLE DFU cannot be reached, recover with SWD/J-Link using the existing PlatformIO `nrfjprog` upload path.

Safe mode is an application-level recovery path for the T-Echo Plus pin map. If this firmware is flashed to hardware with a different user-button pin, the safe-mode gesture may not be reachable; if it has different peripheral pins, normal boot may drive the wrong GPIOs after the recovery check. The reliable recovery path for a wrong-board flash is the bootloader or SWD/J-Link. Do not flash this firmware onto non-T-Echo Plus hardware without a matching board definition and reviewed pin map.

## Repository layout

```
proto/
├── meshtastic/     Vendored Meshtastic protos (pinned commit — do not update)
└── airtak/           AirTAK proto definitions (config, ble, game, mesh)

firmware/           PlatformIO project — nRF52840 / T-Echo Plus
android/            Kotlin + Jetpack Compose app (min API 31, target 35)

tools/
├── setup_firmware_env.sh / .ps1   Create the firmware Python venv + PlatformIO
├── setup_android_env.sh           Install JDK 17, Android SDK, Gradle wrapper
├── pio_firmware.sh / .ps1         Run pinned PlatformIO (build, test, upload)
├── gradle_android.sh              Run Gradle wrapper with Android SDK env
├── phase1_verify.sh / .ps1        Phase 1 automated acceptance checks
├── phase2_verify.ps1              Phase 2 automated mesh-stack checks
├── proto_gen.sh / .ps1            Regenerate nanopb + Kotlin sources from .proto
├── flash.ps1                      Flash firmware to connected device
├── emulator_android.sh            Launch API 35 x86_64 Android emulator
├── install_apk_android.sh         Build + install APK on running emulator
└── tile_fetch.py                  Download MBTiles for a bounding box

docs/
├── radio-protocol.md   Wire format with annotated byte diagrams
├── ble-service.md      GATT UUID table and characteristic flows
└── tactical-protocol.md   Tactical coordination packet flow diagrams
```

## Flashing verification

The Android app supports OTA firmware updates over BLE and USB serial. To verify that a flash actually wrote the new image to the device, build a flash-test firmware that renders **FLASH TEST OK** on the display instead of **AirTAK**. See [`firmware/src/platform/README.md`](firmware/src/platform/README.md#flash-test-firmware) for environments and build commands.

## Design document

All architecture, protocol, and implementation decisions are in [`PLANNING.md`](PLANNING.md).  
Do not modify `PLANNING.md` directly; changes require a versioned amendment.

## License

AirTAK is free software licensed under the GNU General Public License v3.0 or later.

The firmware adopts Meshtastic's GPL-3.0-licensed AES-128-CTR crypto and E-Ink display driver code. The full license text is in [`LICENSE`](LICENSE).

SPDX-License-Identifier: GPL-3.0-or-later
