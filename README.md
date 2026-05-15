# MASH — Mesh Airsoft Situational Hub

LoRa mesh radio firmware for the **LilyGo T-Echo Plus** and a native **Android** companion app, purpose-built for airsoft situational awareness.

MASH is Meshtastic wire-format compatible on the shared channel and introduces a dedicated game-mode protocol on top.

> **Status:** Phase 2 firmware mesh stack is buildable and covered by native config, framing, crypto, and router tests. Automated and hardware signoff status is tracked in [docs/phase2-acceptance.md](docs/phase2-acceptance.md).

---

## What it will do

- Display every player's GPS position on an offline map in real time
- Track player state (active / hit / respawning / eliminated) with push-to-device acknowledgment
- Broadcast game zones, objectives, and phase changes to the whole mesh
- Designed for deployment of up to 100 players on a 10 km × 10 km field; smart beaconing helps each device stay within regulatory duty-cycle limits
- Interoperate with stock Meshtastic devices on the shared channel
- PTT voice over LoRa via Codec2 (Phase 7)

## Hardware

| Part | Detail |
|------|--------|
| Board | LilyGo T-Echo Plus |
| MCU | Nordic nRF52840 |
| Radio | Semtech SX1262 |
| GNSS | Quectel L76K |
| Display | 1.54″ e-ink 200 × 200 |
| Battery | 400 mAh LiPo |

## Firmware quick start

The firmware toolchain is pinned in git and installed into a local, ignored venv under `firmware/.venv`.

```powershell
.\tools\setup_firmware_env.ps1
.\tools\phase2_verify.ps1
.\tools\pio_firmware.ps1 test -e native
.\tools\pio_firmware.ps1 run -e t_echo_plus
```

`setup_firmware_env.ps1` installs PlatformIO plus Zig-based native compiler wrappers so Windows machines can run the native firmware tests without a separate system C/C++ compiler. The target firmware build still uses PlatformIO's nRF52 ARM toolchain.

## Firmware recovery

Hold the T-Echo Plus user button while powering on or resetting the device. Keep it held for 5 seconds to enter safe mode.

Safe mode runs before the shared peripheral power rail, normal radio, GPS, config apply, and FreeRTOS tasks. It keeps persisted config and BLE bonds intact by default, shows `SAFE MODE` on the e-ink display, and advertises over BLE as `MASH Recovery` with Nordic DFU available. Use this when faulty app firmware prevents normal access. Power-cycle without holding the button to boot normally after recovery.

To erase persisted MASH config from safe mode, release the button after the `SAFE MODE` screen appears, then hold it again for 10 seconds. Config erase is intentionally a separate confirmation gesture.

If USB/BLE DFU cannot be reached, recover with SWD/J-Link using the existing PlatformIO `nrfjprog` upload path.

Safe mode is an application-level recovery path for the T-Echo Plus pin map. If this firmware is flashed to hardware with a different user-button pin, the safe-mode gesture may not be reachable; if it has different peripheral pins, normal boot may drive the wrong GPIOs after the recovery check. The reliable recovery path for a wrong-board flash is the bootloader or SWD/J-Link. Do not flash this firmware onto non-T-Echo Plus hardware without a matching board definition and reviewed pin map.

## Repository layout

```
proto/
├── meshtastic/     Vendored Meshtastic protos (pinned commit — do not update)
└── mash/           MASH proto definitions (config, ble, game, mesh)

firmware/           PlatformIO project — nRF52840 / T-Echo Plus
android/            Kotlin + Jetpack Compose app (min API 31, target 35)

tools/
├── flash.ps1       Flash firmware to connected device
├── phase1_verify.ps1       Run Phase 1 automated acceptance checks
├── phase2_verify.ps1       Run Phase 2 automated mesh-stack checks
├── pio_firmware.ps1        Run the pinned firmware PlatformIO environment
├── proto_gen.ps1   Regenerate nanopb + Kotlin sources from .proto files
├── setup_firmware_env.ps1  Create the local firmware Python environment
└── tile_fetch.py   Download MBTiles for a bounding box

docs/
├── phase1-acceptance.md  Phase 1 automated and hardware acceptance record
├── phase2-acceptance.md  Phase 2 mesh-stack acceptance record
├── radio-protocol.md   Wire format with annotated byte diagrams
├── ble-service.md      GATT UUID table and characteristic flows
└── game-protocol.md    Game packet flow diagrams
```

## Design document

All architecture, protocol, and implementation decisions are in [`PLANNING.md`](PLANNING.md).  
Do not modify `PLANNING.md` directly; changes require a versioned amendment.

## License

TBD.
