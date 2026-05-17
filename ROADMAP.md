# AirTAK — Development Roadmap
## Status: IMMUTABLE AFTER ACCEPTANCE — 2026-05-13
## Derived from: PLANNING.md v1.6
## Amendment: v1.5 — 2026-05-14 — Hop limits unified to 7 for all packet types except AIRTAK_AUDIO=1 (§8.7). Deliverable 2.9 verification values updated accordingly. Profile 3 (Medium/Slow SF10/BW250/CR4/5) added; User Defined moves to index 4 (§8.1).
## Amendment: v1.6 — 2026-05-17 — Phase 5 redesign: game-mode system replaced with tactical coordination model. All Phase 5 deliverables rewritten. Formal game modes, admin role, lobby/pause, respawn timers, win conditions, and scoring removed. Replaced with: unit hierarchy configuration, scoped information sharing, tactical marker system (AIRTAK_TACTICAL portnum 257), simplified player status, manual Active/Ended toggle, roster by hierarchy, marker management screen, marker re-broadcast on connect, and decentralized vote-kick moderation (AIRTAK_MODERATION portnum 258) with 3-vote shadowban threshold and 24-hour auto-expiry. Portnums 259–260 reserved. Phase 6 field test validators updated. Cross-dependencies and T.9 updated.

This roadmap enumerates every deliverable required to produce a production-ready AirTAK system. Deliverables are organized into the seven sequential phases defined in §16 of PLANNING.md. A phase MUST NOT be declared complete until every deliverable within it is implemented and every test specified for it in §19 has passed.

Cross-dependencies are declared explicitly on each item where they exist. A cross-dependency means the listed prior deliverable must be completed and its tests passing before the dependent item can begin.

---

## Dependency Notation

`⊣ X.Y` means: blocked until deliverable X.Y is complete and tested.

---

## Phase 1 — Firmware Foundation

All items in Phase 1 are prerequisites for every item in Phase 2. No Phase 2 work begins until Phase 1 is entirely complete.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **1.1** | PlatformIO project scaffolding | Create `firmware/platformio.ini` with the `t_echo_plus` environment, Adafruit nRF52 Arduino core ≥ 1.6.1, and the full dependency set listed in §18.1 (RadioLib ≥ 6.4.0, TinyGPS++ ≥ 1.0.3, nanopb ≥ 0.4.8, LittleFS, Unity). Create `firmware/boards/t_echo_plus.json` board definition. Verify against the actual T-Echo Plus schematic and commit the confirmed pin map as a comment block per §7.1. Board must produce a clean firmware image with `pio run -e t_echo_plus`. | None |
| **1.2** | SX1262 radio bring-up | Implement `hal/Radio.h` and `hal/Radio.cpp` as a thin wrapper over RadioLib. Configure the SX1262 with the global PHY parameters defined in §8.1: frequency (EU868 default 869.525 MHz), +22 dBm TX power, sync word 0x2B, preamble 16 symbols, explicit header, CRC on, IQ not inverted. Demonstrate TX/RX loopback between two physical T-Echo Plus devices at Profile 0 (SF11/BW250/CR4/5) with packet receipt confirmed on both sides. | ⊣ 1.1 |
| **1.3** | L76K GPS bring-up | Implement `hal/GPS.h` and `hal/GPS.cpp`. Initialize the L76K via UART at 9600 baud, upgrade to 115200 baud on first valid NMEA sentence, configure via PMTK commands to emit GNGGA and GNRMC only. Track fix quality (HDOP, satellite count). Demonstrate cold fix acquisition on real hardware. Record the PPS pin (P1.04) in the board definition; do not use it in logic (reserved). | ⊣ 1.1 |
| **1.4** | E-Ink display bring-up | Implement `hal/Display.h` and `hal/Display.cpp` over the SSD1681 controller. Render the static Main screen (node name placeholder, GPS placeholder, battery placeholder, node count placeholder). Enforce the minimum 60-second interval between full refreshes per §9.6. Dirty-state tracking per screen element must be in place from the start. | ⊣ 1.1 |
| **1.5** | BLE GATT server skeleton | Implement `ble/BLEServer.h`, `ble/AirTAKService.h`, and `ble/DFUService.h`. Register the AirTAK GATT service (UUID `4d415348-0000-1000-8000-00805f9b34fb`) with all five characteristics as defined in §10.1. Implement the BLE framing protocol (`ble/Framing.h`) per §10.2: 6-byte frame header, chunked reassembly, MTU-517 negotiation. The `DEVICE_STATUS` characteristic must be readable (open, no bond required). All write characteristics must require an authenticated connection (security mode 1, level 3). Nordic DFU service registered alongside AirTAK service. | ⊣ 1.1 |
| **1.6** | LittleFS config store | Implement `config/FlashStore.h` and `config/FlashStore.cpp`. LittleFS mounted on the GD25Q32C SPI NOR flash. Separate config files per group: `/config/device.pb`, `/config/lora.pb`, `/config/channels.pb`, `/config/position.pb`, `/config/power.pb`, `/config/game.pb`, `/config/security.pb`. Protobuf binary encode/decode (nanopb) for all 7 config groups. Default load from `Defaults.h` when no config file exists. Persist correctly across power cycles. | ⊣ 1.1 |
| **1.7** | FreeRTOS task skeleton | In `main.cpp`, create all FreeRTOS tasks with the priorities and stack sizes specified in §9.3: Radio RX/TX (HIGH/4), GPS Parser (NORMAL/3), BLE Server (NORMAL/3), Config Apply (LOW/2), Display Refresh (LOW/1), Battery Monitor (IDLE/0). Define all inter-task queues and event groups. Tasks may be stubs that do nothing; the objective is that the RTOS scheduler starts cleanly, all tasks are created without assert failures, and the firmware boots to a stable idle state. | ⊣ 1.1 – 1.6 |

### Phase 1 Exit Tests

All firmware unit tests (§19.1, unit test section) that are relevant to Phase 1 infrastructure must pass under `pio test -e native` before Phase 2 begins. Specifically: config serialize/deserialize round-trip for all 7 groups, LittleFS round-trip with mocked flash, BLE framing chunk encode/decode at MTU boundaries.

---

## Phase 2 — Mesh Stack

All items in Phase 2 depend on all of Phase 1. Within Phase 2, items with intra-phase dependencies are noted explicitly. Items without intra-phase dependencies may be developed in parallel.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **2.1** | AES-128-CTR encryption | Implement `mesh/Crypto.h` and `mesh/Crypto.cpp`. Key derivation: `SHA256(psk_bytes)[0:16]`. CTR nonce: `packet_id (32 bits) \|\| from_node_id (32 bits) \|\| 0x00...` padded to 128 bits. Channel 0 key derivation and nonce construction MUST be byte-for-byte identical to the Meshtastic implementation at the pinned commit in `/proto/meshtastic/VERSION`. Validate against Meshtastic test vectors. | ⊣ Phase 1 complete |
| **2.2** | NODEINFO_APP broadcast | Implement `mesh/MeshNode.h` and `mesh/MeshNode.cpp`. At startup and every 3 hours broadcast a NODEINFO_APP packet on channel 0 with `hw_model=LILYGO_TECHO`, `long_name` from DeviceConfig, `short_name` from DeviceConfig, `macaddr` from FICR device address lower 6 bytes, per §13.2. Demonstrate the AirTAK node appearing in the Meshtastic Android app node list. | ⊣ 2.1 |
| **2.3** | TEXT_MESSAGE_APP TX/RX | Accept a text message payload delivered over BLE (`MESH_TX` characteristic) and transmit it as a TEXT_MESSAGE_APP (portnum 1) LoRa packet on channel 0. Receive TEXT_MESSAGE_APP packets from the mesh and deliver them via BLE notify on `MESH_RX`. Demonstrate bidirectional text exchange with a stock Meshtastic device. | ⊣ 2.1, 2.2 |
| **2.4** | POSITION_APP TX | When the GPS task reports a new fix, package the position into a POSITION_APP (portnum 3) packet using the precision bits from PositionConfig and transmit on channel 0. Apply smart beaconing: transmit when GPS displacement ≥ 10 m AND ≥ 15 s since last TX, or when 90 s heartbeat fires, per §8.7. Players in HIT, RESPAWNING, or ELIMINATED states extend the heartbeat to 5 minutes. Demonstrate AirTAK position appearing in the Meshtastic Android app. | ⊣ 2.1, 1.3 |
| **2.5** | Flooding router with duplicate suppression | Implement `mesh/Router.h` and `mesh/Router.cpp`. Implement Meshtastic-compatible ManagedFlood: re-broadcast up to `hop_limit` times; decrement `hop_limit` per hop; discard packets at `hop_limit=0` after receiving; rolling duplicate-suppression cache keyed on `(source_id, packet_id)`; `hop_start` (Flags [2:0]) preserved unchanged across all relay hops per §8.2. Per-packet-type default hop limits implemented as specified in §8.7 hop-limit table. | ⊣ 2.1 |
| **2.6** | SNR-based relay delay | Add SNR-based relay backoff to the Router: `delay_ms = (SNR_max − SNR_received) × 50 + random(0, 50)` per §8.7. Measure (not just compile) the delay by logging relay timestamps during the 3-device linear chain integration test. | ⊣ 2.5 |
| **2.7** | Multi-channel simultaneous RX | Implement `mesh/ChannelManager.h` and `mesh/ChannelManager.cpp`. Manage up to 8 channel slots per §8.5. Channel hash computation per §8.6: XOR-fold for slot 0 (Meshtastic interop), SHA-256 first byte for slots 1–7. When all active slots share the same `phy_profile_index`, the radio listens continuously with O(1) channel dispatch by hash comparison. Simultaneously active slots with separate PSKs receive, decrypt, and route packets independently. Demonstrate two active slots with different PSKs: a packet on slot 0 is not decryptable when only slot 1's PSK is known and vice versa. | ⊣ 2.5 |
| **2.8** | Hot-reload config | Implement `config/ConfigManager.h` and `config/ConfigManager.cpp`. All 8 config groups (including AUDIO, added in Phase 7) apply without MCU reboot. Groups apply immediately to running state. Changes that require a radio restart (CHANNELS PSK/PHY changes, LORA parameter changes) trigger the controlled radio-restart procedure defined in §9.4.2: pause Radio task, drain TX queue, `Radio::reinit()`, `ChannelManager::rekey()`, resume Radio task. Target: ≤ 300 ms for radio restart. BLE connection survives the restart. Staged commit protocol per §9.4.3 (multiple groups staged, single commit, at most one radio restart). | ⊣ 2.7, 1.6 |
| **2.9** | Per-packet-type hop limits | Enforce all per-packet-type default `hop_limit` values from the table in §8.7 (as updated in PLANNING v1.6). These are hardcoded in the Router's packet-type dispatch table in `Defaults.h`. Verify: POSITION_APP departs with `hop_limit=7`; AIRTAK_TACTICAL departs with `hop_limit=7`; TELEMETRY_APP departs with `hop_limit=7`; AIRTAK_AUDIO departs with `hop_limit=1`. The `PositionConfig.position_hop_limit` config field overrides the POSITION_APP default when set. | ⊣ 2.5 |
| **2.10** | Heard-by relay suppression | During the relay backoff window, if the Router overhears a relay of a queued packet (same `source_id + packet_id`) from a different relayer whose received SNR ≥ own received SNR, cancel the queued relay per §8.7. Validate with the 3-device linear-chain test specified in §19.1: middle node cancels relay after overhearing the relay from the end node. | ⊣ 2.6 |
| **2.11** | Application-layer reliability (ACKNOWLEDGED delivery) | Implement the full ACKNOWLEDGED delivery flow per §8.9. On first send of an ACKNOWLEDGED packet: set `Data.want_response=true`, `Data.request_id = MeshPacket.id` (logical `msg_id`). On retry: generate a new `MeshPacket.id` but keep `Data.request_id = msg_id`. ACK timeout formula: `max(500, 2 × hop_start × 400)` ms; `hop_start` read from Flags [2:0] of the wire header; default to 7 if zero. Retry backoff: `random(0, 200 × 2^attempt)` ms; up to 3 retries. Retry queue: maximum 8 in-flight entries; oldest evicted when full. Implicit ACK: sender overhears own relay → cancels pending retries. Receiver deduplicates by `(source_id, Data.request_id)` at application layer. AIRTAK_ACK (portnum 261) contains `ack_request_id = Data.request_id` and `hop_limit = hop_start` of the original packet. AIRTAK_PLAYER ACKNOWLEDGED vs BEST_EFFORT rule: ACKNOWLEDGED only when `PlayerStatePacket.is_state_transition = true`; BEST_EFFORT on heartbeat (`is_state_transition = false`). Three integration test scenarios must pass per §19.1: (a) 3-hop ACK delivery and ACK return; (b) relay-drop with new-packet-id retry; (c) ACK-lost dedup scenario. | ⊣ 2.5, 2.7, T.2 |
| **2.12** | AIRTAK_POSITION_REQUEST | Implement portnum 262 (`AIRTAK_POSITION_REQUEST`). When a node receives a `PositionRequestPacket` with `target_node_id` matching its own node ID, it broadcasts a POSITION_APP packet within 2 seconds, bypassing the smart beaconing timer. Default `hop_limit = 5` per §8.7 hop-limit table. Demonstrate in the integration test: device A sends AIRTAK_POSITION_REQUEST targeting device B; device B broadcasts POSITION_APP within 2 s. | ⊣ 2.4, 2.5 |

### Phase 2 Exit Tests

All firmware unit tests: AES-128-CTR vectors, Router duplicate-suppression, channel hash computation (slot 0 matches Meshtastic reference; slots 1–7 SHA-256 computation). All firmware integration tests for mesh stack per §19.1: loopback text, multi-hop relay, channel isolation, hot-reload, radio restart, per-type hop limit, heard-by suppression, application-layer retry (all three scenarios), AIRTAK_POSITION_REQUEST. Meshtastic interop tests per §19.1: text exchange, node visibility, relay, Meshtastic node in AirTAK player list.

---

## Phase 3 — Android App Foundation

All items in Phase 3 depend on Phase 1 and Phase 2 being complete (a device running Phase 2 firmware is required for BLE integration testing). Within Phase 3, items with intra-phase dependencies are noted explicitly.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **3.1** | Android project scaffolding | Create the Android project under `android/` per the structure in §17. Configure: Kotlin ≥ 2.0, Compose BOM ≥ 2025.01.00, Hilt ≥ 2.51, Room ≥ 2.7, protobuf-kotlin ≥ 4.26, MapLibre GL Android ≥ 11.0, Ktor client ≥ 2.3.9, JUnit 5, MockK. `libs.versions.toml` version catalog. `AndroidManifest.xml` declares exactly the permissions listed in §11.2 and no others. `RECORD_AUDIO` is included with `android:required="false"` only if the Phase 7 build flag is set; it must not appear in the Phase 3 manifest. Min SDK 31, target SDK 35, ABI `arm64-v8a` and `x86_64`. Project must produce a debug APK via `./gradlew assembleDebug`. | None |
| **3.2** | BLE transport layer | Implement `data/ble/BleRepository`. Scan for bonded AirTAK GATT service UUID. Pair via LE Secure Connections / Numeric Comparison (§10.6). Connect, negotiate MTU 517. Implement the chunk-framing protocol (6-byte header, reassembly) for all BLE characteristics. Implement the connection state machine from §11.10: DISCONNECTED → SCANNING → CONNECTING → CONNECTED → RECONNECTING with exponential backoff (1 s, 2 s, 4 s … 60 s). Expose a Kotlin coroutine-based API for sending and receiving framed payloads. | ⊣ 3.1 |
| **3.3** | DEVICE_STATUS live display | Implement `domain/usecase/ObserveDeviceStatusUseCase` and the corresponding ViewModel. Subscribe to `DEVICE_STATUS` BLE notifications. Display battery percentage, GPS fix status, satellite count, RSSI, and uptime on the Settings > About section. Values update in real time as notifications arrive. | ⊣ 3.2 |
| **3.4** | Settings screen — all config groups | Implement `ui/settings/SettingsScreen` in Jetpack Compose. Sections per §11.9: Device, LoRa, Channels, Position, Power, Game, App, About. Read all 7 config groups from the device via `CONFIG_RW`. Staged apply: changes are staged locally; Apply button per section commits via `ConfigPayload.commit=true`. Sections that trigger a radio restart display an amber "⚠ ~300 ms radio pause" indicator per §11.9. PSK fields are obscured (password field) with reveal toggle; accept UTF-8 or `0x`-prefixed hex; validate ≤ 32 bytes before send. LoRa section exposes raw SF/BW/CR per profile; each profile name is editable; Meshtastic preset name shown in parentheses. Channel slot editor shows per-slot PHY profile picker with compatibility indicator (warns when slot's profile differs from known nearby nodes on that slot). | ⊣ 3.2, 3.3 |
| **3.5** | Messages screen — channel 0 | Implement `ui/messages/MessagesScreen` in Compose. Send TEXT_MESSAGE_APP via BLE `MESH_TX`. Receive TEXT_MESSAGE_APP via BLE `MESH_RX` notifications and display in a reverse-chronological list. Channel tab row (default tabs "Mesh" and "Game"). Compose field with remaining byte counter; reject messages that would exceed 237-byte LoRa payload with a user-visible error (never silently truncate). Room DB persistence: rolling 1 000-message window per channel, oldest auto-deleted. `POST_NOTIFICATIONS` permission requested in context to show background message notifications. | ⊣ 3.2, 3.1 |
| **3.6** | MapLibre map with own position | Implement `ui/map/MapScreen` in Compose using MapLibre GL Android. Bundled world overview tileset (zoom 0–5) renders offline. Own GPS position (received from `DEVICE_STATUS.gps`) renders as a blue directional arrow. Map centers on own position at launch. Toolbar: recenter button, layer visibility toggles (zones, objectives, players, waypoints). | ⊣ 3.3, 3.1 |
| **3.7** | Background BLE foreground service | Implement the `FOREGROUND_SERVICE_CONNECTED_DEVICE` foreground service that maintains the BLE connection when the app is backgrounded. Persistent notification reads "AirTAK — Connected to [node name]" with a disconnect action. Reconnect backoff resets to 1 s on successful connection. Settings > About includes a deep-link to the system battery optimization exemption for the app. Validate: kill app process, wait 30 s, relaunch; BLE reconnects automatically. BLE connection held ≥ 30 minutes with app backgrounded. | ⊣ 3.2 |

### Phase 3 Exit Tests

Android unit tests: BLE framing at various MTU values, config validation, tile coordinate math. Android instrumented tests: navigation, Room DB insert/delete/read, MapLibre render with bundled tiles. BLE integration tests (Android device + T-Echo Plus required): full pairing flow, config round-trip, message send/receive, background reconnect per §19.2.

---

## Phase 4 — Situational Awareness

All items in Phase 4 depend on all of Phase 3 being complete. Items 4.1–4.3 can proceed in parallel. Item 4.4 depends only on 3.1 (MapLibre). Item 4.5 depends on 3.6.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **4.1** | Remote node positions on map | Parse incoming POSITION_APP packets from `MESH_RX`. Maintain a `MeshRepository` node table in Room DB. Render remote nodes as colored circular pins with short-name labels on the MapLibre map. Pin color reflects team assignment from the most recent AIRTAK_PLAYER packet for that node. Pins gray out when no position received within 5 minutes (OFFLINE). | ⊣ Phase 3 complete |
| **4.2** | AIRTAK_PLAYER TX/RX | Implement portnum 256 handling. Transmit `PlayerStatePacket` from the Android app (own player state changes) via BLE `MESH_TX`. Receive `PlayerStatePacket` from `MESH_RX`. Apply ACKNOWLEDGED vs BEST_EFFORT rule per §8.9: ACKNOWLEDGED when `is_state_transition=true`, BEST_EFFORT when `is_state_transition=false`. Apply status overlays on map pins: ACTIVE = solid pin, HIT = hollow pin, ELIMINATED = pin with X, no recent position = grayed out. | ⊣ 4.1 |
| **4.3** | Roster screen | Implement `ui/roster/RosterScreen` in Compose. Nodes organized by unit hierarchy with collapsible Company→Platoon→Squad→Fireteam sections. Per-node row: callsign, last-seen time, battery level indicator, status chip (ACTIVE / HIT / RESPAWNING / ELIMINATED / OFFLINE). OFFLINE = no AIRTAK_PLAYER received in last 5 minutes. Extended status only shown when explicitly set. Filter toggle: Show All / Online Only. Tap node for detail sheet. | ⊣ 4.2 |
| **4.4** | Offline tile downloader | Implement `data/tiles/TileRepository`. In-app bounding-box tile download: user draws a bounding box on the overview map and selects zoom range 6–17. Download tiles from a configurable tile server URL (no hardcoded URL; user sets URL in Settings > App). Store as MBTiles in `context.filesDir` (no storage permission required). Verify offline operation in airplane mode with downloaded tiles. | ⊣ 3.6 |
| **4.5** | Local waypoints | Implement local waypoints: long-press map to place a waypoint with a label and icon. Store in Room DB. Render on map. Waypoints are never broadcast over mesh; local-device-only. Layer visibility toggle in map toolbar controls waypoint visibility. | ⊣ 3.6 |

### Phase 4 Exit Tests

Android instrumented tests: map pin rendering with mock AIRTAK_PLAYER data, Roster screen hierarchy grouping and filter. Field validation with at least 3 AirTAK devices: all node positions visible on map, status overlays correct, tile download and offline verification in airplane mode.

---

## Phase 5 — Tactical Coordination

All items in Phase 5 depend on all of Phase 4 being complete. Items within Phase 5 have intra-phase dependencies noted explicitly.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **5.1** | Unit hierarchy configuration | Implement unit ID fields in `GameConfig` (`fireteam_id`, `squad_id`, `platoon_id`, `company_id`; all free-text, all optional). Broadcast unit IDs in every AIRTAK_PLAYER heartbeat. Android Settings > Game exposes four text fields. No central enrollment, no uniqueness enforcement, no admin. | ⊣ Phase 4 complete |
| **5.2** | Simplified player status | Implement ACTIVE/INACTIVE base statuses plus optional extended flags (HIT/RESPAWNING/ELIMINATED). Manual toggle in Android app (status chip on Map and Roster screens). No automatic transitions, no respawn timers, no admin overrides. HIT-toggle via device button is configurable (`GameConfig.hit_button_enabled`), disabled by default. Extended-status players use 5-min position heartbeat per §8.7. | ⊣ 5.1, 4.2 |
| **5.3** | Tactical marker protocol | Implement AIRTAK_TACTICAL (portnum 257) with all five marker types (POINT/ROUTE/AREA/NOTE/OBJECTIVE) per §12.3 and §14.3. ACKNOWLEDGED delivery per §8.9. Create/update/delete lifecycle (`deleted=true` removes marker). Local Room DB persistence and mesh sync. Marker fields: `marker_id`, `type`, `label`, `description`, `color_rgb`, `scope`, `creator_node_id`, timestamps, `deleted`, `points`, and objective-specific `controlling_team`/`priority`. | ⊣ 5.1, T.2 |
| **5.4** | Tactical marker drawing tools | Implement Android map drawing modes: tap to place POINT; tap waypoints to build ROUTE; tap vertices to define AREA polygon; tap location + type text for NOTE; tap location + set team-control and priority for OBJECTIVE. All markers get a label and optional description before send. Vertex limits enforced in UI (12 for AREA, 16 for ROUTE) to stay within 237-byte payload. | ⊣ 5.3 |
| **5.5** | Scoped information sharing | Implement `Scope` enum (ALL/COMPANY/PLATOON/SQUAD/FIRE_TEAM) on AIRTAK_TACTICAL and scoped text messages. Receiver-side display filtering: match sender's unit ID at the specified scope level; ALL bypasses all filtering. Scope selector in send UI with preview of "who will see this" based on known nodes sharing the same unit ID. | ⊣ 5.1, 5.3 |
| **5.6** | Roster screen | Implement `ui/roster/RosterScreen` in Compose. Nodes organized by unit hierarchy with collapsible Company→Platoon→Squad→Fireteam sections. Per-node row: callsign, last-seen time, battery indicator, status chip (ACTIVE/HIT/RESPAWNING/ELIMINATED/OFFLINE). Extended status only shown when explicitly set. Filter: Show All / Online Only. Tap node for detail sheet with "Request position" button triggering AIRTAK_POSITION_REQUEST (portnum 262). | ⊣ 5.2, 4.1 |
| **5.7** | Markers screen | Implement `ui/markers/MarkersScreen` in Compose. Filterable list of all active tactical markers by type, scope, and creator. Each row shows type icon, label, creator callsign, scope badge, and last-modified time. Tap to fly-to on map. Swipe to delete own markers (broadcasts `deleted=true`). Objective markers color-coded by `controlling_team` (OWN=green, OPPOSING=red, NEUTRAL=yellow, UNKNOWN=grey). | ⊣ 5.3, 5.4 |
| **5.8** | Game Active/Ended toggle | Implement manual per-device Active/Ended toggle on the Map screen toolbar. ACTIVE: full mesh participation (position broadcast, markers active, messages processed). ENDED: stop broadcasting own position and markers; continue relay for other participants. Status persisted in local config. No centralized game control, no lobby, no pause, no round timers. | ⊣ 5.2, 5.3 |
| **5.9** | Marker re-broadcast on connect | On BLE connect or app launch, re-broadcast all locally-created active markers (not deleted, created by this node) so late-joining nodes receive the full marker set. Rate-limited to one burst per connect event; markers sent with 500 ms spacing to avoid flooding the TX queue. | ⊣ 5.3 |
| **5.10** | Vote-kick moderation | Implement AIRTAK_MODERATION (portnum 258) with VOTE_BAN/UNBAN actions per §12.7. Three distinct votes from nodes sharing the same scope activate a shadowban — all data from the target node (AIRTAK_PLAYER, AIRTAK_TACTICAL, text messages, POSITION_APP) is suppressed at the display layer. Bans auto-expire after 24 hours; UNBAN from any previous voter lifts immediately. Android UI: "Vote to Ban" from roster detail sheet with scope selector; active ban list in Settings > App > Moderation with unban control for previous voters. ACKNOWLEDGED delivery per §8.9. | ⊣ 5.1, 5.6 |

### Phase 5 Exit Tests

Firmware integration tests: AIRTAK_TACTICAL create/update/delete round-trip over 3-hop chain; scope filtering verified (PLATOON-scoped marker visible to same-platoon node, hidden from different-platoon node sharing only company ID); AIRTAK_MODERATION vote tally and ban activation with 3 distinct voters; unban resets tally. Android unit tests: tactical marker lifecycle, scope filtering logic, marker vertex-limit enforcement, marker Room DB persistence, moderation vote tally and ban expiry logic. Field test with ≥ 5 AirTAK devices: tactical markers propagate to all devices within 10 seconds; scoped messages only appear for intended recipients; unit hierarchy roster correctly groups nodes; Active/Ended toggle stops own broadcasts but relay continues; late-joining node receives full marker set from re-broadcast; 3-vote ban activates and suppresses banned node data; unban lifts suppression.

---

## Phase 6 — Polish & Hardening

All items in Phase 6 depend on all of Phase 5 being complete.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **6.1** | Nordic DFU OTA firmware update | Implement `ble/DFUService.h` integration with the Android app. The Android app triggers DFU entry via the ADMIN characteristic (`AdminCommand.DFU_ENTER`). After entering DFU mode, the device is visible to nRF Connect and to the AirTAK app's built-in DFU initiator. A firmware binary (.zip DFU package) can be delivered from the Android app. Validate end-to-end: build a new firmware version, flash via DFU over BLE, verify new version running. | ⊣ Phase 5 complete |
| **6.2** | GPS duty cycle power saving | Implement `PowerConfig.gps_duty_cycle` toggle and `gps_on_secs` / `gps_off_secs` parameters in the GPS task per §9.5. When enabled, the L76K is powered off between fix periods using the ENABLE pin (P1.02). Verify that position update rate and accuracy are acceptable with duty cycling enabled. Calibrate the default on/off durations against battery life measurements. | ⊣ Phase 5 complete |
| **6.3** | Battery life validation ≥ 8 hours | Run the device in a simulated field scenario (GPS fix acquired, radio active, display refreshed every 5 minutes, BLE connected) starting at 100% battery. Confirm ≥ 8 hours to the device powering off or battery reaching 0%. Document the measured current draw per state against the estimates in §7.2. Adjust GPS duty cycle defaults if needed to meet the target. | ⊣ 6.2 |
| **6.4** | Field acceptance test — 10 devices | Execute the full field acceptance test per §19.3: 10 AirTAK devices active, ≥ 2 organizational units, 60-minute tactical exercise. All acceptance validators must pass: all 10 players visible on every map, tactical markers propagate to all devices within 10 s, scoped messages only reach intended recipients, unit hierarchy roster correctly groups nodes, no device reboots, ≥ 1 stock Meshtastic device on channel 0 with verified text exchange, Android app in airplane mode from launch to end, all devices ≥ 20% battery at 60-minute mark. | ⊣ 6.3, Phase 5 complete |
| **6.5** | Meshtastic interop field test | With a stock Meshtastic device on the same channel 0 during the 6.4 field test or as a standalone test: verify bidirectional text message exchange, AirTAK node visible in Meshtastic Android app, Meshtastic node visible in AirTAK Android app node list, Meshtastic device correctly relays a AirTAK mesh packet (hop_limit decremented). | ⊣ 6.4 |
| **6.6** | Android background stability — 8 hours | With the Android app backgrounded on 3 different Android devices (spanning different OEM firmware variants including at least one aggressive battery-optimization OEM), verify BLE connection is maintained for ≥ 8 hours. Verify the foreground service notification is persistent and the disconnect action works. Document any OEM-specific battery optimization exemption steps; add them to Settings > About. | ⊣ 6.4 |
| **6.7** | Fully offline end-to-end test | From a cold app launch with airplane mode enabled (no internet), run a complete tactical exercise (position sharing, marker creation, scoped messaging, Active→Ended toggle) on ≥ 3 devices. Verify: MapLibre renders from MBTiles (previously downloaded), BLE connects, all coordination features functional, no error dialogs or crashes attributable to lack of internet. | ⊣ 6.4 |
| **6.8** | Daylight readability review | In direct sunlight: verify e-ink display contrast is legible for all four screen layouts (Main, Messages, Roster, Radio). In direct sunlight: verify Android app text and status chips are legible. Document any contrast deficiencies and fix before declaring Phase 6 complete. | ⊣ 6.4 |
| **6.9** | Per-slot PHY field test | With two AirTAK devices, activate two channel slots on the same device — slot 0 on Profile 0 (SF11/BW250) and slot 1 on Profile 1 (SF7/BW250). Verify: a Profile 0 device cannot receive Profile 1 transmissions (PHY isolation); a Profile 1 device cannot receive Profile 0 transmissions. Trigger a radio restart via PHY profile change over BLE; verify restart completes in ≤ 300 ms and the BLE connection is maintained throughout. | ⊣ 6.4 |

### Phase 6 Exit Tests

All items in §19.3 Field Acceptance Test. All six additional Phase 6 items must produce documented pass results. No device reboots in 60-minute field test. Battery life ≥ 8 hours documented. Android background stability ≥ 8 hours on 3 devices documented.

---

## Phase 7 — PTT Audio

All items in Phase 7 depend on all of Phase 6 being complete. Within Phase 7, items with intra-phase dependencies are noted explicitly.

### Deliverables

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **7.1** | Codec2 firmware integration | Add `codec2 ≥ 1.2.0` to `firmware/lib/codec2/`. Implement `game/AudioCodec.h` and `game/AudioCodec.cpp` as a wrapper. Validate 1300 bps mode (26 bits per 20 ms frame) encode/decode round-trip on the nRF52840 Cortex-M4F. Decoded PCM SNR must be ≥ 15 dB relative to original. Measure decode latency: must be ≤ 1.5 ms per 20 ms frame (M4F capability). Add `test/test_audio/` unit test targeting the native PlatformIO environment. | ⊣ Phase 6 complete |
| **7.2** | Short/Speed PHY profile channel slot | Activate the pre-configured audio slot (slot 2, Profile 1: SF7/BW250/CR4/5) from DISABLED to SECONDARY role in `Defaults.h`. Implement firmware-level audio PHY enforcement per §12.7: before enabling PTT transmission, verify `audio_channel_slot.phy_profile_index` references a profile with `spreading_factor ≤ 8`; if not, send `error="audio_slot_phy_too_slow"` on the ADMIN BLE characteristic and refuse to transmit. Verify LoRa loopback between two AirTAK devices at Profile 1 parameters. | ⊣ 7.1 |
| **7.3** | Firmware audio pipeline | Implement the Audio Codec FreeRTOS task (priority LOW/2, 3 KB stack, per §9.3). Pipeline: LoRa RX (AIRTAK_AUDIO packet) → Audio task buffer → BLE `MESH_RX` notify to phone. `AIRTAK_AUDIO` packets (portnum 263) transmitted with `hop_limit=1` always; the Router must never relay them regardless of received `hop_limit`. AudioPacket fields implemented per §14.3: `from_node_id`, `sequence_num`, `session_id`, `codec2_mode_bps`, `frame_count`, `audio_data`, `end_of_transmission`, `fec_prev_audio_data`. FEC session boundary rule enforced: `fec_prev_audio_data` MUST be empty on the first packet of each PTT session. | ⊣ 7.2 |
| **7.4** | Android audio pipeline | Add `RECORD_AUDIO` to `AndroidManifest.xml` with `android:required="false"`. Implement `data/audio/AudioRepository` with a dedicated coroutine dispatcher. Transmit path: `AudioRecord` at 8 kHz PCM → Codec2 encode (JNI, `android/app/src/main/kotlin/com/airtak/data/audio/`) → BLE `MESH_TX` via framing. Receive path: BLE `MESH_RX` AIRTAK_AUDIO packet → Codec2 decode (JNI) → 3-packet jitter buffer (480 ms window) → resequence → `AudioTrack` at 8 kHz PCM. PLC mode on two or more consecutive packet losses (comfort noise). FEC single-packet recovery: on `sequence_num` gap of exactly 1, use `fec_prev_audio_data` of the next received packet to reconstruct the missing audio. Session boundary rule: if `fec_prev_audio_data` is empty on the received packet, do not attempt FEC across sessions; apply PLC for any gap. Codec2 JNI library compiled for `arm64-v8a` and `x86_64`. | ⊣ 7.3, Phase 6 complete |
| **7.5** | PTT button in Android app | Add the PTT button to the bottom navigation bar (visible only when `AudioConfig.ptt_enabled=true`). Hold-to-transmit: button pressed → begin AudioRecord and BLE MESH_TX pipeline; button released → send final `AudioPacket` with `end_of_transmission=true`; increment `session_id`. Button state label: "TRANSMITTING" (red) while active, "LISTENING" otherwise. `RECORD_AUDIO` runtime permission requested on first PTT button hold, with rationale string: "AirTAK needs microphone access to transmit voice over the mesh." If permission denied, disable PTT button with an explanation; no other app functionality affected. Simultaneous PTT from multiple users is not prevented; users hear both transmissions overlaid. | ⊣ 7.4 |
| **7.6** | Time-multiplexing validation | With one AirTAK device having slot 0 (Profile 0) and slot 2 (Profile 1) active simultaneously: transmit 100 AIRTAK_PLAYER packets at 1 Hz on slot 0 while conducting a 5-second PTT burst on slot 2. Count AIRTAK_PLAYER packets received by a second device on slot 0. Measured Profile 0 packet loss during PTT burst must be ≤ 15%. Verify that application-layer retry (§8.9) recovers ACKNOWLEDGED packets missed during the audio window. Document measured loss rate. If loss exceeds 15%, reduce audio frame batch size or add Long/Mesh dwell windows between audio bursts and re-test. | ⊣ 7.5 |
| **7.7** | PTT audio field test | Voice exchange between ≥ 3 AirTAK devices at typical airsoft field distances (50 m and 150 m). Both channels active simultaneously (Profile 0 mesh channel and Profile 1 audio channel). Audio must be intelligible at both distances. Verify game data (AIRTAK_PLAYER packets) continues to flow on Profile 0 during PTT. Document result. | ⊣ 7.6 |
| **7.8** | FEC single-packet-loss recovery | Inject one dropped `AudioPacket` mid-PTT session (via instrumented firmware or controlled packet loss). Verify the receiver reconstructs the missing audio from `fec_prev_audio_data` of the next received packet without an audible gap (subjective listening test). Document that no PLC is invoked for a single-packet loss when FEC recovery is available. | ⊣ 7.5 |
| **7.9** | EU duty-cycle display in Android app | Implement duty-cycle calculation in the AudioConfig section of Settings. Formula: `DC = audio_ToA_ms / (frame_count × 20 ms)`, where `audio_ToA_ms = ~84 ms` (with FEC, Profile 1, 92-byte packet) or `~59 ms` (without FEC, 66-byte packet). Display: calculated DC as a percentage next to `frame_count` slider. Color coding: green = compliant for sub-band g1 (≤ 10 %); amber = non-compliant for sub-band g1 but possible on sub-band g consideration is moot; red = always shown for sub-band g (868 MHz, 1 % limit) since 1300 bps is never feasible there. Specifically: EU868 with `frame_count=8` → ~52 % → amber/red non-compliant; EU868 with `frame_count=42` → ~10 % → green for sub-band g1; EU868 with `frame_count=50` → ~8.4 % → green for sub-band g1, still red for sub-band g. Non-EU regions display DC for informational purposes only with no color coding. | ⊣ 7.5 |
| **7.10** | FEC session boundary correctness | Test: transmit PTT session 1 (multiple packets, normal FEC). Transmit PTT session 2 immediately after. Verify the first packet of session 2 has `fec_prev_audio_data = empty`. Inject a scenario where `end_of_transmission` of session 1 is lost. Verify the receiver uses `session_id` change to detect the new session boundary and applies PLC for any gap at that boundary rather than attempting cross-session FEC. Confirm no corrupted audio artefacts from session bleed. | ⊣ 7.8 |

### Phase 7 Exit Tests

All firmware audio integration tests per §19.1 audio section: Codec2 round-trip SNR ≥ 15 dB, PTT loopback audible, time-multiplexing loss ≤ 15%. All Android unit tests for audio path. PTT field test documented (7.7). FEC recovery test documented (7.8). Session boundary test documented (7.10). EU duty-cycle display verified for all specified `frame_count` values (7.9).

---

## Toolchain Deliverables

The following tooling deliverables are not tied to a specific phase but have dependencies noted. They must be complete before any code generation or flashing workflow is required.

| ID | Deliverable | Description | Cross-dependencies |
|----|-------------|-------------|-------------------|
| **T.1** | Proto definitions — `proto/airtak/config.proto` | Implement the full `airtak/config.proto` schema from §14.2: `ConfigGroup` enum, `DeviceConfig`, `LoraRegion`, `PhyProfile`, `LoraConfig`, `ChannelRole`, `ChannelSlot`, `AudioConfig`, `ChannelConfig`, `PositionConfig`, `PowerConfig`, `GameConfig`, `SecurityConfig`. Vendor Meshtastic protos in `proto/meshtastic/` at a pinned commit; record SHA in `proto/meshtastic/VERSION`. | ⊣ Before 2.1 |
| **T.2** | Proto definitions — `proto/airtak/game.proto` | Implement the full `airtak/game.proto` schema from §14.3: `PlayerStatus`, `PlayerStatePacket`, `MarkerType`, `TeamControl`, `Scope`, `LatLng`, `TacticalMarker`, `ModerationAction`, `ModerationPacket`, `AirTAKAck`, `PositionRequestPacket`, `AudioPacket`. | ⊣ Before 2.11 |
| **T.3** | Proto definitions — `proto/airtak/ble.proto` | Implement the full `airtak/ble.proto` schema from §14.4: `ConfigPayload`, `ConfigAck`, `DeviceStatus`, `GpsStatus`, `RadioStatus`, `AdminCommand`, `AdminRequest`, `AdminResponse`. | ⊣ Before 1.5 |
| **T.4** | `tools/proto_gen.ps1` | PowerShell script that invokes `protoc` with the nanopb plugin on all `proto/airtak/` files, outputs `.pb.h`/`.pb.c` to `firmware/src/proto/`, and invokes `protoc` with the Kotlin plugin, outputs Kotlin sources to `android/app/src/main/kotlin/com/airtak/proto/`. Does not touch vendored Meshtastic protos. Generated files are committed to the repository. | ⊣ T.1, T.2, T.3 |
| **T.5** | `tools/flash.ps1` | PowerShell script that runs `pio run -e t_echo_plus -t upload` with the correct PlatformIO environment and reports success/failure with device connection status. | ⊣ 1.1 |
| **T.6** | `tools/tile_fetch.py` | Python script that accepts a bounding box and zoom range and downloads MBTiles from a configurable tile server URL into a local file for sideloading into the Android app. | ⊣ 3.1 (to define tile storage format) |
| **T.7** | Documentation — `docs/radio-protocol.md` | Wire format reference with annotated byte diagrams covering §8.2 (OTA packet format), §8.3 (payload), §8.4 (port numbers), §8.6 (channel hash). Must reflect the v1.4 corrections: Flags [2:0] = `hop_start`, not reserved. | ⊣ Phase 2 complete |
| **T.8** | Documentation — `docs/ble-service.md` | GATT service UUID table, all five characteristic UUIDs, security requirements, framing protocol description, config protocol flow, admin command list. Derived from §10. | ⊣ Phase 3 complete |
| **T.9** | Documentation — `docs/tactical-protocol.md` | Tactical coordination packet flow diagrams covering unit hierarchy, scoped information sharing, tactical marker lifecycle (create/update/delete/re-broadcast), AIRTAK_PLAYER status flow, and AIRTAK_ACK delivery. | ⊣ Phase 5 complete |

---

## Cross-Dependency Summary

The following table lists every significant inter-phase and intra-phase dependency for quick reference. All phases are sequential; Phase N may not begin until Phase N−1 is fully complete and tested, with the sole exception that tooling deliverables (T.1–T.3) must precede their respective consumers as noted.

| Deliverable | Blocked by |
|-------------|-----------|
| All of Phase 2 | All of Phase 1 |
| All of Phase 3 | All of Phase 2 (device required for BLE integration tests) |
| All of Phase 4 | All of Phase 3 |
| All of Phase 5 | All of Phase 4 |
| All of Phase 6 | All of Phase 5 |
| All of Phase 7 | All of Phase 6 |
| 2.1 (AES crypto) | T.1 (config.proto) |
| 2.2 (NODEINFO) | 2.1 |
| 2.3 (TEXT_MESSAGE) | 2.1, 2.2 |
| 2.4 (POSITION_APP TX) | 2.1, 1.3 |
| 2.6 (SNR relay) | 2.5 |
| 2.7 (multi-channel) | 2.5 |
| 2.8 (hot-reload) | 2.7, 1.6 |
| 2.9 (per-type hop limits) | 2.5 |
| 2.10 (heard-by) | 2.6 |
| 2.11 (ACKNOWLEDGED delivery) | 2.5, 2.7, T.2 |
| 2.12 (POSITION_REQUEST) | 2.4, 2.5 |
| 3.3 (DEVICE_STATUS display) | 3.2 |
| 3.4 (Settings screen) | 3.2, 3.3 |
| 3.5 (Messages screen) | 3.2 |
| 3.6 (MapLibre + own position) | 3.3 |
| 3.7 (background BLE service) | 3.2 |
| 4.1 (remote node positions) | Phase 3 complete |
| 4.2 (AIRTAK_PLAYER) | 4.1 |
| 4.3 (Roster screen) | 4.2 |
| 4.4 (tile downloader) | 3.6 |
| 4.5 (local waypoints) | 3.6 |
| 5.1 (unit hierarchy config) | Phase 4 complete |
| 5.2 (simplified player status) | 5.1, 4.2 |
| 5.3 (tactical marker protocol) | 5.1, T.2 |
| 5.4 (marker drawing tools) | 5.3 |
| 5.5 (scoped information sharing) | 5.1, 5.3 |
| 5.6 (roster screen) | 5.2, 4.1 |
| 5.7 (markers screen) | 5.3, 5.4 |
| 5.8 (Active/Ended toggle) | 5.2, 5.3 |
| 5.9 (marker re-broadcast) | 5.3 |
| 5.10 (vote-kick moderation) | 5.1, 5.6 |
| 6.1 (Nordic DFU) | Phase 5 complete |
| 6.2 (GPS duty cycle) | Phase 5 complete |
| 6.3 (battery life validation) | 6.2 |
| 6.4 (field acceptance test) | 6.3, Phase 5 complete |
| 6.5 (Meshtastic interop field test) | 6.4 |
| 6.6 (Android background 8 h) | 6.4 |
| 6.7 (fully offline test) | 6.4 |
| 6.8 (daylight readability) | 6.4 |
| 6.9 (per-slot PHY field test) | 6.4 |
| 7.1 (Codec2 firmware) | Phase 6 complete |
| 7.2 (Short/Speed PHY slot) | 7.1 |
| 7.3 (firmware audio pipeline) | 7.2 |
| 7.4 (Android audio pipeline) | 7.3, Phase 6 complete |
| 7.5 (PTT button) | 7.4 |
| 7.6 (time-multiplexing validation) | 7.5 |
| 7.7 (PTT field test) | 7.6 |
| 7.8 (FEC single-packet loss) | 7.5 |
| 7.9 (EU duty-cycle display) | 7.5 |
| 7.10 (FEC session boundary) | 7.8 |
| T.1–T.3 (proto definitions) | Before first use |
| T.4 (proto_gen.ps1) | T.1, T.2, T.3 |
| T.5 (flash.ps1) | 1.1 |
| T.6 (tile_fetch.py) | 3.1 |
| T.7 (radio-protocol.md) | Phase 2 complete |
| T.8 (ble-service.md) | Phase 3 complete |
| T.9 (tactical-protocol.md) | Phase 5 complete |

---

## Explicit Exclusions (Out of Scope)

The following will not be implemented and must not be added as deliverables without a new versioned planning amendment:

- iOS support
- Meshtastic Android/iOS app compatibility (beyond basic mesh membership)
- ATAK plugin API or Cursor-on-Target format
- Web-based admin or configuration interface
- Any server-side component, user accounts, or telemetry
- Multi-region automatic frequency switching
- Frequency hopping (FHSS)
- Support for hardware other than LilyGo T-Echo Plus as a firmware target
- Meshtastic store-and-forward, MQTT bridge, serial module, rangetest module, or canned-messages module

---

*End of AirTAK Roadmap*
*This document is immutable after acceptance. New deliverables or changes to existing ones require a new versioned roadmap amendment, not edits here.*
