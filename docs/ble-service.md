# AirTAK BLE Service

**Planning:** §10 | **Roadmap:** T.8

---

## 1. GATT Service

**Service UUID:** `4d415348-0000-1000-8000-00805f9b34fb`

All AirTAK-specific UUIDs follow the pattern `4d415348-{char_id:04x}-1000-8000-00805f9b34fb`.

---

## 2. Characteristics

| Characteristic  | UUID suffix | Properties                   | Security          | Max ATT value |
|-----------------|-------------|------------------------------|-------------------|----------|
| CONFIG_RW       | `0001`      | Read, Write, Indicate        | Mode 1, Level 3   | 514 B    |
| MESH_TX         | `0002`      | Write Without Response       | Mode 1, Level 3   | 514 B    |
| MESH_RX         | `0003`      | Notify                       | Mode 1, Level 3   | 514 B    |
| DEVICE_STATUS   | `0004`      | Read, Notify                 | Open (no auth)    | 514 B    |
| ADMIN           | `0005`      | Write, Indicate              | Mode 1, Level 3   | 514 B    |

- **Mode 1, Level 3:** Encrypted + MITM-authenticated (LE Secure Connections + Numeric Comparison).
- **Open:** No authentication required; readable by any connected central.

---

## 3. Security Model (§10.6)

- **Pairing mode:** LE Secure Connections (LESC) with Numeric Comparison.
- **Passkey display:** 6-digit confirmation code shown on E-Ink display.
- **Bonding:** Configurable up to 4 bonded Android devices; factory default is 1.
- Write characteristics (CONFIG_RW, MESH_TX, ADMIN) reject writes from unauthenticated/unencrypted connections with `ATT_ERROR_INSUFFICIENT_AUTHENTICATION`.
- DEVICE_STATUS is open to allow battery/GPS status monitoring before pairing.

---

## 4. Framing Protocol (§10.2)

All AirTAK characteristics use a 6-byte chunked framing header to support payloads larger than a single ATT value.

### Frame header (6 bytes, little-endian)

```
Offset  Size  Field           Description
──────  ────  ──────────────  ─────────────────────────────────────────────
  0      2    total_length    Total payload bytes across all chunks (UInt16 LE)
  2      2    chunk_index     0-based index of this chunk (UInt16 LE)
  4      2    chunk_count     Total number of chunks in this message (UInt16 LE)
```

**Chunk payload size:** `MTU (517) − ATT overhead (3) − frame header (6) = 508 bytes`. The maximum framed ATT value length is `508 + 6 = 514 bytes`.

For payloads ≤ 508 bytes: `chunk_count = 1`, `chunk_index = 0`.

### Flow (CONFIG_RW read/write)

1. Android serializes `ConfigPayload` proto → raw bytes.
2. Android splits into chunks of ≤ 508 bytes, prepends 6-byte header.
3. Android writes each chunk to CONFIG_RW (Write characteristic).
4. Firmware reassembles all chunks, parses `ConfigPayload` proto.
5. If `data` is empty and `commit=false`, firmware reads the current group and indicates a `ConfigPayload` containing the current bytes.
6. Otherwise, firmware applies/stages config and sends `ConfigAck` back via CONFIG_RW Indicate after any required radio restart completes.

### Flow (MESH_TX write)

1. Android serializes a decoded `meshtastic.Data` protobuf.
2. Android writes it to MESH_TX with the same 6-byte framing header.
3. Firmware reassembles the Data payload, selects the appropriate channel slot, applies hop limit and ACK policy, then encrypts and emits the OTA packet.

For compatibility with early tooling, firmware still accepts an already-encoded OTA frame on MESH_TX when the payload does not parse as `meshtastic.Data` and is at least 16 bytes long.

### Flow (MESH_RX notify)

Firmware decrypts accepted OTA packets before notifying Android. The notified payload is `portnum_hint_low_byte` (1 byte), followed by the 16-byte OTA header, followed by the decrypted `meshtastic.Data` protobuf bytes. Android treats the hint as legacy metadata and parses the protobuf for the full portnum.

---

## 5. Config Protocol Flow (§10.3)

```
Android                                     Firmware
   │                                            │
   │── Write ConfigPayload (chunk 0/N) ────────>│
   │── Write ConfigPayload (chunk N-1/N) ───────>│ ← reassemble
  │                                            │── read/apply config
  │<── Indicate ConfigPayload or ConfigAck ────│
  │    (read bytes, success, or error_code)    │
```

`ConfigPayload` proto fields:
- `group` — `ConfigGroup` enum (0–7)
- `data` — nanopb-encoded config group proto; empty with `commit=false` requests the current group
- `commit` — bool: `false` = stage/validate only; `true` = persist all staged groups to flash

### Radio restart notification

When `commit=true` persists any dirty LORA or CHANNELS group, the firmware performs the radio restart procedure (§9.4.2). The `ConfigAck` is returned **after** the restart completes and reports `radio_restarted=true`. During restart, `DEVICE_STATUS.radio_status = RESTARTING`.

---

## 6. DEVICE_STATUS Characteristic (§10.4)

The `DeviceStatus` proto is serialized and sent as a Notify every 5 seconds and on any state change.

Key fields:

| Field                  | Description                                         |
|------------------------|-----------------------------------------------------|
| `battery_level`        | 0–100 (%)                                           |
| `is_charging`          | True if USB charging detected                       |
| `gps_fix`              | True if GPS has a valid fix                         |
| `gps_lat_e7`           | Latitude × 10^7                                     |
| `gps_lon_e7`           | Longitude × 10^7                                    |
| `gps_hdop`             | HDOP × 100                                          |
| `radio_status`         | `RadioStatus` enum (READY / RESTARTING / ERROR)     |
| `active_phy_profile`   | Index of active PHY profile (0–3)                   |
| `mesh_node_count`      | Number of known mesh nodes                          |
| `firmware_version`     | Semantic version string                             |

---

## 7. Admin Commands (§10.5)

The ADMIN characteristic accepts `AdminRequest` proto (single write, no framing required) and responds with `AdminResponse` via Indicate.

| `AdminCommand` enum     | Action                                              |
|-------------------------|-----------------------------------------------------|
| `FACTORY_RESET`         | Erase all config files, reboot                      |
| `REBOOT`                | Software reset                                      |
| `ENTER_DFU`             | Enter Nordic DFU bootloader mode                    |
| `DISCONNECT`            | Terminate current BLE connection                    |
| `TRANSMIT_NODEINFO`     | Immediately broadcast NODEINFO_APP                  |
| `SET_GAME_MASTER`       | Grant this connected device game-master privileges  |
| `START_GAME`            | Send GameStatePacket(phase=ACTIVE) on mesh          |
| `PAUSE_GAME`            | Send GameStatePacket(phase=PAUSED)                  |
| `END_GAME`              | Send GameStatePacket(phase=ENDED)                   |
| `ELIMINATE_PLAYER`      | Send AdminPacket targeting a specific node          |
| `SET_RESPAWN_TIMER`     | Update respawn timer for a player                   |

Admin commands are authenticated: the firmware verifies that the connected device is bonded and that the request is signed with the `SecurityConfig.admin_psk` (if configured).
