# MASH — Mesh Airsoft Situational Hub
## Master Planning Document v1.5
### Status: IMMUTABLE AFTER ACCEPTANCE — 2026-05-13
### Amendments: v1.1 — 2026-05-13 — Multi-PHY per-channel-slot architecture; transparent PHY parameter model replacing opaque modem presets; all channel slots interchangeable between Meshtastic and MASH use; GPS position multi-hop relay fix; MASH_POSITION_REQUEST; per-packet-type hop limits; heard-by relay suppression; application-layer reliability (MeshCore-derived); PTT audio Phase 7; RECORD_AUDIO conditional permission
### Amendments: v1.2 — 2026-05-13 — 100-node airtime budget analysis; smart beaconing replacing fixed 30 s interval (movement-triggered + 90 s heartbeat); POSITION_APP and MASH_PLAYER hop_limit reduced 5→3; EU 868 MHz duty-cycle compliance guidance; audio packet size redesigned 73-frame→8-frame; FEC piggyback (fec_prev_audio_data); jitter buffer and PLC; AudioConfig.frame_count; infrastructure relay node guidance (§13.4); 3 new risks; Duty Cycle, FEC, PLC, Smart Beaconing, TDM glossary entries
### Amendments: v1.3 — 2026-05-13 — Three ACK reliability bugs fixed: (1) MASH_ACK hop_limit now mirrors original packet's hop_start so ACK can return across any mesh diameter; (2) ACK timeout scaled to 2×hop_start×400 ms (was flat 200 ms, too short for multi-hop); (3) retries use new packet_id + Data.request_id correlation to bypass relay duplicate-suppression cache; honest MeshCore comparison added to §8.9; MashAck proto updated; Phase 2.11 and integration tests updated
### Amendments: v1.4 — 2026-05-13 — Full protocol audit, 10 bugs fixed: (A) §8.2 Flags byte [2:0] corrected from reserved to hop_start — required for v1.3 ACK fix to be implementable; (B) §8.7 position relay exception removed — was dead code that could never trigger, gave false assurance; (C) §12.7/§8.1 audio ToA corrected from ~13 ms to ~84 ms with FEC (correct LoRa calculation for 92-byte packet); EU duty-cycle compliance guidance corrected — sub-band g (1%) is not viable at 1300 bps, sub-band g1 (10%) requires frame_count ≥ 42; (D) §8.7 MASH_POSITION_REQUEST hop_limit raised 3→5 for 10 km field coverage; (E) §12.7/§14.3 FEC cross-session contamination fixed — fec_prev_audio_data must be empty on first packet of each PTT session; (F) §8.9/§14.3 MASH_PLAYER ACK vs BEST_EFFORT ambiguity resolved — is_state_transition field added to PlayerStatePacket; (G) §14.3/§8.9 MashAck.ack_packet_id renamed to ack_request_id to prevent implementer confusion; (H) §14.3/§12.7 AudioPacket session_id field added — prevents lost end_of_transmission causing FEC chaos on session boundary; (I) §12.7 firmware-level audio PHY enforcement requirement added; (J) §8.9 retry backoff formula corrected 1000→200 ms base to match §20 risks table
### Amendments: v1.5 — 2026-05-14 — Five categories corrected: (1) All duty-cycle §8.1/§8.7/§12.7/§20/glossary/README reframed — EU duty cycle is a per-device regulatory obligation, not a mesh-governance concern; 100-node airtime figures are worst-case estimates, not guarantees one operator can control; smart beaconing helps each device comply but does not enforce mesh-wide limits. (2) Per-packet-type hop limit rationales updated — all players in the command hierarchy need information from all other players; text removed implying only certain role categories need certain messages. (3) Hop limits unified to 7 for all packet types except MASH_AUDIO (hop_limit=1, physical latency constraint); hop_limit does not create extra relay traffic — relay count is bounded by mesh topology, not the hop_limit field; airtime control is via smart beaconing TX interval, not restricted hop budgets. (4) §8.1 expanded with practical SF/BW/CR definitions explaining what each parameter means, how they interact, and why mismatches cause silent failures. (5) SHORT_SLOW-equivalent PHY profile added as Profile 3 (Medium/Slow, SF10/BW250/CR4/5); profile table expanded from 4 to 5 entries; all dependent sections updated (§8.1, §9.4.1, §11.9, §14.2)

---

## Table of Contents

1. [Project Identity](#1-project-identity)
2. [Background & Motivation](#2-background--motivation)
3. [Project Goals](#3-project-goals)
4. [Non-Goals & Explicit Exclusions](#4-non-goals--explicit-exclusions)
5. [Target Platforms](#5-target-platforms)
6. [System Architecture](#6-system-architecture)
7. [Hardware: LilyGo T-Echo Plus](#7-hardware-lilygo-t-echo-plus)
8. [Radio Protocol](#8-radio-protocol)
9. [Firmware Architecture](#9-firmware-architecture)
10. [BLE Interface Specification](#10-ble-interface-specification)
11. [Android Application](#11-android-application)
12. [Airsoft Feature Set](#12-airsoft-feature-set)
13. [Meshtastic Interoperability](#13-meshtastic-interoperability)
14. [Data Models & Schemas](#14-data-models--schemas)
15. [Security Model](#15-security-model)
16. [Development Phases](#16-development-phases)
17. [Repository Structure](#17-repository-structure)
18. [Build & Toolchain](#18-build--toolchain)
19. [Testing Strategy](#19-testing-strategy)
20. [Known Risks & Mitigations](#20-known-risks--mitigations)
21. [Glossary](#21-glossary)

---

## 1. Project Identity

| Field | Value |
|-------|-------|
| **Project Name** | MASH |
| **Full Name** | Mesh Airsoft Situational Hub |
| **Version** | 1.0 |
| **Date** | 2026-05-13 |
| **Status** | Immutable After Acceptance |

MASH is a purpose-built mesh radio and situational awareness system for airsoft. It consists of firmware for the LilyGo T-Echo Plus and a native Android companion app. MASH is interoperable with Meshtastic at the radio layer but replaces Meshtastic's configuration experience entirely and introduces a dedicated game-mode protocol and a situational awareness interface designed natively for airsoft — not retrofitted from military ATAK.

---

## 2. Background & Motivation

### 2.1 Meshtastic Pain Points

Meshtastic provides a solid LoRa mesh radio foundation but carries several serious usability failures:

- **Reboot-on-every-change.** Almost all configuration changes require a full device reboot. This is unnecessary for most settings and stems from the absence of a dynamic radio reconfiguration layer.
- **Multi-channel is broken in practice.** Running two channels simultaneously is theoretically supported but buggy enough that it cannot be relied on in the field.
- **Ambiguous configuration.** Setting names, units, and interactions between settings are inconsistently named, poorly documented, and non-obvious (e.g., `hop_limit` behavior differs by channel type; channel index and "role" interact in non-obvious ways).
- **No native game mode.** All airsoft use requires third-party plugins or ATAK bridges.
- **GPS positions are unreliable beyond direct range.** Meshtastic's default 15-minute position broadcast interval, combined with aggressive duplicate-suppression caching in the flood router, means a node 2+ hops away frequently disappears from the map for extended periods. There is no mechanism to request a fresh position from a specific node.
- **Hop limit is a blunt instrument.** The 3-bit hop_limit field supports a maximum of 7, but pure flooding at high hop counts saturates the radio channel exponentially. Meshtastic applies no per-packet-type priority; game state and player position share the same hop budget as low-priority device telemetry.
- **No application-layer delivery guarantee.** Meshtastic's `want_ack` is unreliable because it issues a single L4 acknowledgment with no retry mechanism. Dropped broadcast packets (game state changes, zone definitions) are permanently lost with no indication to the sender.
- **Modem preset confusion.** Meshtastic exposes radio parameters via opaque names (LONG_FAST, SHORT_SLOW, etc.) that reveal nothing about the underlying SF/BW/CR values. A common mistake is setting different presets on different devices — they become silently unable to communicate because they use incompatible LoRa modulation parameters.

### 2.2 ATAK Pain Points

The Android Team Awareness Kit is used as a situational awareness layer on top of Meshtastic via the Meshtastic ATAK plugin. Problems include:

- **Permissions overreach.** ATAK demands nearly every Android permission before it will launch, including permissions entirely unrelated to its function.
- **Outdated UX.** ATAK's interface is a direct port of military desktop GIS software not designed for mobile use.
- **Loose coupling.** The Meshtastic-ATAK integration is a plugin bridge with data model mismatches and latency artifacts.
- **Not designed for airsoft.** ATAK's terminology, workflows, and data model are military-oriented. Mapping them to airsoft requires per-game reconfiguration that resets between sessions.

### 2.3 Meshcore

Meshcore improves on some Meshtastic technical limitations but is not protocol-compatible with the Meshtastic device ecosystem. Since a large number of existing devices run Meshtastic, protocol incompatibility is a hard deal-breaker.

### 2.4 MASH's Position

MASH speaks Meshtastic's protocol on the shared channel, making it a first-class participant in any existing Meshtastic mesh. On its dedicated game channel it runs an extended protocol optimized for airsoft game-mode operation. The Android app is a ground-up replacement for the Meshtastic app + ATAK combination, built with modern Android practices and a minimal permission footprint.

---

## 3. Project Goals

### G1 — Meshtastic Radio Interoperability
MASH devices MUST be able to send and receive standard Meshtastic packets on the primary channel. A MASH device MUST appear as a valid node to a stock Meshtastic device and MUST relay Meshtastic packets correctly.

### G2 — Hot-Reload Configuration
Configuration changes MUST NOT require a device reboot unless they demand physical re-initialization of non-reconfigurable hardware (defined in §9.4). The target for any configuration change is: visible effect within 500 ms, no user-visible device restart.

### G3 — Working Multi-Channel
MASH MUST support at minimum 2 simultaneous active channels. Both channels MUST independently receive and decrypt packets in real time. Channel 0 is the Meshtastic-interop channel; channel 1 is the MASH game channel.

### G4 — Native Airsoft Feature Set
The system MUST natively support: player position sharing, team assignment, per-player state (active / hit / eliminated), game zone boundaries, game start/stop signaling, and field-wide text messaging. These features MUST require zero external plugins or companion apps beyond the MASH Android app.

### G5 — Minimal Android Permissions
The Android app MUST function with only the permissions strictly required by the Android API for Bluetooth LE and location access. It MUST NOT request permissions for camera, microphone, contacts, phone state, or external storage unless a specific optional feature is enabled by the user and that feature demonstrably requires it.

### G6 — Offline-First
The Android app MUST function entirely without an internet connection. All map tiles, routing, and game logic run on-device. Internet access is used only for optional map tile pre-downloads.

### G7 — Modern Android
The Android app MUST target Android API 35 and have a minimum supported API of 31 (Android 12). It MUST use Jetpack Compose as the sole UI framework.

### G8 — T-Echo Plus Primary Target
The firmware MUST produce a fully functional build for the LilyGo T-Echo Plus. Supporting other hardware is a secondary concern and MUST NOT compromise T-Echo Plus behavior or code clarity.

---

## 4. Non-Goals & Explicit Exclusions

The following are deliberately out of scope and will not be implemented:

- **iOS support.** Android only.
- **Meshtastic app compatibility.** MASH does not aim to be usable from the Meshtastic Android or iOS apps beyond basic mesh membership (text messages received by Meshtastic users on the shared channel).
- **ATAK compatibility.** The MASH Android app does not implement the ATAK plugin API or the Cursor-on-Target (CoT) format.
- **Web interface.** No browser-based admin or configuration UI.
- **Cloud sync.** No server-side components, no user accounts, no telemetry.
- **Multi-region automatic switching.** LoRa region is set at first-time setup and requires an explicit user action to change.
- **Frequency hopping.** Fixed-channel LoRa only; no FHSS.
- **Other LoRa hardware.** Devices such as TTGO LoRa32 or RAK WisBlock are not supported firmware targets in any planned phase. The firmware HAL is structured to allow future targets without breaking the T-Echo Plus build.
- **Meshtastic store-and-forward, MQTT bridge, serial module, rangetest module, canned-messages module.** These Meshtastic features are not implemented. Their absence does not affect protocol compatibility with Meshtastic nodes.

---

## 5. Target Platforms

### 5.1 Firmware Target

| Component | Specification |
|-----------|--------------|
| Board | LilyGo T-Echo Plus |
| MCU | Nordic nRF52840 (ARM Cortex-M4F @ 64 MHz) |
| Flash | 1 MB internal + 4 MB SPI NOR (GD25Q32C) |
| RAM | 256 KB |
| LoRa radio | Semtech SX1262 |
| GNSS | Quectel L76K (GPS / GLONASS / BeiDou) |
| Display | 1.54″ E-Ink 200 × 200 px, SSD1681 controller |
| Connectivity | Bluetooth 5.0 BLE, NFC |
| Power | 400 mAh LiPo, USB-C charging via TP4054 |

### 5.2 Android Target

| Field | Value |
|-------|-------|
| Minimum SDK | 31 (Android 12) |
| Target SDK | 35 (Android 15) |
| ABI | arm64-v8a, x86_64 |
| Language | Kotlin |
| UI framework | Jetpack Compose |

---

## 6. System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Android Application                      │
│  ┌────────────┐  ┌──────────┐  ┌────────────┐  ┌────────┐  │
│  │  Map View  │  │ Messages │  │  Players   │  │  Game  │  │
│  └────────────┘  └──────────┘  └────────────┘  └────────┘  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                Game Engine (Kotlin)                   │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              BLE Transport Layer                      │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────┬────────────────────────────────┘
                              │ Bluetooth LE
┌─────────────────────────────▼────────────────────────────────┐
│                   T-Echo Plus Firmware                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ BLE GATT Srv │  │  Config Mgr  │  │   Display Driver │  │
│  └──────────────┘  └──────────────┘  └──────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                    Mesh Stack                         │   │
│  │  ┌──────────────┐  ┌────────────────────────────┐   │   │
│  │  │   Router     │  │   Channel Manager           │   │   │
│  │  └──────────────┘  └────────────────────────────┘   │   │
│  │  ┌──────────────┐  ┌────────────────────────────┐   │   │
│  │  │  AES Crypto  │  │   Flood / Relay Logic       │   │   │
│  │  └──────────────┘  └────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────┐  ┌────────────┐  ┌──────────────────┐    │
│  │  SX1262 Drv  │  │  L76K Drv  │  │  Power Manager   │    │
│  └──────────────┘  └────────────┘  └──────────────────┘    │
└─────────────────────────────┬────────────────────────────────┘
                              │ LoRa RF
┌─────────────────────────────▼────────────────────────────────┐
│                       Mesh Network                           │
│          MASH nodes   +   Meshtastic nodes (interop)        │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. Hardware: LilyGo T-Echo Plus

### 7.1 Pin Mapping

> **Mandatory**: This mapping must be verified against the actual T-Echo Plus hardware schematic before the first firmware compilation. The Meshtastic T-Echo board definition is the reference for any discrepancies. Commit the verified mapping as a comment block in `firmware/boards/t_echo_plus.json`.

| Peripheral | Signal | nRF52840 Pin |
|------------|--------|--------------|
| SX1262 | SCK | P0.19 |
| SX1262 | MISO | P0.23 |
| SX1262 | MOSI | P0.22 |
| SX1262 | NSS (CS) | P0.24 |
| SX1262 | RESET | P0.25 |
| SX1262 | DIO1 (IRQ) | P0.20 |
| SX1262 | BUSY | P0.17 |
| SX1262 | TXEN | P1.13 |
| SX1262 | RXEN | P1.14 |
| E-Ink | SCK | P0.14 |
| E-Ink | MOSI | P0.13 |
| E-Ink | CS | P0.30 |
| E-Ink | DC | P0.28 |
| E-Ink | RST | P0.02 |
| E-Ink | BUSY | P0.03 |
| L76K GPS | UART RX (MCU) | P1.09 |
| L76K GPS | UART TX (MCU) | P1.08 |
| L76K GPS | PPS | P1.04 |
| L76K GPS | ENABLE | P1.02 |
| Battery ADC | VBAT sense | P0.04 (AIN2) |
| Charge IC | CHG_STAT | P0.05 |
| User button | USER | P1.10 (active low) |
| NFC antenna | NFC1 / NFC2 | P0.09 / P0.10 |
| SPI NOR flash | (shared SPI, verify CS pin) | TBD at schematic review |

### 7.2 Power Budget

| State | Estimated Current |
|-------|------------------|
| Deep sleep (all off) | ~8 µA |
| Radio RX only, GPS off | ~15 mA |
| Radio RX, GPS acquiring | ~25 mA |
| Radio RX, GPS fixed | ~18 mA |
| Radio TX peak (22 dBm) | ~120 mA |
| E-Ink refresh (active) | ~30 mA |
| E-Ink static | ~0 mA |

**Target battery life**: ≥ 8 hours continuous operation with GPS fix acquired, radio active, and display refreshed every 5 minutes on the 400 mAh battery. This is validated in Phase 6.

---

## 8. Radio Protocol

### 8.1 PHY Layer

MASH uses LoRa modulation via the SX1262. The parameters that govern range, throughput, and air time are: Spreading Factor (SF), Bandwidth (BW), and Coding Rate (CR). **Two devices can only communicate if they share the same SF, BW, CR, and frequency.** This is a LoRa physics constraint, not a MASH design choice. Meshtastic's opaque preset names (LONG_FAST, SHORT_SLOW, etc.) abstract these parameters in a way that makes this constraint invisible — a leading cause of silent configuration failures. MASH exposes the parameters explicitly.

**Global parameters** — apply to all channel slots; set once per device:

| Parameter | Default | Notes |
|-----------|---------|-------|
| Frequency | 869.525 MHz (EU868) / 906.875 MHz (US915) | Determined by region at first-time setup; requires radio restart to change |
| TX Power | +22 dBm | SX1262 maximum; configurable 2–22 dBm |
| Sync word | 0x2B | Meshtastic network identifier — must match across all interoperating nodes |
| Preamble length | 16 symbols | |
| Header mode | Explicit | |
| CRC | Enabled | |
| Invert IQ | Disabled | |

**PHY profiles** — each channel slot references one profile by index; see §8.5 for the per-slot binding mechanism.

MASH defines up to 5 named PHY profiles per device. Profiles 0, 1, 3 are pre-defined defaults; profiles 2 and 4 are user-configurable. The Meshtastic equivalent column is provided for cross-reference only; MASH's UI displays the raw SF/BW/CR parameters, not Meshtastic codenames.

| Profile Index | Name | SF | BW | CR | Max Payload Air Time | Effective Data Rate | Range (open, +22 dBm) | Meshtastic Ref |
|--------------|------|----|----|----|----------------------|---------------------|-----------------------|----------------|
| 0 | Long / Mesh | SF11 | 250 kHz | 4/5 | ~680 ms | ~0.9 kbps | 3–8 km LOS | LONG_FAST |
| 1 | Short / Speed | SF7 | 250 kHz | 4/5 | ~85 ms | ~5.5 kbps | 0.5–2 km LOS | SHORT_FAST |
| 2 | Long / Reliable | SF12 | 125 kHz | 4/8 | ~3 500 ms | ~0.2 kbps | 5–15 km LOS | LONG_SLOW |
| 3 | Medium / Slow | SF10 | 250 kHz | 4/5 | ~340 ms | ~1.9 kbps | 2–5 km LOS | SHORT_SLOW |
| 4 | User Defined | configurable | configurable | configurable | — | — | — | — |

All five profiles use the identical sync word, preamble, header mode, CRC, and IQ settings shown in the global parameters table. This ensures that a MASH node with profile 0 on slot A and profile 1 on slot B is interoperable with Meshtastic LONG_FAST and SHORT_FAST nodes respectively on those respective slots.

**What SF, BW, and CR mean in practice:**

LoRa modulation trades speed for range along three independent axes. Every node on a channel must agree on all three values — a mismatch on any one axis makes communication impossible.

- **Spreading Factor (SF, range 7–12).** SF controls how many "chirps" encode each data symbol. Each increment of +1 doubles the number of chirps per symbol, which doubles the time-on-air for the same payload. Higher SF improves receiver sensitivity by roughly 2.5 dB per step (i.e., the receiver can decode a weaker signal), extending range at the cost of throughput. SF7 is the fastest (shortest range), SF12 is the slowest (longest range). Think of SF as *how many times each bit is repeated* — a higher SF repeats each bit more, so the receiver can still hear it through noise and distance.

- **Bandwidth (BW, 125 / 250 / 500 kHz).** BW is the width of the radio channel. Wider bandwidth carries data faster because each chirp is shorter in time. Doubling the bandwidth roughly halves the time-on-air but also halves the receiver sensitivity (≈ −3 dB per doubling). 125 kHz gives the longest range but slowest speed; 500 kHz gives the shortest range but highest speed; 250 kHz is the default balance point. Think of BW as *how wide the pipe is* — a wider pipe moves water faster but spreads it thinner.

- **Coding Rate (CR, expressed as 4/N where N = 5, 6, 7, or 8).** CR controls the ratio of error-correction bits appended to each payload chunk. 4/5 means for every 4 data bits, 1 error-correction bit is added (20 % overhead). 4/8 means for every 4 data bits, 4 error-correction bits are added (100 % overhead). Higher overhead (larger N) makes the transmission more resilient to interference at the cost of longer time-on-air. The default 4/5 is sufficient for most outdoor airsoft conditions. Think of CR as *how many spare copies of the payload are included* — more copies = more resilient to corruption, but takes longer to send.

**Interaction example:** SF7 + BW500 + CR 4/5 is the fastest possible combination (shortest range, ~1 km). SF12 + BW125 + CR 4/8 is the slowest (longest range, >10 km). Profile 0 (SF11 + BW250 + CR 4/5) is the recommended starting point — it balances range and throughput for a typical outdoor airsoft field.

**Field size guidance:**

| Field Size | Recommended Profile | Notes |
|------------|--------------------|---------|
| ≤ 300 m | Profile 1 (Short/Speed) | Higher throughput; lower per-packet latency; supports PTT audio (Phase 7) |
| 300 m – 3 km | Profile 0 (Long/Mesh) | Default; appropriate for most outdoor airsoft events |
| 1–5 km | Profile 3 (Medium/Slow) | Higher throughput than Long/Mesh while retaining good range; useful for game-event traffic on mid-size fields |
| > 3 km | Profile 2 (Long/Reliable) or User Defined | Extended range at the cost of very low throughput |

All nodes participating on a given channel must use the same PHY profile. The Android app channel editor displays a compatibility indicator warning when a channel slot's profile differs from that of known nearby nodes.

**Airtime budget — 100-node deployment:**

The table below shows typical time-on-air (ToA) for a 50-byte mesh packet and the resulting shared-channel utilisation in a theoretical 100-player deployment where every node transmits at the given interval. EU 868 MHz sub-band g (1 % duty cycle, ETSI EN 300 220-2) limits **each device individually** to ≤ 1 % transmission time over a rolling 1-hour window — this is a per-device legal obligation of each operator, not a limit MASH can enforce across the mesh. The 100-node column is a worst-case capacity estimate; actual channel load depends on how many independent operators are present and how they have configured their devices.

| Profile | ToA / 50-byte pkt | EU DC limit per node | 100-node load @ 30 s interval | 100-node load @ 90 s interval |
|---------|-------------------|-----------------------|-------------------------------|-------------------------------|
| 0 — Long/Mesh (SF11/BW250) | ~310 ms | ≈ 2 pkts/min | ~103 % ⚠ SATURATED | ~34 % |
| 1 — Short/Speed (SF7/BW250) | ~38 ms | ≈ 15 pkts/min | ~13 % | ~4 % |
| 2 — Long/Reliable (SF12/BW125) | ~2 700 ms | ≈ 0.2 pkts/min | > 900 % ⚠ | > 300 % ⚠ |
| 3 — Medium/Slow (SF10/BW250) | ~150 ms | ≈ 4 pkts/min | ~50 % ⚠ | ~17 % |

Key constraints:
- **EU 1 % duty cycle (sub-band g)**: Profile 0 at 310 ms ToA means each device may legally transmit ≈ 1 packet per 31 s on sub-band g. MASH's smart beaconing defaults (§8.7) are designed so that a device with default settings operates within this limit; the operator is ultimately responsible for ensuring their device's configuration complies with their region's regulations.
- **Hop amplification and the hop_limit safety margin**: Each source packet triggers one relay per node within radio range, bounded by the mesh topology — not by the hop_limit field. A hop_limit of 7 on a 5-hop diameter mesh does not create extra relay transmissions because no nodes exist at hop distances 6 or 7. Setting hop_limit to the maximum available (7) ensures delivery to the furthest node and costs nothing in airtime beyond what the mesh topology already dictates. The table's 100-node load columns account only for source transmissions; relay amplification adds roughly `(diameter − 1)` copies per packet. At a 4-hop mesh diameter this is a ~3× multiplier — the reason smart beaconing's 90 s heartbeat (§8.7) is the primary airtime control, not hop_limit restrictions.
- **MASH game events**: Routing game-critical packets (MASH_GAME, MASH_ZONE, etc.) on Profile 1 or Profile 3 consumes less airtime per packet than Profile 0 and is recommended when sub-second game-state propagation is required.

### 8.2 Over-the-Air Packet Format

MASH uses Meshtastic's packet wire format without modification. The frame transmitted over LoRa is:

```
Offset  Length  Field
0       4       Destination node ID (little-endian uint32; 0xFFFFFFFF = broadcast)
4       4       Source node ID (little-endian uint32)
8       1       Packet ID low byte
9       1       Flags: [7:5] hop_limit, [4] want_ack, [3] via_mqtt, [2:0] hop_start
10      1       Channel hash (see §8.5)
11      1       Next-hop node ID (low byte; 0 = not yet relayed)
12      N       Encrypted payload (protobuf Data message, AES-128-CTR)

> **`hop_start`** (Flags bits [2:0]): the original `hop_limit` value set by the sender, preserved unchanged across all relay hops. Relay nodes decrement `hop_limit` but leave `hop_start` intact. The ACK receiver uses `hop_start` to set the return `hop_limit` on MASH_ACK so the acknowledgment can traverse the full mesh diameter back to the sender (see §8.9). This matches the Meshtastic wire format at the pinned commit; `[2:0]` was incorrectly documented as `reserved` in earlier versions of this document. If a packet is received from a device that does not encode `hop_start` (zero value), the ACK receiver MUST default to `hop_limit = 7` on the reply.
```

This format is byte-for-byte identical to the Meshtastic wire format, enabling native mesh participation and relay by stock Meshtastic devices.

### 8.3 Packet Payload

The payload is the Meshtastic `Data` protobuf message:

```protobuf
message Data {
  uint32 portnum = 1;
  bytes  payload = 2;
  bool   want_response = 3;
  uint32 dest = 4;
  uint32 source = 5;
  uint32 request_id = 6;
  uint32 reply_id = 7;
  uint32 emoji = 8;
}
```

When channel encryption is enabled the entire serialized `Data` is encrypted with AES-128-CTR before transmission. See §15.1 for key derivation details.

### 8.4 Port Numbers

Standard Meshtastic portnums are fully supported on channel 0:

| Portnum | Name | Notes |
|---------|------|-------|
| 1 | TEXT_MESSAGE_APP | Plain text messages |
| 3 | POSITION_APP | GPS position broadcast |
| 4 | NODEINFO_APP | Node identity / hardware info |
| 67 | TELEMETRY_APP | Battery and environment telemetry |
| 71 | TRACEROUTE_APP | Hop-path tracing |
| 73 | NEIGHBORINFO_APP | Neighbor SNR table |

MASH-specific portnums occupy the private range 256–511. These packets are sent on any channel slot whose `downlink_enabled = true` and that is configured with the MASH game PSK. They are silently relayed but not decryptable by stock Meshtastic nodes on other channel slots.

| Portnum | Name | Description |
|---------|------|-------------|
| 256 | MASH_PLAYER | Player state update (callsign, team, status) |
| 257 | MASH_GAME | Game phase broadcast (start, pause, stop, scores) |
| 258 | MASH_ZONE | Zone polygon definition or update |
| 259 | MASH_OBJECTIVE | Objective capture state |
| 260 | MASH_ADMIN | Admin command (game control, player override) |
| 261 | MASH_ACK | Application-layer acknowledgment for acknowledged-delivery packets |
| 262 | MASH_POSITION_REQUEST | Request immediate position re-broadcast from a specific node |
| 263 | MASH_AUDIO | Codec2-encoded PTT audio frame (Phase 7; Short/Speed PHY only) |

### 8.5 Channel Architecture

MASH manages 8 channel slots numbered 0–7. Each slot is independently configured and stored. Any slot can carry Meshtastic-protocol traffic, MASH game traffic, or both — the distinction is determined purely by the PSK and portnum, not by the slot number. There is no hard assignment of “slot 0 = Meshtastic” and “slot 1 = game”; users may configure any layout that matches their network.

| Field | Type | Description |
|-------|------|-------------|
| `index` | uint8 | Slot number 0–7 |
| `name` | string[16] | Human-readable channel name |
| `psk` | bytes[32] | Pre-shared key (empty = no encryption) |
| `role` | enum | PRIMARY / SECONDARY / DISABLED |
| `uplink_enabled` | bool | Forward received packets to phone |
| `downlink_enabled` | bool | Accept packets from phone for TX |
| `position_precision` | uint8 | GPS precision level 0–32 |
| `phy_profile_index` | uint8 | Index into the device's PHY profile table (see §8.1); default 0 |

**Default slot assignments (factory defaults):**
- Slot 0: role=PRIMARY, name="MASH", phy_profile_index=0 (Long/Mesh). PSK configured to match local Meshtastic network or a shared MASH group key. Carries Meshtastic-compatible AND MASH game traffic.
- Slot 1: role=SECONDARY, name="Game", phy_profile_index=0 (Long/Mesh). Separate PSK for MASH game-only traffic when channel isolation from Meshtastic is desired.
- Slot 2: role=DISABLED, name="Audio", phy_profile_index=1 (Short/Speed). Pre-configured but disabled; activated in Phase 7 for PTT audio.
- Slots 3–7: role=DISABLED.

**Same-PHY multi-channel (standard operation):** When all active slots share the same `phy_profile_index`, the SX1262 listens on one radio configuration continuously. Channel disambiguation is done by the channel hash byte in each received packet header (see §8.6). This is O(1) per packet and has no performance cost. This is the same mechanism used internally by Meshtastic multi-channel. Up to 8 channels can be active simultaneously with no radio switching overhead.

**Multi-PHY operation (Phase 7 audio):** When active slots reference different `phy_profile_index` values, the SX1262 must time-multiplex between PHY configurations. During each radio cycle the device switches to each active PHY profile in sequence for a configurable dwell time. Packets on other profiles are missed during each profile's dwell window.
- The Long/Mesh channel (profile 0) is the primary profile; the radio spends most of its time there.
- The Short/Speed audio channel (profile 1) is entered only during active PTT transmission/reception, not continuously.
- Estimated Long/Mesh packet loss during a 5-second PTT burst: ≤ 15%. Application-layer retry (§8.9) covers ACKNOWLEDGED packets missed during this window.
- Multi-PHY operation requires a radio restart (≤ 300 ms, no MCU reboot) when profile assignments change.

### 8.6 Channel Hash Computation

The channel hash is an 8-bit value placed in every packet header to allow fast pre-decryption channel identification.

- **Channel 0**: Channel hash is computed using the identical algorithm as Meshtastic — XOR of all bytes of the 32-byte PSK, then truncated to 8 bits. This exact match is required for interoperability with Meshtastic devices on the shared channel.
- **Channels 1–7**: Channel hash is `SHA256(psk)[0]` (first byte of SHA-256 of the PSK). This is a clean, well-defined computation and provides sufficient disambiguation for up to 8 slots.

### 8.7 Routing

MASH uses a modified flooding router compatible with Meshtastic's `ManagedFlood` algorithm:

- Packets are re-broadcast up to `hop_limit` times (maximum 7; default varies by packet type — see table below).
- `hop_limit` is decremented per hop; packets at hop_limit=0 are received but not relayed.
- Duplicate suppression: a rolling cache of (source_id, packet_id) pairs; any duplicate is discarded without relay.
- **SNR-based relay delay**: Before relaying, a node waits a random backoff time inversely proportional to received SNR. Nodes with better SNR relay sooner and suppress redundant relays from worse-SNR neighbors. Formula: `delay_ms = (SNR_max − SNR_received) × 50 + random(0, 50)`.
- **Heard-by suppression**: If a node overhears a relay of a packet it has queued to relay (same source_id + packet_id from a different relayer), and the overheard relay's SNR was ≥ its own received SNR, the queued relay is cancelled. This fires during the relay backoff window, preventing the exponential rebroadcast problem without a central coordinator.
- **Position packet freshness**: Every new position broadcast generates a new `MeshPacket.id`. Duplicate suppression operates on `(source_id, packet_id)`; because each broadcast has a unique packet ID, no special timestamp exception is needed. Position freshness is maintained by the smart beaconing policy (§8.7) and by the MASH_POSITION_REQUEST on-demand refresh (portnum 262). There is no timestamp-based relay exception — such a mechanism would be dead code because the same packet ID cannot carry a different timestamp.

**Per-packet-type default hop limits** (configurable via firmware `Defaults.h`):

| Packet Type | Default hop_limit | Rationale |
|-------------|-------------------|-----------|
| TEXT_MESSAGE_APP | 7 | Standard text; must reach every player regardless of position on the field; a squad leader on one side of the map must receive messages from the commander on the opposite side |
| POSITION_APP | 7 | Every player's position must be visible to every other player; hop_limit must match the full mesh diameter so no position goes stale for nodes at the far edge |
| NODEINFO_APP | 7 | Node identity must propagate across the full mesh so every device knows about every other device |
| TELEMETRY_APP | 7 | Battery and environment telemetry reaches all nodes; situational awareness includes which devices are running low on power |
| MASH_PLAYER | 7 | Player state (active/hit/respawning/eliminated) must propagate to every node in the command hierarchy; a squad leader on the opposite end of the map must see player state changes from all fireteams |
| MASH_GAME | 7 | Game phase broadcast must reach every player; maximum hop budget |
| MASH_ZONE | 7 | Zone definitions must reach every player; maximum hop budget |
| MASH_OBJECTIVE | 7 | Objective capture state must reach every player; maximum hop budget |
| MASH_ADMIN | 7 | Admin commands must reach every player; maximum hop budget |
| MASH_ACK | mirrors original | ACK must be able to return to the sender along any path the original took; hop_limit is set to the original packet's hop_start (see §8.9) |
| MASH_POSITION_REQUEST | 7 | Unicast position request must reach the target node regardless of distance; maximum hop budget |
| MASH_AUDIO | 1 | Audio must never be relayed; relay latency makes audio unintelligible — the sole packet type with a restricted hop budget due to a hard physical constraint |

The hop_limit for POSITION_APP can be overridden by the user via `PositionConfig.position_hop_limit`. Other per-type defaults are hardcoded in the firmware router's packet-type dispatch table.

**Smart Beaconing**

Transmitting position at a fixed short interval is incompatible with EU duty-cycle limits and would contribute to channel load in a typical 100-player deployment (see §8.1 airtime budget). MASH uses a movement-triggered broadcast policy:

- Position is sent when **GPS displacement ≥ 10 m** since the last TX, provided at least **15 s** have elapsed since the last TX (prevents bursts during rapid GPS updates).
- Position is sent when **90 s have elapsed** since the last TX regardless of movement (heartbeat).
- Players marked **dead or respawning** extend the heartbeat to **5 min** (no tactically relevant movement occurs in this state).
- A MASH_POSITION_REQUEST unicast (portnum 262) causes the target to send a position packet within **2 s**, bypassing the beaconing timer. This lets the Android map screen refresh a stale pin on demand.

Under typical active-player movement, this yields an average TX rate of ≈ 1 packet per 60 s, helping each device stay within its EU duty-cycle obligations at Profile 0 and keeping typical channel utilisation low in a 100-player deployment.

The `POSITION.broadcast_interval_secs` config field (§9.4.1) sets the heartbeat maximum; default 90. The movement threshold is fixed at 10 m in `Defaults.h` and is not user-configurable.

### 8.8 Node ID Assignment

Node IDs are 32-bit values. MASH derives the node ID from the lower 32 bits of the nRF52840's hardware UID register (FICR.DEVICEID[0]), matching Meshtastic's assignment scheme. This guarantees uniqueness within reasonable mesh sizes.

### 8.9 Application-Layer Reliability

Meshtastic's flood router provides best-effort delivery. MASH adds a lightweight application-layer reliability layer inspired by MeshCore, without breaking wire-format compatibility with Meshtastic nodes.

**Reliability classes:**

| Class | Behavior | Used For |
|-------|----------|----------|
| BEST_EFFORT | Standard flood, no retry | POSITION_APP, TELEMETRY_APP, MASH_AUDIO |
| ACKNOWLEDGED | Send + wait for MASH_ACK (portnum 261); retry up to 3 times with jittered exponential backoff | TEXT_MESSAGE_APP (unicast), MASH_PLAYER **state transitions** (see note below), MASH_GAME, MASH_ZONE, MASH_OBJECTIVE, MASH_ADMIN |

> **MASH_PLAYER ACKNOWLEDGED vs BEST_EFFORT rule**: A MASH_PLAYER packet is sent ACKNOWLEDGED (`Data.want_response = true`, `PlayerStatePacket.is_state_transition = true`) **only when the player's status field changes** (e.g., ACTIVE→HIT, HIT→RESPAWNING, RESPAWNING→ACTIVE, any admin-forced transition). Heartbeat position-update packets where status is **unchanged** are sent BEST_EFFORT (`Data.want_response = false`, `PlayerStatePacket.is_state_transition = false`). This prevents the 8-entry retry queue from being saturated by heartbeats and ensures state transitions receive all three retry slots. MASH_POSITION_REQUEST responses are always BEST_EFFORT.

**ACKNOWLEDGED delivery flow:**

All ACKNOWLEDGED packets set `Data.want_response = true`. On the first send, `Data.request_id` is set to the same value as `MeshPacket.id` (the logical message ID). On retries, a **new** `MeshPacket.id` is generated but `Data.request_id` keeps the original value — this is how the ACK can correlate back to the sender's retry queue regardless of which attempt was received.

1. Sender generates a logical message ID (`msg_id`) and a LoRa packet ID (`pkt_id = msg_id`); sets `Data.request_id = msg_id`, `Data.want_response = true`.
2. Sender adds `msg_id` to the retry queue (max 8 concurrent in-flight).
3. Receiver decodes the packet, deduplicates by `(source_id, Data.request_id)` at the application layer (idempotent for all ACKNOWLEDGED types — game state and player state applied twice is safe; text messages dedup by source + request_id), then sends MASH_ACK with `ack_request_id = Data.request_id` and `hop_limit = received packet's hop_start` (from Flags bits [2:0] of the wire header; if zero, use hop_limit=7 as safe default).
4. If sender receives a MASH_ACK with `ack_request_id == msg_id` within `ack_timeout_ms`, the entry is removed from the retry queue. **Timeout formula**: `ack_timeout_ms = max(500, 2 × hop_start × 400)`. At hop_start=3 this is 2 400 ms; at hop_start=7 this is 5 600 ms. The 400 ms per-hop budget covers 310 ms ToA (Profile 0) plus relay backoff headroom.
5. On timeout, generate a new `pkt_id` (leaving `Data.request_id = msg_id` unchanged), retransmit. Backoff between retries: `wait_ms = random(0, 200 × 2^attempt)`. Retransmit up to 3 times. After 3 failures, notify the BLE client of delivery failure.
6. **Implicit ACK**: if the sender overhears a relay of a packet with the same `source_id + Data.request_id` from a different node, this is treated as a successful delivery and cancels pending retries. This is an optimisation for broadcast packets, not a reliability guarantee — an isolated border node may never overhear its own relay.

**Retry correctness under duplicate suppression:**

Because each retry carries a **new `MeshPacket.id`**, relay nodes that already forwarded earlier attempts do not suppress retries. This is the critical difference from same-id retries: if the original was relayed by R1 but dropped before reaching destination D (e.g., an R1→D collision), a retry with a new `pkt_id` passes through R1 again normally. Application-layer dedup at D (by `Data.request_id`) ensures the message is applied only once.

The 60-second relay duplicate-suppression window applies to individual LoRa packet IDs, not to logical message IDs. Retransmitted packets with new IDs are always forwarded; application dedup at the destination prevents duplicate processing.

**Comparison to MeshCore:**

MeshCore's ACK travels a known routed path (router backbone) and is therefore guaranteed to reach the originator if any path exists. MASH's ACK is flooded; delivery is probabilistic — it relies on the flooding mesh reaching the sender. The key advantage of MASH's approach is zero routing state and full Meshtastic wire-format compatibility. For the MASH use case (100-player field, 1–2 minute accuracy requirement), probabilistic but high-confidence delivery of game state is sufficient. If three retries over a 5-second window fail, it is very likely the sender is genuinely unreachable, not a protocol artifact.

---

## 9. Firmware Architecture

### 9.1 Overview

The firmware is written in C++17, built with PlatformIO, and targets the Arduino framework for nRF52840 using the Adafruit nRF52 Arduino BSP. This BSP choice is consistent with the upstream Meshtastic firmware and gives immediate access to a proven hardware abstraction layer for the T-Echo Plus.

The Meshtastic firmware source is used as an upstream reference. The following components are adopted with minor or no modification:

| Component | Source | Treatment |
|-----------|--------|-----------|
| SX1262 LoRa driver | RadioLib | Adopted directly via library dependency |
| GPS NMEA parser | TinyGPS++ | Adopted with MASH-specific sentence configuration |
| E-Ink driver (SSD1681) | Meshtastic HAL | Adopted and extended for MASH screen layouts |
| AES-128-CTR crypto | Meshtastic / mbedTLS | Adopted; key derivation kept identical for channel 0 |

The following components are replaced entirely:

| Component | Reason |
|-----------|--------|
| Configuration system | Meshtastic's requires reboot for almost all changes; replaced with hot-reload engine |
| BLE GATT layer | Meshtastic's BLE framing has MTU-handling issues; replaced with clean framed protocol |
| Mesh router | Refactored for multi-channel correctness; algorithm kept compatible |
| App / plugin layer | Meshtastic's module system is not used; replaced with direct MASH packet handlers |

### 9.2 Module Structure

```
firmware/src/
├── main.cpp                        Entry point; FreeRTOS scheduler init
├── hal/
│   ├── Radio.h / Radio.cpp         SX1262 wrapper over RadioLib
│   ├── GPS.h / GPS.cpp             L76K UART driver, NMEA parse, fix state
│   ├── Display.h / Display.cpp     SSD1681 driver, screen layout engine
│   ├── Battery.h / Battery.cpp     ADC read, charge state, fuel gauge
│   └── Button.h / Button.cpp       Debounced user button, gesture detection
├── mesh/
│   ├── MeshNode.h / MeshNode.cpp   Node identity, NODEINFO broadcast
│   ├── Router.h / Router.cpp       Flood router, SNR relay, duplicate cache
│   ├── ChannelManager.h / ...      Channel slot table, hash dispatch
│   ├── PacketPool.h / ...          Fixed-size preallocated packet buffers
│   ├── Crypto.h / Crypto.cpp       AES-128-CTR encrypt/decrypt
│   └── Portnums.h                  Portnum enum (Meshtastic + MASH)
├── config/
│   ├── ConfigManager.h / ...       Hot-reload engine, change queue, apply logic
│   ├── ConfigGroups.h              Config group enum and metadata
│   ├── FlashStore.h / ...          LittleFS read/write, migration
│   └── Defaults.h                  Factory default values for all groups
├── ble/
│   ├── BLEServer.h / ...           GATT server lifecycle, bonding
│   ├── MashService.h / ...         MASH GATT service characteristics
│   ├── DFUService.h / ...          Nordic DFU service (OTA update)
│   └── Framing.h / ...             Length-prefixed chunked BLE framing
├── game/
│   ├── GameState.h / ...           Local mirror of game phase, zone table
│   └── PlayerState.h / ...         Own player state, respawn timer
└── ui/
    ├── Screens.h                   Screen ID enum, layout constants
    ├── MainScreen.cpp              Status: GPS, radio, battery, node count
    ├── MessageScreen.cpp           Last 3 received messages
    └── PlayerScreen.cpp            Team name, player count, own status
```

### 9.3 Task Structure

The firmware runs under FreeRTOS (provided by the Adafruit nRF52 core). No shared mutable state is accessed outside a mutex; inter-task communication uses FreeRTOS queues and event groups exclusively.

| Task | Priority | Stack | Trigger |
|------|----------|-------|---------|
| Radio RX/TX | HIGH (4) | 4 KB | SX1262 DIO1 interrupt |
| GPS Parser | NORMAL (3) | 2 KB | UART data available |
| BLE Server | NORMAL (3) | 4 KB | BLE stack events |
| Config Apply | LOW (2) | 2 KB | Config change queued |
| Audio Codec | LOW (2) | 3 KB | BLE audio frame received or TX queue non-empty (Phase 7) |
| Display Refresh | LOW (1) | 2 KB | 5-minute timer or content change |
| Battery Monitor | IDLE (0) | 1 KB | 60-second timer |

### 9.4 Configuration System

#### 9.4.1 Config Groups

Configuration is organized into groups. Each group is versioned independently, can be updated independently over BLE, and applies independently without affecting other groups.

| Group | Contents | Requires Radio Restart? |
|-------|----------|------------------------|
| `DEVICE` | Node long name, short name | No — broadcasts updated NODEINFO immediately |
| `POSITION` | GPS precision bits, broadcast interval, min TX interval, movement threshold, per-type hop limits | No |
| `CHANNELS` | Channel slot definitions, PSKs, roles, per-slot PHY profile index | Yes — PSK re-keying or PHY profile change requires radio restart |
| `LORA` | Region, TX power, PHY profile table (up to 5 entries of SF/BW/CR + name) | Yes — PHY reconfiguration requires radio restart |
| `POWER` | GPS duty cycle, display sleep timeout | No |
| `GAME` | Callsign, team ID, player color, admin flag | No |
| `SECURITY` | Max bonded BLE devices | No |
| `AUDIO` | Audio channel slot index, Codec2 mode, PTT enabled (Phase 7) | No |

#### 9.4.2 Radio Restart (Not Device Reboot)

A radio restart is a controlled re-initialization of the SX1262 and channel crypto contexts. It does not restart the nRF52840 MCU, does not lose the BLE connection, and does not reset any other firmware state. Target duration: ≤ 300 ms.

Procedure:
1. Signal Radio RX/TX task to pause via event group.
2. Drain the TX queue (in-flight packets are dropped; the BLE layer is notified).
3. Call `Radio::reinit(newLoraConfig)` — issues SX1262 hardware reset, applies new register values.
4. Call `ChannelManager::rekey(newChannelConfig)` — recomputes channel hashes and AES key schedules.
5. Signal Radio RX/TX task to resume.

No step in this procedure requires an MCU reboot. The phrase "requires a reboot" is explicitly abolished from the MASH user-facing vocabulary.

#### 9.4.3 Pending Changes & Grouped Apply

The BLE config protocol supports staging multiple config group changes before committing them. The phone sends each group change with `commit = false` (staged) and then sends a final message with `commit = true` (apply all staged). This allows the Config Apply task to execute at most one radio restart regardless of how many groups were changed, minimizing the apply window.

#### 9.4.4 Config Persistence

Config groups are stored in LittleFS on the SPI NOR flash. Each group is a separate file (`/config/device.pb`, `/config/lora.pb`, etc.) encoded as protobuf binary. LittleFS provides wear leveling and power-loss atomicity. On first boot (no config files present), all groups load from `Defaults.h`.

### 9.5 GPS Management

The L76K is initialized via UART at 9600 baud, then upgraded to 115200 baud after the first valid NMEA sentence is received. The firmware sends standard PMTK configuration commands to enable only the sentences MASH requires (GNGGA, GNRMC) and disable all others, minimizing UART load.

GPS behaviors:

- **Fix quality**: HDOP and satellite count are tracked. Positions with HDOP > 5.0 are flagged as low-quality and their precision bits are reduced before transmission.
- **Smart broadcast**: A POSITION_APP packet is sent when GPS displacement ≥ 10 m since last TX AND at least 15 s have elapsed, OR 90 s have elapsed regardless of movement (heartbeat). See §8.7 Smart Beaconing for full policy. The `position.broadcast_interval_secs` config field sets the heartbeat maximum (default: 90 s).
- **Duty cycle**: When `power.gps_duty_cycle` is enabled, GPS is powered off between fix updates. The on/off intervals are configurable.
- **PPS**: The L76K PPS output is connected to a GPIO interrupt to enable future precise timing; it is not used in the initial implementation but the pin is reserved.

### 9.6 Display Management

The SSD1681 e-ink display is refreshed only when content changes, subject to a minimum 60-second interval between full refreshes to prevent damage from excessive refresh cycles. The driver tracks dirty state per screen element.

Screen rotation is triggered by a short button press. Screens cycle:

1. **Main** — node name, GPS coordinates, satellite count, battery percentage, mesh node count, time since last packet.
2. **Messages** — last 3 received text messages (channel, sender short name, truncated text).
3. **Players** — team name, own status badge, active player count.
4. **Radio** — current channel name, last packet RSSI and SNR, hop count.

A long press (≥ 3 s) while status is ACTIVE triggers the HIT state. A short press while status is HIT and within 3 seconds of reporting cancels the HIT before it propagates (accidental-hit protection). See §12.1 for full state machine.

### 9.7 BLE Architecture

The nRF52840 SoftDevice S140 BLE stack is used, accessed via the Adafruit nRF52 Arduino core wrappers. The GATT server starts at boot and remains active throughout device operation.

Security: All write-capable characteristics require an authenticated (bonded) connection. The `DEVICE_STATUS` read characteristic is accessible to unauthenticated connections so that any BLE scanner can identify the device before pairing.

Nordic DFU service is registered alongside the MASH service, enabling OTA firmware updates via Nordic's standard DFU protocol (nRF Connect app or custom app integration).

---

## 10. BLE Interface Specification

### 10.1 MASH GATT Service

**Service UUID**: `4d415348-0000-1000-8000-00805f9b34fb`
(Mnemonic: `4d415348` = ASCII "MASH")

| Characteristic | UUID Suffix | Properties | Security | Description |
|----------------|-------------|------------|----------|-------------|
| `CONFIG_RW` | `…0001` | Read, Write, Notify | Authenticated | Config group read/write |
| `MESH_TX` | `…0002` | Write Without Response | Authenticated | Send packet to mesh |
| `MESH_RX` | `…0003` | Notify | Authenticated | Receive packet from mesh |
| `DEVICE_STATUS` | `…0004` | Read, Notify | Open | Battery, GPS, radio status |
| `ADMIN` | `…0005` | Write, Notify | Authenticated | Admin commands and responses |

### 10.2 BLE Framing Protocol

All characteristics use a length-prefixed chunked framing protocol to support payloads larger than the negotiated BLE ATT MTU.

Frame header (6 bytes):
```
Offset  Size  Field
0       2     total_length  (uint16_le) — total byte count of reassembled payload
2       2     chunk_index   (uint16_le) — 0-based index of this chunk
4       2     chunk_count   (uint16_le) — total number of chunks
6       N     chunk_data
```

The phone negotiates MTU to 517 bytes (Bluetooth 5.0 maximum). Payloads larger than `MTU − 6 − 3` (ATT overhead) bytes are split into multiple chunks. The receiver reassembles all chunks before dispatching. Single-chunk payloads use `chunk_index=0, chunk_count=1`.

### 10.3 Config Protocol

A config read/write cycle over `CONFIG_RW`:

**Write (phone → device):**
```protobuf
message ConfigPayload {
  ConfigGroup group  = 1;
  bytes       data   = 2;   // serialized config group proto
  bool        commit = 3;   // false = stage only; true = apply all staged
}
```

**Notify (device → phone after apply):**
```protobuf
message ConfigAck {
  ConfigGroup group            = 1;
  bool        success          = 2;
  string      error            = 3;   // present only when !success
  bool        radio_restarted  = 4;
  uint32      apply_duration_ms = 5;
}
```

**Read**: The phone may read `CONFIG_RW` to request the current value of a config group by first writing a `ConfigPayload` with an empty `data` field, then reading the next notification.

### 10.4 Device Status Packet

Transmitted on `DEVICE_STATUS` at connect and on any field change (debounced to 1 Hz maximum):

```protobuf
message DeviceStatus {
  uint32    node_id             = 1;
  string    node_name           = 2;
  float     battery_level       = 3;   // 0.0–1.0
  bool      charging            = 4;
  GpsStatus gps                 = 5;
  RadioStatus radio             = 6;
  uint32    mesh_node_count     = 7;
  uint32    uptime_seconds      = 8;
  string    firmware_version    = 9;
  string    bluetooth_address   = 10;  // formatted as "AA:BB:CC:DD:EE:FF"
}

message GpsStatus {
  bool   fix         = 1;
  uint32 satellites  = 2;
  double latitude    = 3;
  double longitude   = 4;
  float  altitude_m  = 5;
  float  accuracy_m  = 6;
}

message RadioStatus {
  string channel_name  = 1;
  float  last_rssi     = 2;
  float  last_snr      = 3;
  uint32 packets_rx    = 4;
  uint32 packets_tx    = 5;
}
```

### 10.5 Admin Commands

```protobuf
enum AdminCommand {
  REBOOT         = 0;
  FACTORY_RESET  = 1;
  DFU_ENTER      = 2;
  RADIO_RESTART  = 3;
  GPS_RESET      = 4;
  PAIR_CLEAR     = 5;
}

message AdminRequest {
  AdminCommand command       = 1;
  string       confirm_token = 2;
  // confirm_token = HMAC-SHA256(device_node_id_bytes, command_name_string)
  // Required for FACTORY_RESET and PAIR_CLEAR; optional for others.
}

message AdminResponse {
  AdminCommand command = 1;
  bool         success = 2;
  string       error   = 3;
}
```

`FACTORY_RESET` and `PAIR_CLEAR` require a valid `confirm_token`. The Android app computes the token from the device's node ID (obtained from `DEVICE_STATUS`) and presents the user with a confirmation dialog before transmitting.

### 10.6 BLE Pairing Policy

- **Pairing method**: LE Secure Connections with Numeric Comparison. The device displays a 6-digit code on the e-ink screen; the phone user confirms the same code. This provides MITM protection.
- **Bond storage**: Up to 4 bonded devices (configurable in `SecurityConfig`, default 1). Stored in SoftDevice bond database on internal flash.
- **Reconnect**: Bonded phone reconnects without user interaction; device auto-accepts based on stored LTK.
- **Bond management**: `PAIR_CLEAR` admin command removes all bonds. Individual bond removal is not supported in the initial implementation.

---

## 11. Android Application

### 11.1 Technology Stack

| Concern | Choice | Version |
|---------|--------|---------|
| Language | Kotlin | ≥ 2.0 |
| UI | Jetpack Compose + Material 3 | BOM ≥ 2025.01 |
| Architecture | Clean Architecture, MVVM | — |
| DI | Hilt | ≥ 2.51 |
| DB | Room | ≥ 2.7 |
| Serialization | protobuf-kotlin | ≥ 4.26 |
| BLE | Android system BLE API, custom coroutine wrapper | — |
| Maps | MapLibre GL Android SDK | ≥ 11.0 |
| HTTP (tile download) | Ktor client | ≥ 2.3 |
| Unit testing | JUnit 5, MockK | — |
| Instrumented testing | Espresso, Compose test rules | — |

### 11.2 Permissions

The following permissions are declared in the manifest. No other permissions are declared.

| Permission | Reason | Revokable |
|------------|--------|-----------|
| `BLUETOOTH_SCAN` | Discover nearby MASH devices | No |
| `BLUETOOTH_CONNECT` | Connect to and communicate with a MASH device | No |
| `ACCESS_FINE_LOCATION` | Required by Android 12+ for BLE scan; used for map centering | No |
| `ACCESS_BACKGROUND_LOCATION` | Continue position updates when app is backgrounded | Yes — user opt-in, explained in-context |
| `FOREGROUND_SERVICE` | Maintain BLE connection when app is not in foreground | No |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service type for BLE on Android 14+ | No |
| `POST_NOTIFICATIONS` | In-game event and message notifications | Yes |
| `RECORD_AUDIO` | Phone microphone for PTT audio transmission (Phase 7 only) | Yes — requested only when user explicitly enables PTT audio in Settings |

The app MUST NOT request or declare: CAMERA, READ_CONTACTS, READ_PHONE_STATE, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, READ_MEDIA_*, or any other permission not listed above. `RECORD_AUDIO` is included in the manifest with `android:required="false"` only when Phase 7 PTT audio is present in the build, and is requested at runtime only at the moment the user first enables PTT audio, with an in-context rationale string. The app MUST NOT request `RECORD_AUDIO` on first launch or at any time before the user enables PTT audio.

All map tile storage uses the app's internal storage directory (`context.filesDir`), which requires no storage permission.

### 11.3 Application Architecture

```
UI Layer (Compose + ViewModel)
    ↕ StateFlow / SharedFlow
Domain Layer (Use Cases)
    ↕ Repository interfaces
Data Layer
  ├── BleRepository        BLE transport, framing, reconnect logic
  ├── MeshRepository       Received packet store, node table
  ├── GameRepository       Game state, player state, zones, objectives
  ├── ConfigRepository     Device config read/write via BLE
  └── TileRepository       MapLibre tile cache management
```

All repository implementations are injected via Hilt. ViewModels observe domain-layer StateFlows. Compose UI renders the emitted state.

### 11.4 Navigation Structure

Bottom navigation bar with 5 top-level destinations. No nested bottom navigation; sub-screens use full-screen transitions or sheets.

| Tab | Route | Description |
|-----|-------|-------------|
| Map | `map` | Situational awareness map (default at launch) |
| Messages | `messages` | Channel-tabbed message list |
| Players | `players` | Team roster with status |
| Game | `game` | Game phase control and scoring |
| Settings | `settings` | Device config and app preferences |

### 11.5 Map Screen

The primary screen, shown at app launch.

**Features:**
- MapLibre GL rendering with offline vector tile support.
- Own position: blue directional arrow (bearing from device compass, if available, else heading from GPS track).
- Remote nodes: colored circular pin with short-name label; color reflects team assignment from MASH_PLAYER data.
- Node status overlay: active = solid pin; hit = hollow pin; eliminated = pin with X; no recent position = grayed out.
- Tap a pin: bottom sheet with player details (callsign, team, battery %, status, last-seen time).
- Long-press map: place a local waypoint (label + icon; stored in Room DB; not broadcast).
- Zone polygons: rendered as semi-transparent fills with colored borders per zone type (see §12.3 for zone type colors).
- Objective icons: flag icon at zone centroid with capture-bar overlay.
- Toolbar: recenter on own position, toggle layer visibility (zones, objectives, players, waypoints), open tile manager.

**Tile management:**
- The app is distributed with a bundled world overview tileset at zoom levels 0–5.
- Users download field area tiles in-app by drawing a bounding box on the overview map and selecting zoom range 6–17.
- Tile download uses configurable tile server URL (default: OpenStreetMap raster tiles); downloaded tiles are stored as MBTiles in app-internal storage.
- No tile server URL is hardcoded into the codebase. Users configure the URL in Settings.

### 11.6 Messages Screen

- Channel selector: horizontal tab row (tab per active channel slot; default tabs "Mesh" and "Game").
- Message list: reverse-chronological, grouped by sender, with timestamp and sender short name.
- Compose field: shows remaining byte count (LoRa maximum payload is 237 bytes after headers; longer messages are rejected before send with a user-visible error).
- Message persistence: Room DB, rolling window of 1 000 messages per channel. Oldest messages are deleted automatically.
- Messages received while the screen is in the background generate a POST_NOTIFICATIONS notification (if permission granted).

### 11.7 Players Screen

- Section headers by team name (team color as left border accent).
- Per player row: callsign, relative last-seen time, battery level indicator, status chip (ACTIVE / HIT / RESPAWNING / ELIMINATED / OFFLINE).
- OFFLINE = no MASH_PLAYER packet received in the last 5 minutes.
- Filter toggle: Show All / Online Only.
- Admin accounts (see §11.8) see additional controls per row: reassign team (dropdown), force status change (button).

### 11.8 Game Screen

All users see the full game screen. Admin-specific controls are conditionally shown only when `GameConfig.is_admin = true` on the connected device.

**All users:**
- Own status badge (large, prominent — ACTIVE / HIT / RESPAWNING / ELIMINATED).
- **HIT button**: large red button; triggers HIT state on own device and broadcasts MASH_PLAYER packet. Requires confirmation hold (500 ms) to prevent accidental activation.
- **Cancel hit**: visible for 3 seconds after a HIT is reported; cancels the report if tapped before propagation window closes.
- Respawn countdown timer when status = RESPAWNING.
- Game phase indicator (LOBBY / ACTIVE / PAUSED / ENDED).
- Score summary: team scores, objective count, active player counts.

**Admin only:**
- Game phase controls: Start / Pause / Resume / Stop (with confirmation dialog for Stop).
- Round timer: set duration, visible countdown.
- Zone editor: button to enter zone-draw mode on the map (draws polygon → MASH_ZONE broadcast).
- Player override panel: force any player's status, reassign teams.
- Force respawn all.
- Reset scores.

### 11.9 Settings Screen

Settings are organized by config group. All changes are staged; an **Apply** button at the bottom of each section commits changes. Sections that will trigger a radio restart show an amber indicator: "⚠ Applying this change will briefly pause radio reception (~300 ms)."

| Section | Contents |
|---------|----------|
| Device | Node name (max 39 chars), short name (max 4 chars) |
| LoRa | Region (dropdown), TX power (slider 2–22 dBm), PHY profile editor (up to 5 profiles; each shows SF / BW / CR as raw values with a plain-language range/speed label; Meshtastic preset name shown in parentheses for cross-reference) |
| Channels | Channel slot list: tap slot to edit name, PSK (password field), role, position precision, PHY profile (picker shows profile name + SF/BW/CR; compatibility indicator flags mismatch with known nearby nodes on that slot) |
| Position | Broadcast interval (slider), precision bits (slider), smart broadcast toggle, movement threshold |
| Power | GPS duty cycle toggle, GPS on/off durations, display sleep timeout |
| Game | Callsign, team ID, player color, admin mode toggle |
| App | Theme (light / dark / system), default map zoom, tile server URL, notification prefs |
| About | Firmware version, node ID (hex), Bluetooth address, diagnostic log export, factory reset |

The PSK field in channel config is shown as a password field (obscured) with a reveal toggle. The field accepts raw text (UTF-8, max 32 bytes) or a hex string prefixed with `0x`. The app validates length before sending.

### 11.10 BLE Connection Lifecycle

The app maintains a persistent BLE connection to one MASH device at a time (single-device model).

State machine:

```
DISCONNECTED
    │ launch / scan for bonded device
    ▼
SCANNING  ──(no bonded device found within 10 s)──► PAIR_FLOW
    │ bonded device found
    ▼
CONNECTING
    │ connected + services discovered
    ▼
CONNECTED  ──(link loss)──► RECONNECTING ──(backoff: 1 s, 2 s, 4 s … 60 s)──► CONNECTING
```

- A `FOREGROUND_SERVICE_CONNECTED_DEVICE` foreground service maintains the BLE connection when the app is backgrounded.
- The persistent notification reads "MASH — Connected to [node name]" with a disconnect action.
- Reconnect backoff resets to 1 s on any successful connection.

### 11.11 Game Admin Authorization

Admin status is stored in `GameConfig.is_admin` on the device, not on the server (there is no server). A device acting as game admin broadcasts MASH_GAME, MASH_ZONE, MASH_OBJECTIVE, and MASH_ADMIN packets. Other devices accept game-state packets from any source (trust is implicit within the PSK-encrypted game channel). The admin role is therefore enforced by convention within a game group and by the PSK, not by cryptographic authorization. This is an accepted limitation for the airsoft use case; see §15.4.

---

## 12. Airsoft Feature Set

### 12.1 Player State Machine

```
               ┌──────────────────────────────────┐
               │            ACTIVE                │◄──────┐
               └──────────────────┬───────────────┘       │
                          HIT button / long press          │ respawn timer expires
                                  │                         │ (auto)
               ┌──────────────────▼───────────────┐       │
               │             HIT                  │       │
               └──────────────────┬───────────────┘       │
                      (if respawns remaining)              │
               ┌──────────────────▼───────────────┐       │
               │          RESPAWNING              ├───────┘
               └──────────────────┬───────────────┘
                    (if no respawns remaining)
               ┌──────────────────▼───────────────┐
               │          ELIMINATED              │
               └──────────────────────────────────┘
                           ▲
                  Admin force-eliminate
```

- State is stored on the device and broadcast as a MASH_PLAYER packet on every transition.
- Respawn count and timer duration are set by the game admin via a MASH_GAME packet at game start.
- Admin can force any state transition via MASH_ADMIN packet.
- On game STOP, all player states reset to ACTIVE.

### 12.2 Teams

- Up to 8 teams per game.
- Configurable by game admin via MASH_GAME packet: name (max 16 chars), color (24-bit RGB), max player count.
- Team assignment is stored in `GameConfig.team_id` on each device. The player sets their own team in Settings > Game before the game. Admin can override any player's team via MASH_ADMIN.
- Team 0 is reserved for SPECTATOR role (no hit/respawn mechanics, not included in scoring).

### 12.3 Game Zones

Zones are polygonal geographic boundaries defined by the game admin and broadcast as MASH_ZONE packets to all nodes. Zones are rendered on the map as filled polygons with per-type styling:

| Zone Type | Fill Color | Border | Description |
|-----------|-----------|--------|-------------|
| BOUNDARY | transparent | red dashed | Hard outer field boundary |
| SAFE_ZONE | team color, 30% opacity | team color solid | Respawn area for a team |
| OBJECTIVE | yellow, 40% opacity | yellow solid | Capturable point |
| EXCLUSION | dark grey, 50% opacity | grey dashed | Out-of-bounds area |

Players whose GPS position falls outside the BOUNDARY zone trigger a visual warning in the Android app (amber banner). No automatic enforcement is applied; enforcement is by game organizer.

Zones are stored by ID. A MASH_ZONE packet with the same `zone_id` as an existing zone replaces it. A MASH_ZONE packet with `polygon` empty deletes the zone. The full zone table is re-broadcast by the admin at game start.

### 12.4 Objectives

Each objective references a zone and carries:
- `objective_id`: unique within the game
- `zone_id`: the OBJECTIVE zone it occupies
- `controlling_team_id`: team currently in control (0 = NEUTRAL)
- `capture_percent`: 0–100, displayed as a progress arc on the map icon
- `score_value`: points awarded per unit time while held

Objective state is administered exclusively by the game admin device. The admin app increments `capture_percent` based on proximity data it aggregates from received MASH_PLAYER (position) packets.

### 12.5 Game Modes

Three built-in modes. Mode is set in the MASH_GAME packet at game start and determines the scoring display and win-condition banner.

| Mode | Win Condition |
|------|--------------|
| ELIMINATION | Last team with at least one ACTIVE player wins |
| TIME_LIMIT | Team with most ACTIVE players when timer expires wins |
| DOMINATION | Team controlling most objectives when timer expires wins; objective time-held is weighted by score_value |

### 12.6 Device Button Behavior Summary

| Gesture | State | Action |
|---------|-------|--------|
| Short press | any | Advance to next display screen |
| Long press (≥ 3 s) | ACTIVE | Report HIT; transition to HIT state |
| Short press within 3 s of HIT report | HIT | Cancel HIT report (if not yet propagated) |
| Double press | any | Toggle display backlight (not applicable for e-ink; reserved for future hardware) |

### 12.7 PTT Audio (Phase 7)

PTT audio enables half-duplex voice communication across the MASH mesh, routed through the phone as the audio input/output device. The T-Echo Plus has no onboard microphone or speaker; all audio I/O is handled by the Android device over BLE.

**Audio pipeline:**

```
Phone mic (AudioRecord, 8 kHz PCM)
  -> Codec2 encode (1300 bps, 20 ms frames)
  -> BLE MESH_TX to T-Echo Plus
  -> LoRa TX on audio channel slot (Profile 1: Short/Speed, SF7/BW250)

LoRa RX on receiving devices
  -> BLE MESH_RX to Android app
  -> Codec2 decode
  -> Phone speaker (AudioTrack, 8 kHz PCM)
```

**Design decisions:**

- **Codec and packet size**: Codec2 at 1300 bps. Each 20 ms frame is 26 bits (~3.25 bytes). MASH packs **8 Codec2 frames (160 ms of audio) per LoRa packet** at ~26 bytes of raw audio payload. The total LoRa frame is larger: the wire header (16 bytes), protobuf Data wrapper, and AudioPacket fields (from_node_id, sequence_num, session_id, codec2_mode_bps, frame_count, end_of_transmission) add ~22 bytes of overhead before the audio data. With the `fec_prev_audio_data` FEC field (another ~26 bytes), the total LoRa payload is approximately **92 bytes**, giving **~84 ms on air** on Profile 1 (SF7/BW250/CR4/5, 16-symbol preamble). Without FEC the payload is ~66 bytes and ToA ~59 ms. The ~84 ms figure should be used for all duty-cycle and airtime planning when FEC is enabled (the default).
- **Reliability — FEC piggyback**: Each AudioPacket carries a `fec_prev_audio_data` field containing the Codec2 bytes of the **immediately preceding packet within the same PTT session**. If a receiver detects a gap in `sequence_num` (exactly one packet lost), it reconstructs the missing audio from the FEC field of the next received packet. This recovers single-packet-loss events — the most common failure mode on a congested LoRa channel — without any retransmit request. **Session boundary rule**: on the **first packet of each PTT session** (i.e., the first packet after no previous transmission or after a packet with `end_of_transmission = true`), the sender MUST set `fec_prev_audio_data` to an empty bytes field. A receiver that sees `fec_prev_audio_data` empty on the first packet of a new session MUST NOT attempt FEC recovery for sequence gaps spanning the session boundary; it MUST instead apply PLC or silence for any gap. This prevents corrupted audio output when `end_of_transmission` from the previous session was lost.
- **Reliability — jitter buffer**: The Android receiver maintains a 3-packet jitter buffer (480 ms at 8 frames/packet). Packets arriving out of order within the buffer window are resequenced before decode. Packets arriving more than 480 ms late are discarded.
- **Reliability — packet loss concealment (PLC)**: If a packet is lost and FEC recovery is not possible (two or more consecutive losses), the Codec2 decoder runs in PLC mode, synthesising comfort noise for the missing interval rather than producing a click or silence gap.
- **EU duty-cycle compliance (audio)**: At 8 frames/packet (160 ms interval), each AudioPacket with FEC has **~84 ms ToA** on Profile 1 (SF7/BW250). At 160 ms packet interval, this is **~52 % duty cycle** — far above any EU limit. EU sub-band g1 (10 % limit): each operator MUST configure `frame_count ≥ 42` (42 × 20 ms = 840 ms interval; DC = 84/840 = 10 %). EU sub-band g (1 % limit): PTT audio at 1300 bps **is not feasible** on EU 868 MHz at any frame_count — reaching 1 % DC would require frame_count ≥ 420, making real-time voice impossible; operators on sub-band g MUST use 915 MHz. The AudioConfig screen in the Android app displays the calculated per-device duty cycle (using the correct ToA formula with FEC overhead) and highlights it in amber when frame_count < 42 on EU868 (sub-band g1 non-compliant) and in red always for sub-band g at 868 MHz. Compliance is the operator's responsibility; the app provides guidance only.
- **Direct range only**: `hop_limit = 1`. Relaying audio would add ≥ 13 ms per hop minimum latency and introduce re-ordering; combined with the jitter buffer this degrades quality significantly. All PTT participants must be within single-hop range on the Short/Speed channel slot. On a 10 km field, fixed relay nodes on elevated positions are the solution for range extension — not multi-hop audio.
- **PHY requirement**: Audio requires a channel slot configured with Profile 1 (Short/Speed). The Android app shows a warning if the user attempts to enable PTT while the audio slot uses Profile 0. **The firmware MUST also enforce this**: before enabling PTT transmission, the firmware checks that the audio channel slot's `phy_profile_index` references a profile with `spreading_factor ≤ 8`. If the check fails, the firmware sends an error notification on the ADMIN BLE characteristic (`error = "audio_slot_phy_too_slow"`) and refuses to transmit audio. This prevents a Profile 0 misconfiguration from causing the device to attempt a 680 ms ToA transmission on a 160 ms audio cadence.
- **Encoding on Android**: Codec2 is compiled as a native library (C) bundled in the Android app via JNI. `AudioRepository` runs encoding and decoding on a dedicated coroutine dispatcher to avoid audio jitter.
- **Channel isolation**: Audio packets (portnum 263) are sent on the designated audio channel slot with its own PSK. Non-audio channel slots are unaffected. Meshtastic nodes relay audio packets if they can decrypt the channel slot, but do not process them (unknown portnum).
- **PTT UI in the Android app**: A persistent PTT button appears in the bottom navigation bar when PTT audio is enabled in Settings. Hold to transmit, release to stop. The button shows "TRANSMITTING" in red while active and "LISTENING" otherwise. Simultaneous PTT from multiple users is not prevented at the protocol level; users hear both transmissions overlaid (standard PTT behaviour).
- **Android permission**: `RECORD_AUDIO` is requested the first time the user holds the PTT button, with a rationale: "MASH needs microphone access to transmit voice over the mesh." If permission is denied, the PTT button is disabled with an explanation. No other feature is affected.

---

## 13. Meshtastic Interoperability

### 13.1 Guaranteed Interop Behaviors

When a MASH device shares channel 0 PHY parameters and PSK with a Meshtastic device:

| Capability | Works? | Notes |
|------------|--------|-------|
| MASH receives text messages from Meshtastic | ✓ | Standard TEXT_MESSAGE_APP portnum |
| MASH sends text messages to Meshtastic | ✓ | Standard TEXT_MESSAGE_APP portnum |
| MASH position visible in Meshtastic app | ✓ | POSITION_APP + NODEINFO_APP broadcast |
| MASH relays Meshtastic packets | ✓ | Flood router is compatible |
| Meshtastic relays MASH packets | ✓ | Unknown portnums are relayed, not dropped |
| Meshtastic app shows MASH as a node | ✓ | NODEINFO_APP with hw_model |
| Meshtastic sees MASH game data (MASH_PLAYER, etc.) | ✗ | Sent on MASH game channel slot (different PSK); relayed but not decryptable by Meshtastic |

### 13.2 NODEINFO Broadcast

At startup and every 3 hours, MASH broadcasts a NODEINFO_APP packet on channel 0:

```
hw_model:     LILYGO_TECHO
long_name:    <DeviceConfig.long_name>
short_name:   <DeviceConfig.short_name>
macaddr:      <lower 6 bytes of nRF52840 FICR device address>
```

This makes MASH nodes identifiable in the Meshtastic app's node list.

### 13.3 Vendored Meshtastic Protobuf Files

The Meshtastic protobuf definitions in `/proto/meshtastic/` are vendored at a specific commit and MUST NOT be auto-updated. The pinned commit SHA is recorded in `/proto/meshtastic/VERSION`. Updating the vendored protos is a deliberate, tested action performed when a specific interop fix is needed, not during routine development.

### 13.4 Infrastructure Relay Nodes (Large Fields)

On fields larger than ≈ 2 km in any dimension, tree canopy and building attenuation can make single-hop coverage unreliable. MASH nodes used as **fixed relays** require no special firmware configuration: any MASH node (or stock Meshtastic node) with the correct channel PSKs and PHY profile will relay all packets automatically. Recommended relay node placement:

- **Elevation**: mount at ≥ 5 m height (tree line, flagpole, building roof) to clear foliage. Each relay node effectively extends mesh radius by ≈ 800 m under forest canopy at Profile 0.
- **Power**: relay nodes typically run on USB power banks; the MASH firmware duty-cycle logic still applies (GPS beaconing at 90 s heartbeat), so a 10 000 mAh power bank runs a relay node for ≈ 40 hours.
- **Count**: for a 10 km × 10 km field with 3–4 player clusters, 4–6 relay nodes at elevated positions provides ≥ 95 % of the field area under moderate forest density with the default hop_limit=7.
- **Audio**: relay nodes do NOT relay `MASH_AUDIO` packets (`hop_limit = 1`). PTT audio is direct-range on the Short/Speed profile. Players expecting voice communication must ensure they are within ≈ 800 m LOS of each other on the audio channel slot.

---

## 14. Data Models & Schemas

### 14.1 Proto File Organization

```
proto/
├── meshtastic/            Vendored Meshtastic protos — DO NOT MODIFY
│   ├── mesh.proto
│   ├── config.proto
│   ├── portnums.proto
│   └── VERSION            Pinned commit SHA
└── mash/
    ├── config.proto       MASH config group messages
    ├── ble.proto          BLE transport messages (ConfigPayload, ConfigAck, DeviceStatus, AdminRequest)
    ├── game.proto         Game state, player state, zones, objectives, audio; portnums 256–263
    └── mesh.proto         Reserved for future MASH over-the-air packet extensions (schema TBD)
```

### 14.2 `mash/config.proto`

```protobuf
syntax = "proto3";
package mash;

enum ConfigGroup {
  DEVICE   = 0;
  LORA     = 1;
  CHANNELS = 2;
  POSITION = 3;
  POWER    = 4;
  GAME     = 5;
  SECURITY = 6;
  AUDIO    = 7;   // Phase 7 addition
}

message DeviceConfig {
  string long_name  = 1;   // max 39 UTF-8 bytes
  string short_name = 2;   // max 4 UTF-8 bytes
}

enum LoraRegion {
  EU_868 = 0;
  US_915 = 1;
  AU_915 = 2;
  CN_470 = 3;
  JP_920 = 4;
  KR_920 = 5;
  IN_865 = 6;
}

// PHY profile: a named set of LoRa modulation parameters.
// All nodes that communicate on a given channel must use identical values.
message PhyProfile {
  string name             = 1;   // user-visible name, max 16 bytes
  uint32 spreading_factor = 2;   // 7-12
  uint32 bandwidth_hz     = 3;   // 125000, 250000, or 500000
  uint32 coding_rate      = 4;   // 5=4/5  6=4/6  7=4/7  8=4/8
}

// Default PhyProfile values (factory defaults at index 0-4 in LoraConfig.profiles):
//   [0] name="Long/Mesh"      SF=11 BW=250000 CR=5  (= Meshtastic LONG_FAST)
//   [1] name="Short/Speed"    SF=7  BW=250000 CR=5  (= Meshtastic SHORT_FAST; audio capable)
//   [2] name="Long/Reliable"  SF=12 BW=125000 CR=8  (= Meshtastic LONG_SLOW)
//   [3] name="Medium/Slow"    SF=10 BW=250000 CR=5  (= Meshtastic SHORT_SLOW)
//   [4] name="User Defined"   -- user must set all fields

message LoraConfig {
  LoraRegion          region       = 1;
  uint32              tx_power_dbm = 2;   // 2-22 inclusive
  repeated PhyProfile profiles     = 3;   // exactly 5 entries; indexed by ChannelSlot.phy_profile_index
}

enum ChannelRole {
  DISABLED  = 0;
  PRIMARY   = 1;
  SECONDARY = 2;
}

message ChannelSlot {
  uint32      index              = 1;
  string      name               = 2;   // max 16 bytes
  bytes       psk                = 3;   // 16 or 32 bytes; empty = no encryption
  ChannelRole role               = 4;
  bool        uplink_enabled     = 5;
  bool        downlink_enabled   = 6;
  uint32      position_precision = 7;   // 0-32 bits of lat/lng precision
  uint32      phy_profile_index  = 8;   // index into LoraConfig.profiles; default 0
}

message AudioConfig {
  uint32 audio_channel_slot = 1;   // which slot carries audio (default 2)
  uint32 codec2_mode_bps    = 2;   // 700, 1300, or 2400; default 1300
  bool   ptt_enabled        = 3;   // master enable for PTT audio feature
  uint32 frame_count        = 4;   // Codec2 frames per LoRa packet; default 8 (160 ms); max 50; affects duty cycle
}

message ChannelConfig {
  repeated ChannelSlot slots = 1;
}

message PositionConfig {
  uint32 broadcast_interval_secs = 1;   // heartbeat max; default 90
  uint32 gps_precision_bits      = 2;   // default 32 (full precision)
  bool   smart_broadcast         = 3;   // default true (movement-triggered; see §8.7)
  uint32 movement_threshold_m    = 4;   // fixed 10 m in Defaults.h; not exposed in UI
  uint32 min_tx_interval_secs    = 5;   // minimum gap between any two TX; default 15
}

message PowerConfig {
  bool   gps_duty_cycle    = 1;
  uint32 gps_on_secs       = 2;
  uint32 gps_off_secs      = 3;
  uint32 display_off_secs  = 4;   // 0 = never off
}

message GameConfig {
  string callsign         = 1;   // max 16 bytes
  uint32 team_id          = 2;   // 0 = spectator
  uint32 player_color_rgb = 3;
  bool   is_admin         = 4;
}

message SecurityConfig {
  uint32 max_bonded_devices = 1;   // 1–4, default 1
}
```

### 14.3 `mash/game.proto`

```protobuf
syntax = "proto3";
package mash;

// ── Player ────────────────────────────────────────────────────

enum PlayerStatus {
  ACTIVE      = 0;
  HIT         = 1;
  RESPAWNING  = 2;
  ELIMINATED  = 3;
  SPECTATING  = 4;
}

// Portnum 256 — sent on channel 1 on every player state transition
// and at least once every broadcast_interval_secs (heartbeat).
// is_state_transition distinguishes a status change (ACKNOWLEDGED) from a
// position-only heartbeat (BEST_EFFORT). See §8.9 for the ACK rule.
message PlayerStatePacket {
  uint32       node_id           = 1;
  string       callsign          = 2;
  uint32       team_id           = 3;
  PlayerStatus status            = 4;
  uint32       respawn_at_unix   = 5;   // epoch seconds; non-zero when RESPAWNING
  uint32       color_rgb         = 6;
  bool         is_state_transition = 7; // true = status changed; send ACKNOWLEDGED (want_response=true)
                                         // false = heartbeat; send BEST_EFFORT (want_response=false)
}

// ── Game ──────────────────────────────────────────────────────

enum GamePhase {
  LOBBY   = 0;
  ACTIVE  = 1;
  PAUSED  = 2;
  ENDED   = 3;
}

enum GameMode {
  ELIMINATION = 0;
  TIME_LIMIT  = 1;
  DOMINATION  = 2;
}

message TeamDefinition {
  uint32 team_id   = 1;
  string name      = 2;
  uint32 color_rgb = 3;
  uint32 max_players       = 4;
  uint32 respawn_count     = 5;   // respawns allowed; 0 = unlimited
  uint32 respawn_secs      = 6;   // respawn timer duration
}

message TeamScore {
  uint32 team_id        = 1;
  uint32 score          = 2;
  uint32 active_players = 3;
}

// Portnum 257 — broadcast by admin on phase change or score update
message GameStatePacket {
  uint32                   admin_node_id    = 1;
  GamePhase                phase            = 2;
  GameMode                 mode             = 3;
  uint32                   started_at_unix  = 4;
  uint32                   ends_at_unix     = 5;   // 0 = no timer
  repeated TeamDefinition  teams            = 6;
  repeated TeamScore       scores           = 7;
}

// ── Zones ─────────────────────────────────────────────────────

enum ZoneType {
  BOUNDARY  = 0;
  SAFE_ZONE = 1;
  OBJECTIVE = 2;
  EXCLUSION = 3;
}

message LatLng {
  double lat = 1;
  double lng = 2;
}

// Portnum 258 — broadcast by admin; empty polygon = delete zone
message ZonePacket {
  uint32          zone_id       = 1;
  ZoneType        type          = 2;
  string          name          = 3;
  repeated LatLng polygon       = 4;
  uint32          owner_team_id = 5;   // relevant for SAFE_ZONE
}

// ── Objectives ────────────────────────────────────────────────

// Portnum 259 — broadcast by admin
message ObjectivePacket {
  uint32 objective_id        = 1;
  uint32 zone_id             = 2;
  uint32 controlling_team_id = 3;   // 0 = NEUTRAL
  uint32 capture_percent     = 4;   // 0–100
  uint32 score_value         = 5;
}

// ── Admin ─────────────────────────────────────────────────────

enum AdminAction {
  FORCE_PLAYER_STATE   = 0;
  FORCE_PLAYER_TEAM    = 1;
  FORCE_RESPAWN_ALL    = 2;
  RESET_SCORES         = 3;
  BROADCAST_ZONES      = 4;   // admin re-broadcasts all zone packets
}

// Portnum 260
message AdminPacket {
  uint32      admin_node_id = 1;
  AdminAction action        = 2;
  uint32      target_node_id = 3;   // for player-specific actions
  uint32      value         = 4;    // PlayerStatus or team_id depending on action
}

// ── Ack ───────────────────────────────────────────────────────

// Portnum 261 — application-layer ack for acknowledged-delivery packets.
// ack_request_id matches Data.request_id of the packet being acknowledged (NOT MeshPacket.id,
// which changes on each retry attempt). hop_limit for this packet is set to the
// hop_start value of the original packet (Flags bits [2:0] of the wire header) so the
// ACK can return over the full mesh diameter. If hop_start is zero (legacy device), use 7.
message MashAck {
  uint32 ack_request_id     = 1;   // = Data.request_id of the acknowledged packet (NOT MeshPacket.id)
  uint32 from_node_id       = 2;
  uint32 original_hop_start = 3;   // hop_start of the packet being ACKed; used by sender to validate
}

// -- Position Request -------------------------------------------------

// Portnum 262 -- unicast request asking target_node_id to immediately re-broadcast
// its current POSITION_APP packet. The receiving node responds within 2 seconds.
// Useful when a node appears on the mesh but its position data is stale.
message PositionRequestPacket {
  uint32 target_node_id    = 1;
  uint32 requester_node_id = 2;
}

// -- Audio (Phase 7) --------------------------------------------------

// Portnum 263 -- PTT audio frame; MUST be sent on the audio channel slot only.
// hop_limit is always 1; this packet MUST NOT be relayed.
// codec2_mode_bps must match the value configured in AudioConfig.
// FEC session rule: fec_prev_audio_data MUST be empty (zero-length) on the first
// packet of each PTT session (after no previous session or after a packet where
// end_of_transmission=true). Receivers MUST NOT attempt FEC recovery for gaps that
// cross a session boundary identified by this empty fec_prev_audio_data marker.
message AudioPacket {
  uint32 from_node_id          = 1;
  uint32 sequence_num          = 2;   // monotonically increasing per PTT session; resets to 0 at session start
  uint32 codec2_mode_bps       = 3;   // 700, 1300, or 2400
  bytes  audio_data            = 4;   // Codec2-encoded frames for this packet (frame_count frames)
  bool   end_of_transmission   = 5;   // set on the final packet of a PTT press
  bytes  fec_prev_audio_data   = 6;   // Codec2 bytes of the PREVIOUS packet within this session;
                                      // MUST be empty on the first packet of each session
  uint32 frame_count           = 7;   // number of Codec2 frames in audio_data; matches AudioConfig.frame_count
  uint32 session_id            = 8;   // increments by 1 each time the PTT button is pressed; wraps at 2^32;
                                      // combined with sequence_num to unambiguously identify session boundaries
                                      // even when end_of_transmission is lost
}
```

### 14.4 `mash/ble.proto`

```protobuf
syntax = "proto3";
package mash;

// See §10.3 for usage
message ConfigPayload {
  ConfigGroup group  = 1;
  bytes       data   = 2;
  bool        commit = 3;
}

message ConfigAck {
  ConfigGroup group             = 1;
  bool        success           = 2;
  string      error             = 3;
  bool        radio_restarted   = 4;
  uint32      apply_duration_ms = 5;
}

// See §10.4
message DeviceStatus {
  uint32      node_id             = 1;
  string      node_name           = 2;
  float       battery_level       = 3;
  bool        charging            = 4;
  GpsStatus   gps                 = 5;
  RadioStatus radio               = 6;
  uint32      mesh_node_count     = 7;
  uint32      uptime_seconds      = 8;
  string      firmware_version    = 9;
  string      bluetooth_address   = 10;  // formatted as "AA:BB:CC:DD:EE:FF"
}

message GpsStatus {
  bool   fix        = 1;
  uint32 satellites = 2;
  double latitude   = 3;
  double longitude  = 4;
  float  altitude_m = 5;
  float  accuracy_m = 6;
}

message RadioStatus {
  string channel_name = 1;
  float  last_rssi    = 2;
  float  last_snr     = 3;
  uint32 packets_rx   = 4;
  uint32 packets_tx   = 5;
}

// See §10.5
enum AdminCommand {
  REBOOT        = 0;
  FACTORY_RESET = 1;
  DFU_ENTER     = 2;
  RADIO_RESTART = 3;
  GPS_RESET     = 4;
  PAIR_CLEAR    = 5;
}

message AdminRequest {
  AdminCommand command       = 1;
  string       confirm_token = 2;
}

message AdminResponse {
  AdminCommand command = 1;
  bool         success = 2;
  string       error   = 3;
}
```

---

## 15. Security Model

### 15.1 Channel Encryption

- **Algorithm**: AES-128-CTR.
- **Key material**: The 128-bit key is derived as `SHA256(psk_bytes)[0:16]` — first 16 bytes of SHA-256 of the raw PSK.
- **For channel 0 specifically**: The key derivation and CTR nonce construction MUST match Meshtastic's exact implementation byte-for-byte. This is validated in the interop test suite (§19.1). The Meshtastic source at the pinned commit in `/proto/meshtastic/VERSION` is the authoritative reference.
- **Nonce**: AES-CTR initial counter value is constructed as `packet_id (32 bits) || from_node_id (32 bits) || 0x00...` (zero-padded to 128 bits), matching Meshtastic.
- **No message authentication code**: AES-CTR provides confidentiality but not integrity. Forged packets from a node that knows the PSK cannot be detected at the crypto layer. This is inherited from Meshtastic and accepted for the airsoft threat model.

### 15.2 BLE Security

- **Pairing**: LE Secure Connections, Numeric Comparison. Provides MITM protection during initial pairing.
- **Bonding**: Long-term keys stored in SoftDevice bond database.
- **Characteristic access control**: All write characteristics (`CONFIG_RW`, `MESH_TX`, `ADMIN`) require security mode 1, level 3 (authenticated encrypted link). `DEVICE_STATUS` read is security mode 1, level 1 (no authentication required).
- **Destructive operation guard**: `FACTORY_RESET` and `PAIR_CLEAR` require `AdminRequest.confirm_token = HMAC-SHA256(key=device_node_id_bytes, data=command_name_utf8)`. The HMAC key is the device's 4-byte node ID. This prevents accidental triggering from the UI; it is not a cryptographic access control mechanism against a bonded attacker.

### 15.3 Data at Rest

- **On-device**: Config files (including PSKs) are stored in LittleFS on SPI NOR flash. There is no hardware-level flash encryption on the nRF52840 in the T-Echo Plus configuration. Physical access to the device allows PSK extraction. This is consistent with Meshtastic and is accepted for the threat model.
- **On Android**: BLE bond keys are managed by the Android OS BLE stack in protected storage. App-internal config and message DB are in `context.filesDir`, inaccessible to other apps without root.

### 15.4 Threat Model

The operational threat model is: **participants sharing a field in an airsoft game may attempt to cheat by spoofing or replaying game state packets**. The PSK-encrypted game channel provides basic protection against participants who do not know the PSK. Participants who do know the PSK (all players) can construct arbitrary game packets. Full per-packet cryptographic authentication is not implemented and is not planned. Game integrity is enforced socially by the game admin and field organizers, not technically by MASH.

---

## 16. Development Phases

Phases are sequential. A phase is not considered complete until all items within it are tested per §19.

### Phase 1 — Firmware Foundation

| ID | Item |
|----|------|
| 1.1 | PlatformIO project, Adafruit nRF52 BSP, T-Echo Plus board definition |
| 1.2 | SX1262 bring-up via RadioLib: TX/RX loopback between two devices |
| 1.3 | L76K GPS bring-up: UART init, NMEA parse, cold fix acquisition verified |
| 1.4 | E-Ink display bring-up: static main screen renders |
| 1.5 | BLE GATT server: MASH service registered, `DEVICE_STATUS` readable |
| 1.6 | LittleFS config store: read, write, default load verified |
| 1.7 | FreeRTOS task skeleton: all tasks created, inter-task queues defined |

### Phase 2 — Mesh Stack

| ID | Item |
|----|------|
| 2.1 | AES-128-CTR packet encryption/decryption; interop-verified against Meshtastic channel 0 |
| 2.2 | NODEINFO_APP broadcast; MASH node visible in Meshtastic Android app |
| 2.3 | TEXT_MESSAGE_APP TX and RX; text messages exchanged with Meshtastic device |
| 2.4 | POSITION_APP TX with GPS position; position visible in Meshtastic Android app |
| 2.5 | Flooding router with duplicate-suppression cache |
| 2.6 | SNR-based relay delay (measured, not just compiled) |
| 2.7 | Multi-channel: two channel slots simultaneously active; separate PSKs; packet routing verified |
| 2.8 | Hot-reload config: all config groups apply without MCU reboot; radio restart path verified |
| 2.9 | Per-packet-type hop limits: verify all packet types (TEXT_MESSAGE_APP, POSITION_APP, MASH_PLAYER, MASH_GAME, etc.) use hop_limit=7; verify MASH_AUDIO uses hop_limit=1; verify hop_start in Flags bits [2:0] preserves the original value across relay hops |
| 2.10 | Heard-by suppression: 3-device linear chain test; middle node cancels relay after overhearing relay from end node |
| 2.11 | Application-layer reliability: (a) ACKNOWLEDGED packet delivered over 3-hop chain; verify MASH_ACK hop_limit mirrors original hop_start and reaches sender; (b) mid-path packet drop (jamming R1→D link): verify retry with new packet_id passes through R1 and is received by D; verify single delivery at D; (c) timeout validation: hop_start=3 → timeout=2400 ms; hop_start=7 → timeout=5600 ms |
| 2.12 | MASH_POSITION_REQUEST: unicast request causes target to re-broadcast POSITION_APP within 2 s |

### Phase 3 — Android App Foundation

| ID | Item |
|----|------|
| 3.1 | Android project: Hilt, Room, Compose, Navigation, proto-kotlin configured |
| 3.2 | BLE transport: scan, pair (numeric comparison), connect, chunk framing |
| 3.3 | `DEVICE_STATUS` live display: battery, GPS fix, RSSI update in real time |
| 3.4 | Settings screen: read all 7 config groups from device, edit, apply, verify radio-restart indicator |
| 3.5 | Messages screen: send and receive TEXT_MESSAGE_APP on channel 0 |
| 3.6 | MapLibre integration: own GPS position shown on bundled world overview tiles |
| 3.7 | Background BLE service: connection survives app backgrounding for ≥ 30 minutes |

### Phase 4 — Situational Awareness

| ID | Item |
|----|------|
| 4.1 | Node positions on map: POSITION_APP packets render as colored pins with callsigns |
| 4.2 | MASH_PLAYER TX/RX: team color applied to pins; status overlay renders |
| 4.3 | Players screen: team grouping, status chips, online/offline filter |
| 4.4 | Tile downloader: bounding box UI, download, offline verification (airplane mode) |
| 4.5 | Local waypoints: place, label, persist in Room DB, render on map |

### Phase 5 — Game Mode

| ID | Item |
|----|------|
| 5.1 | Game state engine: phase management, timer, team score tracking |
| 5.2 | MASH_GAME TX/RX: game phase changes propagate to all devices |
| 5.3 | Hit button: device button → HIT state → MASH_PLAYER broadcast |
| 5.4 | Respawn timer: countdown on device display and in app; ACTIVE set on expiry |
| 5.5 | MASH_ZONE TX/RX: zone polygons render on map with correct type styling |
| 5.6 | MASH_OBJECTIVE TX/RX: capture state renders on map |
| 5.7 | Admin controls: start / pause / stop game; force player state; force respawn all |
| 5.8 | All three game modes: win condition detection and result screen |
| 5.9 | Position request: Android app "Request position" tap on player pin triggers MASH_POSITION_REQUEST; stale pin updates within 2 s |

### Phase 6 — Polish & Hardening

| ID | Item |
|----|------|
| 6.1 | Nordic DFU OTA firmware update via Android app |
| 6.2 | GPS duty cycle: verified power saving with acceptable position update rate |
| 6.3 | Battery life validation: ≥ 8 hours under Phase 6 field test conditions |
| 6.4 | Field test: 10+ devices, 2+ teams, 60-minute game, verified all P5 features functional |
| 6.5 | Meshtastic interop field test: stock Meshtastic device on mesh; text exchange and node visibility verified |
| 6.6 | Android background stability: BLE connection held for ≥ 8 hours on 3 different Android devices |
| 6.7 | Fully offline test: airplane mode from app launch to end of game; all features functional |
| 6.8 | Daylight readability review: e-ink display contrast verified at direct sunlight; Android app contrast verified |
| 6.9 | Per-slot PHY field test: two channel slots with different PHY profiles active simultaneously; verify packet isolation between profiles; verify radio restart on profile change |

### Phase 7 — PTT Audio

| ID | Item |
|----|------|
| 7.1 | Codec2 library integration into firmware: encode/decode round-trip on nRF52840 verified; 1300 bps mode; 20 ms frames |
| 7.2 | Short/Speed PHY profile (SF7/BW250/CR4/5) channel slot configured; LoRa loopback between two MASH devices at Short/Speed verified |
| 7.3 | Firmware audio pipeline: BLE MESH_RX audio frame -> Codec2 decode -> Audio task -> BLE MESH_TX to phone |
| 7.4 | Android audio pipeline: AudioRecord (8 kHz PCM) -> Codec2 encode -> BLE MESH_TX -> device; receive path from BLE MESH_RX -> Codec2 decode -> AudioTrack (8 kHz PCM) |
| 7.5 | PTT button in Android app: hold-to-transmit; TRANSMITTING / LISTENING state display; RECORD_AUDIO permission request flow on first use |
| 7.6 | Time-multiplexing validation: Long/Mesh packet loss during a 5-second PTT burst measured <= 15%; application-layer retry recovers ACKNOWLEDGED packets missed during audio window |
| 7.7 | PTT audio field test: voice exchange between 3 MASH devices at typical airsoft field distances (50 m, 150 m); audio intelligible; both mesh channel (Profile 0) and audio channel (Profile 1) active simultaneously |
| 7.8 | FEC single-packet-loss recovery: inject one dropped packet mid-PTT session; verify receiver reconstructs audio from fec_prev_audio_data without audible gap |
| 7.9 | EU duty-cycle display: configure region to EU868; set frame_count=8; verify AudioConfig screen shows calculated duty cycle ~52 % (amber/red, non-compliant for sub-band g1); set frame_count=42; verify calculated DC = 10 % (green for sub-band g1); set frame_count=50; verify green for sub-band g1, still red for sub-band g (1 % limit) |
| 7.10 | FEC session boundary: transmit PTT session 1; verify first packet of session 2 has empty fec_prev_audio_data; inject a gap at the session boundary (drop end_of_transmission); verify receiver uses session_id change to detect new session and applies PLC rather than cross-session FEC |

```
mash/
├── PLANNING.md                          This document — do not modify
├── README.md                            Project overview and quick-start
│
├── proto/
│   ├── meshtastic/                      Vendored Meshtastic protos — do not modify
│   │   ├── mesh.proto
│   │   ├── config.proto
│   │   ├── portnums.proto
│   │   └── VERSION                      Pinned upstream commit SHA
│   └── mash/
│       ├── config.proto
│       ├── ble.proto
│       ├── game.proto
│       └── mesh.proto
│
├── firmware/
│   ├── platformio.ini                   PlatformIO project configuration
│   ├── boards/
│   │   └── t_echo_plus.json             Board definition (verified pin map)
│   ├── src/
│   │   ├── main.cpp
│   │   ├── hal/
│   │   │   ├── Radio.h / Radio.cpp
│   │   │   ├── GPS.h / GPS.cpp
│   │   │   ├── Display.h / Display.cpp
│   │   │   ├── Battery.h / Battery.cpp
│   │   │   └── Button.h / Button.cpp
│   │   ├── mesh/
│   │   │   ├── MeshNode.h / MeshNode.cpp
│   │   │   ├── Router.h / Router.cpp
│   │   │   ├── ChannelManager.h / ChannelManager.cpp
│   │   │   ├── PacketPool.h / PacketPool.cpp
│   │   │   ├── Crypto.h / Crypto.cpp
│   │   │   └── Portnums.h
│   │   ├── config/
│   │   │   ├── ConfigManager.h / ConfigManager.cpp
│   │   │   ├── ConfigGroups.h
│   │   │   ├── FlashStore.h / FlashStore.cpp
│   │   │   └── Defaults.h
│   │   ├── ble/
│   │   │   ├── BLEServer.h / BLEServer.cpp
│   │   │   ├── MashService.h / MashService.cpp
│   │   │   ├── DFUService.h / DFUService.cpp
│   │   │   └── Framing.h / Framing.cpp
│   │   ├── game/
│   │   │   ├── GameState.h / GameState.cpp
│   │   │   ├── PlayerState.h / PlayerState.cpp
│   │   │   └── AudioCodec.h / AudioCodec.cpp       Codec2 encode/decode wrapper (Phase 7)
│   │   ├── ui/
│   │   │   ├── Screens.h
│   │   │   ├── MainScreen.cpp
│   │   │   ├── MessageScreen.cpp
│   │   │   └── PlayerScreen.cpp
│   │   └── proto/                       Nanopb-generated C sources (committed)
│   ├── lib/                             Third-party firmware libraries
│   │   ├── RadioLib/
│   │   ├── TinyGPSPlusPlus/
│   │   ├── nanopb/
│   │   ├── codec2/                          Codec2 speech codec (Phase 7)
│   │   └── LittleFS/
│   ├── include/
│   │   └── config.h                     Global compile-time constants
│   └── test/
│       ├── test_crypto/
│       ├── test_router/
│       ├── test_framing/
│       ├── test_config/
│       └── test_audio/                      Codec2 encode/decode round-trip (Phase 7)
│
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/
│   │   └── libs.versions.toml           Version catalog
│   └── app/
│       ├── build.gradle.kts
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── kotlin/
│           │   │   └── com/mash/
│           │   │       ├── MashApplication.kt
│           │   │       ├── ui/
│           │   │       │   ├── map/
│           │   │       │   ├── messages/
│           │   │       │   ├── players/
│           │   │       │   ├── game/
│           │   │       │   └── settings/
│           │   │       ├── domain/
│           │   │       │   ├── usecase/
│           │   │       │   └── model/
│           │   │       └── data/
│           │   │           ├── ble/
│           │   │           ├── db/
│           │   │           ├── game/
│           │   │           ├── audio/              PTT audio pipeline, Codec2 JNI wrapper (Phase 7)
│           │   │           └── tiles/
│           │   ├── proto/               Symlink → /proto/mash/
│           │   └── res/
│           ├── test/                    JVM unit tests
│           └── androidTest/             Instrumented tests
│
├── tools/
│   ├── flash.ps1                        Flash firmware to connected T-Echo Plus
│   ├── proto_gen.ps1                    Regenerate nanopb + Kotlin from .proto files
│   └── tile_fetch.py                    Download MBTiles for a bounding box
│
└── docs/
    ├── radio-protocol.md                Wire format reference with annotated byte diagrams
    ├── ble-service.md                   GATT service UUID table and characteristic flows
    └── game-protocol.md                 Game packet flow diagrams (lobby → active → ended)
```

---

## 18. Build & Toolchain

### 18.1 Firmware

| Tool | Required Version | Purpose |
|------|-----------------|---------|
| PlatformIO Core | ≥ 6.1 | Build system and library manager |
| Adafruit nRF52 Arduino core | ≥ 1.6.1 | BSP for nRF52840 |
| RadioLib | ≥ 6.4.0 | SX1262 driver |
| TinyGPS++ | ≥ 1.0.3 | NMEA sentence parser |
| nanopb | ≥ 0.4.8 | Protobuf encoder/decoder for C |
| LittleFS (nRF52 port) | ≥ 0.1.0 | Flash file system |
| Unity test framework | ≥ 2.5.2 | Firmware unit tests |
| codec2 | ≥ 1.2.0 | Open-source speech codec for PTT audio (Phase 7) |
| ARM GCC | ≥ 12.0 | C++ compiler (managed by PlatformIO) |

Common commands:

```powershell
# Build for T-Echo Plus
pio run -e t_echo_plus

# Flash to connected device (USB)
pio run -e t_echo_plus -t upload

# Run firmware unit tests on host (native environment)
pio test -e native

# Open serial monitor
pio device monitor -b 115200
```

### 18.2 Android

| Tool | Required Version | Purpose |
|------|-----------------|---------|
| Android Studio | ≥ Ladybug 2024.2.1 | IDE |
| Kotlin | ≥ 2.0.0 | Language |
| Gradle | ≥ 8.7 | Build system |
| Compose BOM | ≥ 2025.01.00 | Jetpack Compose library set |
| Hilt | ≥ 2.51 | Dependency injection |
| Room | ≥ 2.7.0 | Local database |
| MapLibre GL Android | ≥ 11.0.0 | Map rendering |
| protobuf-kotlin | ≥ 4.26.0 | Protobuf runtime |
| Ktor client | ≥ 2.3.9 | HTTP for tile download |
| JUnit 5 | ≥ 5.10.0 | Unit tests |
| MockK | ≥ 1.13.10 | Mocking |

Common commands:

```powershell
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (device or emulator required)
./gradlew connectedAndroidTest

# Install debug APK to connected device
./gradlew installDebug
```

### 18.3 Protobuf Code Generation

Run `tools/proto_gen.ps1` to regenerate all protobuf outputs. The script:

1. Calls `protoc` with the nanopb plugin on all files in `/proto/mash/`, outputting `.pb.h` and `.pb.c` files to `firmware/src/proto/`.
2. Calls `protoc` with the Kotlin plugin on all files in `/proto/mash/`, outputting Kotlin sources to `android/app/src/main/kotlin/com/mash/proto/`.
3. Does **not** regenerate the vendored Meshtastic protos.

Generated files are committed to the repository. Proto compilation is not part of the CI build pipeline; it is a developer action performed when `.proto` files change.

---

## 19. Testing Strategy

### 19.1 Firmware Tests

**Unit tests** (run on native host, no hardware required):
- AES-128-CTR: encrypt/decrypt round-trip; channel 0 output matches reference vectors derived from Meshtastic test vectors.
- Router: duplicate-suppression cache eviction; hop_limit decrement; SNR relay delay formula.
- Framing: BLE chunk encode/decode at MTU boundaries; single-chunk and multi-chunk payloads.
- Config: serialize/deserialize all 7 config groups; default value application; LittleFS round-trip (mocked).
- Channel hash: channel 0 hash matches Meshtastic reference; channels 1–7 SHA-256 computation.

**Integration tests** (2 MASH devices required):
- Loopback text message: device A sends TEXT_MESSAGE_APP, device B receives and delivers to BLE client.
- Multi-hop relay: 3-device linear chain; message from device A reaches device C via device B; hop count correct.
- Channel isolation: MASH_PLAYER packet on MASH game slot not received by a device with only the interop channel slot active (different PSK).
- Hot-reload: change `DeviceConfig.long_name` over BLE; verify NODEINFO_APP broadcast within 5 s; no reboot.
- Radio restart: change `LoraConfig.tx_power_dbm` over BLE; verify radio restart completes in ≤ 300 ms; BLE connection maintained.
- Per-type hop limit: TEXT_MESSAGE_APP packet traced across 4-device chain; verify hop_limit starts at 7 and decrements correctly at each hop; verify MASH_AUDIO starts at hop_limit=1 and is not relayed.
- Heard-by suppression: 3-device triangle; all three can hear each other; device A sends; verify that B and C each relay at most once.
- Application-layer retry — relay-drop scenario: 3-device chain A→R→B; jam R→B link; verify A retries with new MeshPacket.id but same Data.request_id; verify R forwards retry; verify B receives and applies message exactly once; verify A receives ACK and removes from retry queue.
- Application-layer retry — ACK-lost scenario: 3-device chain A→R→B; B receives but ACK is jammed; verify A retries; verify B deduplicates by Data.request_id and re-sends ACK; verify A ultimately clears queue.
- ACK hop_limit: send MASH_GAME (hop_start=7) over 5-hop chain; verify MASH_ACK is generated with hop_limit=7 and reaches sender at hop 5.
- Implicit ACK: device A broadcasts MASH_PLAYER; device A overhears B's relay; verify A cancels pending retries.
- MASH_POSITION_REQUEST: device A sends MASH_POSITION_REQUEST targeting device B; verify device B broadcasts POSITION_APP within 2 s.
- PHY profile isolation: two MASH devices -- one Profile 0, one Profile 1 on the same slot; verify they cannot receive each other's transmissions.
- Multi-PHY same device: one MASH device with slot 0 on Profile 0 and slot 2 on Profile 1; verify it can receive transmissions on both profiles.

**Interop test** (1 MASH device + 1 stock Meshtastic device):
- Text message exchange in both directions.
- MASH node appears in Meshtastic Android app node list.
- Meshtastic node appears in MASH Android app player list.
- Mesh relay: MASH device relays a Meshtastic packet correctly (hop_limit decremented).

**Audio integration tests** (Phase 7; 2 MASH devices required):
- Codec2 unit test: 1300 bps encode -> decode round-trip on nRF52840; decoded PCM SNR >= 15 dB relative to original.
- PTT loopback: device A transmits audio on Profile 1 slot; device B receives and delivers decoded audio to Android app; confirmed audible.
- Time-multiplexing: device A has Profile 0 mesh channel and Profile 1 audio channel active; transmit 100 MASH_PLAYER packets at 1 Hz; simultaneously conduct 5-second PTT; count MASH_PLAYER packets received by device B; verify loss <= 15%.

### 19.2 Android Tests

**Unit tests** (JVM, no device):
- Game state machine: all valid state transitions; invalid transitions rejected.
- BLE framing: chunk/reassemble at various payload sizes and MTU values.
- Config validation: out-of-range values rejected before BLE transmission.
- Tile coordinate math: MBTiles tile index computation from WGS-84 coordinates.
- Player state: respawn timer expiry triggers ACTIVE transition.

**Instrumented tests** (emulator or device):
- Navigation: tab switches, back navigation, settings apply flow.
- Room DB: message insert, rolling-window deletion, config read/write.
- MapLibre: map renders with bundled tiles; own position pin appears at expected coordinates.

**BLE integration tests** (Android device + T-Echo Plus required):
- Full pairing flow: first-time pair, numeric comparison, bond stored.
- Config round-trip: write LoraConfig, read back, values match.
- Message send/receive: text message sent from Android app received by Meshtastic app on mesh.
- Background reconnect: kill app, wait 30 s, relaunch; BLE reconnects automatically.

### 19.3 Field Acceptance Test

The following test must pass before Phase 6 is considered complete:

- **Devices**: 10 MASH devices active simultaneously.
- **Teams**: At minimum 2 teams with at least 3 players each; 1 admin device (may overlap).
- **Duration**: 60-minute simulated game.
- **Validators**:
  - All 10 players visible on every admin Android app map throughout the game.
  - Hit → respawn cycle completes correctly on all devices.
  - Game start/stop broadcast reaches all 10 devices within 10 seconds.
  - No device requires a reboot during the 60-minute game.
  - At least 1 stock Meshtastic device present on the same channel 0; text message exchange verified.
  - Test conducted with Android app in airplane mode (no internet) from launch to end of game.
  - Battery level on all devices ≥ 20% at end of 60-minute test (starting from 100%).

---

## 20. Known Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| T-Echo Plus pin mapping diverges from Meshtastic reference | Medium | High | Verify against published board schematic and with oscilloscope/logic analyzer before 1.2; record confirmed mapping in board definition comment |
| SX1262 register-level timing sensitivity causes interop failures | Low | High | Use Meshtastic's RadioLib configuration parameters as exact reference; do not experiment with register settings on the interop path |
| Multi-channel simultaneous RX has higher-than-expected packet loss | Medium | Medium | Both channels share PHY so no frequency switching occurs; channel hash filtering is O(1) per received packet; validate loss rate in integration test 2.7 |
| BLE MTU negotiation fails on older Android 12 devices | Low | Medium | The framing protocol handles any MTU ≥ 23 bytes; test on a minimum-spec Android 12 device in Phase 3 |
| 400 mAh battery insufficient for 8-hour target | Medium | Medium | Implement GPS duty cycle in Phase 6; run power profiler measurement after Phase 2 completion to calibrate duty-cycle parameters before field test |
| Protobuf-encoded MASH game packet exceeds 237-byte LoRa payload limit | Medium | Low | Enforce max_size annotations in nanopb proto definitions; Android app shows byte count before send; oversized packets are logged and dropped, never silently truncated |
| Android foreground service killed by OS battery optimization on aggressive OEM firmware | Medium | Medium | Use `startForeground` with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`; Settings screen includes a direct deep-link to battery optimization exemption for the app |
| Meshtastic upstream changes wire format between project start and field use | Low | High | Pin Meshtastic protobuf files at a specific commit (recorded in `VERSION`); no automatic proto updates; treat any Meshtastic proto update as a deliberate compatibility-tested action |
| SPI NOR flash wear from frequent config writes during development | Low | Low | LittleFS handles wear leveling; additionally, avoid writing config on every BLE packet; write only on explicit commit |
| E-Ink display corruption from over-refresh during development | Medium | Low | Enforce minimum 60-second refresh interval in driver; display driver tracks last-refresh timestamp and drops requests that arrive too quickly |
| Radio time-multiplexing (Profile 0 + Profile 1 active) causes Long/Mesh packet loss > 15% during PTT | Medium | Medium | Budget <= 500 ms per audio burst on Profile 1; measure actual loss in Phase 7 test 7.6; if loss exceeds threshold, reduce audio frame batch size or add Long/Mesh dwell windows between audio bursts |
| Codec2 decode on nRF52840 Cortex-M4F exceeds real-time budget | Low | High | Codec2 1300 bps decode requires ~1.5 ms per 20 ms frame on M4F; well within budget; if needed fall back to 700C mode (~0.8 ms) and notify Android app |
| Android RECORD_AUDIO permission denied on hardened OEM builds | Low | Low | PTT audio is opt-in; denial disables PTT button with explanation; does not affect any other app functionality |
| Application-layer retry storms during congestion cause further congestion | Low | Medium | Retry backoff uses full-jitter formula: `wait_ms = random(0, 200 * 2^attempt)`; maximum 3 retries; firmware retry queue bounded at 8 in-flight packets; oldest entries evicted if queue is full |
| Per-packet-type hop limit misconfiguration causes packets not to reach distant nodes | Low | High | All packet types default to hop_limit=7 (maximum); relay count is bounded by mesh topology, not hop_limit, so the safety margin costs no extra airtime; Android app settings show estimated mesh diameter based on last known node positions, with a warning if any node reports a hop distance exceeding the configured hop_limit |
| 100-node mesh channel saturation on Profile 0 at default broadcast interval | High | High | Smart beaconing (§8.7) replaces fixed 30 s interval with movement-triggered TX + 90 s heartbeat; all packet types use hop_limit=7 but relay count is bounded by mesh topology, not hop_limit; airtime control is achieved through TX interval policy, not restricted hop budgets |
| EU 868 MHz 1 % duty-cycle limit violated by short position intervals or audio frame_count too low | Medium | High | Duty cycle is a per-device regulatory obligation; smart beaconing defaults yield ≈ 310 ms ToA per 90 s = 0.34 % DC per device on Profile 0; audio with FEC has ~84 ms ToA on Profile 1 — frame_count ≥ 42 required for sub-band g1 (10 %) compliance; sub-band g (1 %) is not viable at 1300 bps and requires 915 MHz; AudioConfig screen displays calculated per-device DC and warns on non-compliance; final responsibility rests with the operator |
| PTT audio single-hop range insufficient on 10 km fields | Medium | Medium | Documented in §12.7 and §13.4; fixed relay nodes on elevation extend mesh range; audio itself does not relay (hop_limit=1); players rely on mesh relay nodes for text/game data and accept direct-range limitation for voice |
| Flood-based ACK is probabilistic, not guaranteed (unlike MeshCore routed ACK) | Low | Low | Ten systematic issues addressed in v1.3–v1.4: MASH_ACK hop_limit mirrors original hop_start (wire header Flags [2:0]); retries use new packet_id; timeout scaled to 2×hop_start×400 ms; hop_start fallback=7 for legacy devices; MASH_PLAYER ACK rule clarified (state transitions only); retry backoff standardised at 200 ms base; residual failure mode is genuine unreachability, not a protocol artifact |

---

## 21. Glossary

| Term | Definition |
|------|-----------|
| **AES-128-CTR** | Advanced Encryption Standard in Counter mode with 128-bit key; used for channel encryption |
| **Codec2** | Open-source speech codec designed for narrow-band voice at very low bit rates; MASH uses the 1300 bps mode (26 bits per 20 ms frame) for PTT audio over LoRa |
| **BLE** | Bluetooth Low Energy (Bluetooth 4.0+) |
| **BW** | Bandwidth — LoRa PHY parameter; values: 125 kHz, 250 kHz, 500 kHz |
| **BSP** | Board Support Package — the hardware-specific layer of the firmware toolchain |
| **CoT** | Cursor-on-Target — the XML message format used by ATAK; not used in MASH |
| **CR** | Coding Rate — LoRa PHY parameter; values: 4/5, 4/6, 4/7, 4/8 |
| **DFU** | Device Firmware Update — Nordic's BLE OTA firmware update protocol |
| **FICR** | Factory Information Configuration Registers — nRF52840 read-only hardware ID registers |
| **GATT** | Generic Attribute Profile — the BLE service and characteristic framework |
| **HDOP** | Horizontal Dilution of Precision — dimensionless GPS accuracy metric; lower is better |
| **LittleFS** | Lightweight filesystem designed for NOR flash storage; provides wear leveling |
| **LoRa** | Long Range — spread-spectrum radio modulation by Semtech |
| **MASH** | Mesh Airsoft Situational Hub — this project |
| **MBTiles** | An SQLite-based container format for map tile data |
| **Meshtastic** | Open-source LoRa mesh networking project (Apache 2.0); MASH is interoperable with it |
| **MTU** | Maximum Transmission Unit — maximum byte count per BLE ATT packet |
| **NMEA** | National Marine Electronics Association — standard GPS sentence format |
| **NOR flash** | Non-volatile flash memory used for config storage on the T-Echo Plus |
| **PHY** | Physical layer — the LoRa modulation parameters (SF, BW, CR, frequency) |
| **PHY Profile** | A named set of SF/BW/CR parameters assigned to a MASH channel slot; nodes must share the same PHY profile to communicate on a channel |
| **PTT** | Push-to-Talk — half-duplex voice communication mode; user holds a button to transmit voice and releases to receive |
| **PSK** | Pre-Shared Key — symmetric encryption key distributed out-of-band to game participants |
| **Duty Cycle** | Regulatory limit on the fraction of time a radio may transmit on a given frequency band; EU 868 MHz sub-band g = 1 %, sub-band g1 = 10 %; each device operator is individually responsible for compliance; MASH smart beaconing and AudioConfig DC display help operators stay within their legal limits |
| **FEC** | Forward Error Correction — MASH audio uses a piggyback FEC scheme: each AudioPacket carries the previous packet's Codec2 bytes so the receiver can reconstruct a single lost packet without retransmission |
| **PLC** | Packet Loss Concealment — when two or more consecutive audio packets are lost and FEC cannot recover them, the Codec2 decoder synthesises comfort noise to mask the gap |
| **Smart Beaconing** | MASH position broadcast policy: transmit when GPS displacement ≥ 10 m (min 15 s apart) or when 90 s heartbeat fires; replaces fixed-interval beaconing to help each device operate within regulatory duty-cycle limits while minimising channel load in typical deployments |
| **TDM** | Time-Division Multiplexing — used by MASH to share the single SX1262 radio between channel slots that use different PHY profiles |
| **SNR** | Signal-to-Noise Ratio — radio link quality metric; higher is better |
| **SX1262** | Semtech SX1262 — the LoRa transceiver IC used in the T-Echo Plus |
| **T-Echo Plus** | LilyGo T-Echo Plus development board; the sole supported firmware hardware target |

---

*End of MASH Master Planning Document v1.4*
*This document is immutable after acceptance. Implementation decisions that deviate from this document require a new versioned planning document, not amendments here.*
