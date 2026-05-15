# Phase 1 Acceptance

**Scope:** ROADMAP Phase 1, firmware foundation deliverables 1.1-1.7.

This file records the repeatable checks required before Phase 1 is treated as complete. Automated checks can be run on a development machine; hardware checks require one or more LilyGo T-Echo Plus devices.

## Automated Checks

Run from the repository root:

```powershell
.\tools\phase1_verify.ps1
```

Equivalent individual commands:

```powershell
.\tools\pio_firmware.ps1 test -e native
.\tools\pio_firmware.ps1 run -e t_echo_plus
```

Expected result:

| Check | Expected |
|---|---|
| Native unit tests | `test_config` and `test_framing` pass |
| Firmware build | `t_echo_plus` environment builds successfully |

Last local verification:

| Date | Command | Result |
|---|---|---|
| 2026-05-15 | `.\tools\phase1_verify.ps1` | Automated checks passed |
| 2026-05-15 | `.\tools\pio_firmware.ps1 test -e native` | 16 passed / 0 failed |
| 2026-05-15 | `.\tools\pio_firmware.ps1 run -e t_echo_plus` | Build succeeded |

Coverage:

| Roadmap Item | Automated Evidence |
|---|---|
| 1.1 PlatformIO project scaffolding | `pio_firmware.ps1 run -e t_echo_plus` |
| 1.5 BLE framing protocol | `firmware/test/test_framing` |
| 1.6 Config serialization and flash-store round trip | `firmware/test/test_config` |

## Hardware Smoke Checks

These checks complete the parts of Phase 1 that cannot be proven by CI or native tests.

| Roadmap Item | Required Result | Evidence To Record |
|---|---|---|
| 1.1 Pin map | Device boots with radio, GPS, display, flash, BLE, battery ADC initialized | Device revision, firmware commit, boot log |
| 1.2 SX1262 bring-up | Two T-Echo Plus devices exchange packets at Profile 0 | RSSI/SNR on both devices, packet counters |
| 1.3 L76K GPS bring-up | Cold fix acquired; GNGGA/GNRMC only after baud upgrade | Time-to-first-fix, satellites, HDOP |
| 1.4 E-Ink display | Main screen renders node, GPS, battery, node-count placeholders | Photo after boot and after one dirty refresh |
| 1.5 BLE GATT skeleton | Phone pairs with numeric comparison, negotiates MTU 517, reads DEVICE_STATUS, authenticated writes are enforced | BLE scanner/app log, passkey photo |
| 1.6 LittleFS config store | Config write persists across power cycle | Before/after config readback payload hash |
| 1.7 FreeRTOS skeleton | Firmware remains stable idle for at least 10 minutes with all tasks created | Serial boot log and no asserts/reboots |

## Signoff Template

| Field | Value |
|---|---|
| Firmware commit |  |
| Device hardware revision |  |
| Test date |  |
| Tester |  |
| Native tests pass |  |
| Firmware build pass |  |
| Hardware smoke checks pass |  |
| Notes |  |