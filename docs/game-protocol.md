# AirTAK Game Protocol

**Planning:** §12 | **Roadmap:** T.9

---

## 1. Overview

The game protocol runs entirely over the mesh radio layer using AirTAK private port numbers (256–263). The Android companion app observes game state via the BLE MESH_RX characteristic and issues game-master commands via the ADMIN characteristic.

---

## 2. Game Phases

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
                    ▼                                         │
 [IDLE] ──start──> [LOBBY] ──start_game──> [ACTIVE] ──pause──> [PAUSED]
                                              │
                                           end_game
                                              │
                                              ▼
                                           [ENDED]
```

| Phase    | Value | Description                                         |
|----------|-------|-----------------------------------------------------|
| IDLE     | 0     | No game configured. Default at power-on.            |
| LOBBY    | 1     | Game configured, players joining. No scoring.       |
| ACTIVE   | 2     | Game running. Scoring active. HIT button enabled.   |
| PAUSED   | 3     | Scoring paused. Players maintain state.             |
| ENDED    | 4     | Game over. Win condition evaluated and announced.   |

Phase transitions are broadcast via `AIRTAK_GAME` (`GameStatePacket`). All transitions use ACKNOWLEDGED delivery.

---

## 3. AIRTAK_GAME Packet Flow (§12.1)

```
Game Master (Android)          Firmware (game master device)      Other Devices
       │                               │                               │
       │── AdminRequest(START_GAME) ──>│                               │
       │                               │── AIRTAK_GAME (phase=ACTIVE) ──>│
       │                               │<── AIRTAK_ACK ─────────────────│
       │<── AdminResponse(ok) ─────────│                               │
```

`GameStatePacket` fields:
- `game_phase` — `GamePhase` enum
- `game_mode`  — `GameMode` enum
- `duration_secs` — total game duration (0 = unlimited)
- `team_definitions` — repeated `TeamDefinition`
- `team_scores` — repeated `TeamScore`
- `timestamp_utc` — Unix timestamp (for clock sync)

---

## 4. AIRTAK_PLAYER Packet Flow (§12.6)

Player state changes (status transitions) are broadcast with `want_ack=true` and `is_state_transition=true`.
Non-transition heartbeat updates use `want_ack=false`.

```
Player Device                             All Devices
     │                                        │
     │── AIRTAK_PLAYER(status=HIT,              │
     │              is_state_transition=true) ─>│
     │<── AIRTAK_ACK ───────────────────────────│
```

`PlayerStatePacket` fields:
- `node_id` — originating node
- `status` — `PlayerStatus` enum (ACTIVE/HIT/RESPAWNING/ELIMINATED/SPECTATOR)
- `team_index` — team assignment
- `squad_index` — squad within team
- `callsign` — display name
- `is_state_transition` — true when status has changed (triggers ACKNOWLEDGED delivery)
- `respawn_seconds_remaining` — countdown value when RESPAWNING

---

## 5. AIRTAK_ZONE Packet Flow (§12.2)

Zone definitions are broadcast by the game master and relayed to all nodes.

```
Game Master                    Mesh Network
     │                              │
     │── AIRTAK_ZONE (zone_id=1) ────>│  (hop_limit=7, ACKNOWLEDGED)
     │<── AIRTAK_ACK ────────────────│
```

`ZonePacket` fields:
- `zone_id`, `zone_type` (`SPAWN`/`OBJECTIVE`/`EXCLUSION`/`SAFE`)
- `center` — `LatLng`
- `radius_m` — zone radius
- `name` — display name
- `controlled_by` — team index (-1 = contested/neutral)

---

## 6. AIRTAK_ACK Packet (§8.9)

`AirTAKAck` is the application-layer acknowledgement for ACKNOWLEDGED packets.

```
Receiver                              Sender
    │                                     │
    │<── AIRTAK_GAME (request_id=0xABCD) ──│
    │── AIRTAK_ACK (ack_request_id=0xABCD) >│
```

**CRITICAL:** `AirTAKAck.ack_request_id` references `Data.request_id` (the logical message ID), **not** the `MeshPacket.id` (transport ID, which changes on each retry). This allows the sender to match the ACK across retransmissions. See §8.9 amendment G.

`AirTAKAck` fields:
- `ack_request_id` — mirrors `Data.request_id` of the acknowledged packet
- `from_node_id` — node ID of the acknowledging device

The ACK hop_limit is set to the same `hop_start` value as the original packet, so the ACK travels back the same number of hops.

---

## 7. AIRTAK_OBJECTIVE Packet Flow (§12.3)

Used to update the state of named objectives (flags, control points).

`ObjectivePacket` fields:
- `objective_id`, `zone_id`
- `controlled_by` — team index (-1 = neutral)
- `capture_progress` — 0–100 (% toward capture)
- `name`

---

## 8. AIRTAK_ADMIN Packet Flow (§12.5)

Game-master administrative actions are broadcast via `AIRTAK_ADMIN`.
All `AdminPacket` messages are signed with the admin PSK to prevent spoofing.

```
Game Master                    Target Node                  All Nodes
     │                              │                           │
     │── AIRTAK_ADMIN (ELIMINATE) ──>│─────────────────────────>│
     │                              │ PlayerState → ELIMINATED  │
     │                              │── AIRTAK_PLAYER (ELIMINATED)>│
```

`AdminPacket` fields:
- `action` — `AdminAction` enum (ELIMINATE/SET_RESPAWN_TIMER/FORCE_POSITION_REPORT/…)
- `target_node_id` — affected node (0 = broadcast to all)
- `admin_psk_hash` — SHA256(admin_psk)[0:4] for authentication
- `param_int` — integer parameter (e.g., respawn seconds)

---

## 9. Audio PTT Flow (§12.7, §8.11 — Phase 7)

```
PTT Sender                        Mesh (hop_limit=1)         PTT Receivers
     │                                   │                        │
     │── AIRTAK_AUDIO (session_id=42,      │                        │
     │   audio_data=[8 frames],          │                        │
     │   fec_prev_audio_data=[])   ──────>│───────────────────────>│
     │                                   │                        │ (decode, play)
     │── AIRTAK_AUDIO (session_id=42,      │                        │
     │   audio_data=[8 frames],          │                        │
     │   fec_prev_audio_data=[prev]) ────>│───────────────────────>│
```

Key audio rules:
- `hop_limit = 1` **always** for `AIRTAK_AUDIO` (local mesh, no multi-hop relay).
- `fec_prev_audio_data` **MUST be empty** on the first packet of each PTT session (session boundary).
- `session_id` is a monotonically increasing counter per PTT press; used by receivers to reset jitter buffers.
- Audio codec: Codec2 1300 bps — 8 frames per packet = 160 ms of audio.
