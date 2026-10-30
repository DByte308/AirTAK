# Live own-node name consistency (#6)

Verified using the connected Heltec V4.3 and S24 Ultra with the updated debug APK.
The board runs the newer-framework BLE fix from `edddaad`.

## Fixes exercised

- One `LocalNodeIdentity` snapshot supplies the HUD, own map label and own roster row:
  callsign -> short name -> configured long name (DeviceStatus fallback).
- Preserve the intentional nameless UI words: map/roster `You`, HUD `AirTAK`.
  HUD and roster uppercase labels as presentation; the map preserves case.
- All three PlayerState producers send only the trimmed configured callsign,
  never a synthetic UI label or a potentially 39-byte device long name.
- Refresh GAME/DEVICE config after every BLE connection, not only screen creation;
  discard the previous device's cached groups when disconnected.
- Read authoritative config after successful apply acknowledgements.
- Preserve explicitly empty identity strings on the wire. DEVICE and GAME get
  empty string tags appended to the original bytes when needed. This avoids the
  firmware's zero-byte commit-only operation and absent-field factory defaults,
  while retaining all existing numeric/bool/unknown wire bytes. Other groups and
  explicit `read()` requests are unchanged.

## Device checks

| Configured names | Map | HUD | Own roster |
|---|---|---|---|
| CHKCALL / CSHT / CheckLongName | CHKCALL | CHKCALL | CHKCALL |
| empty / CSHT / CheckLongName | CSHT | CSHT | CSHT |
| empty / empty / CheckLongName | CheckLongName | CHECKLONGNAME | CHECKLONGNAME |
| all empty | You | AIRTAK | YOU |

Applied changes updated the three views without revisiting Settings. The all-empty
case was first reproduced as failing (old long name remained), then passed after
the explicit-empty encoding fix. Original names were restored and verified in all
three views. After force-stop/relaunch, the saved callsign appeared without entering
Settings, confirming reconnect-time config reads.

Android tests: **285/285**, discovery audit clean. This includes identity precedence,
malformed config handling, reconnect/cache invalidation, ACK readback, explicit-empty
staging/apply, full GAME numeric/bool preservation and unknown-wire preservation.
A follow-up QR-import regression test verifies shared GAME settings keep the local
callsign (including a deliberately generated draft identity), never apply DEVICE,
and retain imported hierarchy/color/HIT settings. QR import formerly forced the
callsign empty despite its documented preservation contract; that is now corrected.
This QR path is unit-tested, not claimed as an additional on-device QR-camera test.
Debug APK's strict-ABI native libraries pass the 16 KB ELF/archive alignment gate.

## Limits

This verifies one BLE-connected own node, not reception on a second LoRa radio.
Existing remote-name clear semantics are deliberately unchanged: proto3's implicit
presence cannot distinguish an omitted callsign from an explicit empty one, and
`RemoteNodeRepository` currently retains a previously received callsign on empty.
Fixing stale peer-name erasure requires a separate backward-compatible protocol
contract; do not treat this local UI verification as that mesh-level proof.
