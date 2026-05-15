# MASH — Mesh Airsoft Situational Hub

LoRa mesh radio firmware for the **LilyGo T-Echo Plus** and a native **Android** companion app, purpose-built for airsoft situational awareness.

MASH is Meshtastic wire-format compatible on the shared channel and introduces a dedicated game-mode protocol on top.

> **Status: pre-development.** No code exists yet. See [`PLANNING.md`](PLANNING.md) for the full design.

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

## Repository layout (planned)

```
proto/
├── meshtastic/     Vendored Meshtastic protos (pinned commit — do not update)
└── mash/           MASH proto definitions (config, ble, game, mesh)

firmware/           PlatformIO project — nRF52840 / T-Echo Plus
android/            Kotlin + Jetpack Compose app (min API 31, target 35)

tools/
├── flash.ps1       Flash firmware to connected device
├── proto_gen.ps1   Regenerate nanopb + Kotlin sources from .proto files
└── tile_fetch.py   Download MBTiles for a bounding box

docs/
├── radio-protocol.md   Wire format with annotated byte diagrams
├── ble-service.md      GATT UUID table and characteristic flows
└── game-protocol.md    Game packet flow diagrams
```

## Design document

All architecture, protocol, and implementation decisions are in [`PLANNING.md`](PLANNING.md).  
Do not modify `PLANNING.md` directly; changes require a versioned amendment.

## License

TBD.
