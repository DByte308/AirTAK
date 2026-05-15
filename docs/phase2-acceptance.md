# Phase 2 Acceptance

Phase 2 covers the firmware mesh stack: AES-128-CTR, NODEINFO, TEXT/POSITION routing, ManagedFlood relay behavior, channel management, hot config reload, hop limits, ACKNOWLEDGED delivery, and `AIRTAK_POSITION_REQUEST`.

## Automated Verification

Run from the repository root:

```powershell
.\tools\phase2_verify.ps1
```

This runs the native firmware tests and builds the T-Echo Plus firmware image.

Last local verification:

| Date | Command | Result |
|------|---------|--------|
| 2026-05-15 | `.\tools\phase2_verify.ps1` | 46 native tests passed; `t_echo_plus` build succeeded |

Native coverage includes config/protobuf round-trips, BLE framing, Meshtastic-compatible crypto/channel hashes, hop-limit defaults, SNR relay delay, duplicate suppression, heard-by relay suppression, `AIRTAK_AUDIO` non-relay, NODEINFO dispatch, `AIRTAK_POSITION_REQUEST` target dispatch, ACK retry/timeout/clearing, app-layer dedup by `Data.request_id`, unicast text ACK selection, and the `AIRTAK_PLAYER.is_state_transition` ACK rule.

## Hardware Signoff Remaining

The automated checks do not replace device-level mesh validation. These checks still require physical T-Echo Plus and Meshtastic devices:

- Bidirectional TEXT_MESSAGE_APP exchange with a stock Meshtastic device.
- NODEINFO and POSITION_APP visibility in Meshtastic Android.
- Multi-hop relay, heard-by suppression, and ACK retry behavior on a three-device chain.
- Channel isolation with two active slots using different PSKs.
- Hot-reload radio restart timing and BLE connection survival.
- `AIRTAK_POSITION_REQUEST` response within 2 seconds on target hardware.