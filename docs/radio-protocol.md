# MASH Radio Protocol

**Planning:** §8.2, §8.3, §8.4, §8.6 | **Roadmap:** T.7

> **v1.4 NOTE:** Flags byte bits [2:0] carry `hop_start` — they are **NOT reserved**.
> See §8.2 amendment A. All relay nodes must preserve `hop_start` unchanged.
>
> **v1.5 NOTE:** Default hop limits are unified to 7 for every packet type except
> `MASH_AUDIO`, which remains local-only with hop limit 1. `MASH_ACK` uses the
> original packet's `hop_start`.

---

## 1. OTA Wire Format (§8.2)

Every MASH/Meshtastic over-the-air packet uses the following 12-byte header, followed by an encrypted payload:

```
Offset  Size  Field            Description
──────  ────  ───────────────  ──────────────────────────────────────────────────────────
  0      4    dest_node_id     Destination node ID (0xFFFFFFFF = broadcast), LE uint32
  4      4    src_node_id      Originating node ID (FICR.DEVICEID[0] lower 32 bits), LE uint32
  8      1    packet_id_low    Low 8 bits of the transport packet ID
  9      1    flags            [7:5] hop_limit (3 bits)
                               [4]   want_ack  (1 bit)
                               [3]   via_mqtt  (1 bit, always 0 for MASH)
                               [2:0] hop_start (3 bits) ← v1.4 amendment A (was "reserved")
 10      1    channel_hash     Channel disambiguation byte (§8.6)
 11      1    next_hop_low     Next-hop node ID low byte (0x00 for broadcast)
 12+     N    payload          AES-128-CTR encrypted nanopb-encoded Data message (§8.3)
```

**Total minimum packet size:** 12 bytes header + payload.

### Flags byte detail

```
Bit:  7   6   5   4   3   2   1   0
      ↑───────↑   ↑   ↑   ↑───────↑
      hop_limit   want_ack  hop_start
      (3 bits)    via_mqtt  (3 bits)
                  (1 bit)
```

- `hop_limit`: Decremented by each relay. Packet dropped when it reaches 0 before reaching destination.
- `hop_start`: Set by originator to the initial `hop_limit` value. **Never modified by relay nodes.** Used by receiver to compute ACK timeout: `timeout_ms = max(500, 2 × hop_start × 400)`.
- `want_ack`: Set when application-layer ACK is required (§8.9).
- `via_mqtt`: Always 0 for direct radio packets.

---

## 2. Encrypted Payload (§8.3)

The payload is the nanopb-encoded `meshtastic.Data` message, encrypted in-place with AES-128-CTR.

### Encryption parameters

| Parameter  | Value                                              |
|------------|----------------------------------------------------|
| Algorithm  | AES-128-CTR                                        |
| Key        | `SHA256(psk_bytes_padded_to_32)[0:16]`             |
| Nonce      | `packet_id_low` zero-extended to 32-bit LE ‖ `from_node_id` 32-bit LE ‖ `0x00…` (128 bits) |
| PSK pad    | Raw PSK zero-padded to 32 bytes before SHA256      |

The AES key is derived once per PSK change via `Crypto::deriveKey()`. MASH uses the OTA-visible packet ID byte for nonce construction because receivers can only reconstruct the low 8 bits from the compact wire header.

### `meshtastic.Data` fields used by MASH

```
Field             Type      Description
──────────────    ──────    ──────────────────────────────────────────
portnum           uint32    Port number (§8.4 table)
payload           bytes     Application payload bytes
want_response     bool      Request a response (TEXT_MESSAGE_APP ACK)
dest              uint32    Direct routing destination (0 = broadcast)
source            uint32    Originating node (used for relay attribution)
request_id        uint32    Message ID for ACKNOWLEDGED delivery tracking (§8.9)
reply_id          uint32    request_id of the message being replied to
```

---

## 3. Port Numbers (§8.4)

| Portnum | Value | Hop Limit | Reliability   | Description                        |
|---------|-------|-----------|---------------|------------------------------------|
| `UNKNOWN_APP`            |   0 | 3 | BEST_EFFORT   | Unused                             |
| `TEXT_MESSAGE_APP`       |   1 | 7 | BEST_EFFORT / ACK when requested | UTF-8 text message      |
| `POSITION_APP`           |   3 | 7 | BEST_EFFORT   | GPS position; `PositionConfig.position_hop_limit` can override when set |
| `NODEINFO_APP`           |   4 | 7 | BEST_EFFORT   | Node identity (HardwareModel, callsign) |
| `TELEMETRY_APP`          |  67 | 7 | BEST_EFFORT   | Battery/sensor telemetry           |
| `TRACEROUTE_APP`         |  71 | 7 | BEST_EFFORT   | Path discovery                     |
| `NEIGHBORINFO_APP`       |  73 | 7 | BEST_EFFORT   | Neighbor table exchange            |
| `MASH_PLAYER`            | 256 | 7 | ACKNOWLEDGED* | `PlayerStatePacket`                |
| `MASH_GAME`              | 257 | 7 | ACKNOWLEDGED  | `GameStatePacket`                  |
| `MASH_ZONE`              | 258 | 7 | ACKNOWLEDGED  | `ZonePacket`                       |
| `MASH_OBJECTIVE`         | 259 | 7 | ACKNOWLEDGED  | `ObjectivePacket`                  |
| `MASH_ADMIN`             | 260 | 7 | ACKNOWLEDGED  | `AdminPacket`                      |
| `MASH_ACK`               | 261 | = hop_start | — | `MashAck` (application-layer ACK)  |
| `MASH_POSITION_REQUEST`  | 262 | 7 | BEST_EFFORT   | `PositionRequestPacket`            |
| `MASH_AUDIO`             | 263 | 1 | BEST_EFFORT   | `AudioPacket` (Phase 7, local-only) |

*`MASH_PLAYER` uses ACKNOWLEDGED only when `PlayerStatePacket.is_state_transition = true`.

---

## 4. Channel Hash Algorithm (§8.6)

Each channel slot is identified on-air by a single hash byte in the OTA header. This allows receivers to quickly determine which channel (and therefore which AES key) applies to a packet.

| Slot index | Hash algorithm                                     |
|------------|----------------------------------------------------|
| 0          | XOR fold of all 32 PSK bytes: `hash = psk[0] ^ psk[1] ^ … ^ psk[31]` |
| 1–7        | `SHA256(psk_raw)[0]` — first byte of SHA-256 of raw PSK bytes |

Slot 0 uses the XOR-fold algorithm for byte-for-byte compatibility with Meshtastic channel hash computation. Slots 1–7 use SHA-256 for improved collision resistance.

Packets on different channels with the same hash byte (hash collision) will be **decrypted with both keys** by the receiver; the packet that successfully decodes to a valid proto structure is accepted.

---

## 5. Relay Delay (§8.7)

To reduce simultaneous relay collisions, each node delays before re-broadcasting using SNR:

```
delay_ms = (SNR_max − SNR_received) × 50 + random(0, 50)
```

Where `SNR_max` is the best observed SNR for this packet from any source, and `SNR_received` is this node's received SNR. A node that heard a better relay (overheard relay) cancels its own queued relay (heard-by suppression).

---

## 6. ACKNOWLEDGED Delivery (§8.9)

For ACKNOWLEDGED portnums, the sender:
1. Sets `want_ack = 1` in the OTA header.
2. Records `Data.request_id` in the retry queue.
3. Waits `timeout_ms = max(500, 2 × hop_start × 400)` ms for a `MASH_ACK` packet.
4. If no ACK received, retransmits with a **new `MeshPacket.id`** but the **same `Data.request_id`**.
5. After `ACK_MAX_RETRIES` attempts, reports delivery failure to BLE layer.

The receiver sends `MASH_ACK` with `MashAck.ack_request_id = Data.request_id` of the received packet.

> **CRITICAL:** `ack_request_id` references the **`Data.request_id`** field (logical message ID), NOT the `MeshPacket.id` (transport packet ID, which changes on every retry). See §8.9 amendment G.
