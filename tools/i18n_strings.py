#!/usr/bin/env python3
"""Issue #11: append the externalised UI strings to values/ and values-de/.

The table below is the durable replacement for the scratch TSV the translation pass
used to live in (/tmp/airtak-i18n/hardcoded-de.tsv, gone on reboot). One row per key:
key, English, German, translatable.

German is translated in context, not word for word: several short English labels are
too long for a narrow button once rendered literally, so those rows carry the shorter
form an operator would actually read on a 6-inch screen.
"""
import pathlib
import sys

ROWS = [
    ("help_back_to_guide", "Back to help", "Zur Hilfe", True),
    ("link_failed", "LINK FAIL", "VERB.-FEHLER", True),
    ("markers_creator_you", "You", "Du", True),
    ("gps_permission_missing", "Location permission not granted — allow location to see your position", "Standortberechtigung fehlt — erlaube den Standortzugriff, um deine Position zu sehen", True),
    ("gps_location_off_connect", "Device location is off — turn it on, or connect a device", "Handy-Standort ist ausgeschaltet — aktiviere ihn oder verbinde ein Gerät", True),
    ("gps_provider_missing", "No location provider available — no GPS or network location on this device", "Kein Standortanbieter verfügbar — weder GPS noch Netzwerkstandort auf diesem Handy", True),
    ("gps_updates_stopped", "Location updates not running — reopen the map or connect a device", "Standortaktualisierung läuft nicht — öffne die Karte erneut oder verbinde ein Gerät", True),
    ("gps_location_off", "Device location is off — turn it on to update your position", "Handy-Standort ist ausgeschaltet — aktiviere ihn, um deine Position zu aktualisieren", True),
    ("gps_waiting_fix", "No position yet — waiting for a GPS fix", "Noch keine Position — wartet auf GPS-Empfang", True),
    ("gps_waiting_position", "No position yet — waiting for a location or a connected device", "Noch keine Position — wartet auf Standortdaten oder ein verbundenes Gerät", True),
    ("fw_failed_explanation", "The firmware update failed. Check the connection and firmware file, then try again. Technical details are shown in the diagnostic log below.", "Das Firmware-Update ist fehlgeschlagen. Prüfe Verbindung und Firmware-Datei und versuche es erneut. Technische Details stehen unten im Diagnoseprotokoll.", True),
    ("route_profile_all", "All routes", "Alle Routen", True),
    ("route_profile_car_free", "Car, no tolls", "Auto, mautfrei", True),
    ("route_profile_car", "Car", "Auto", True),
    ("route_profile_dummy", "Diagnostic profile (dummy)", "Diagnoseprofil (dummy)", True),
    ("route_profile_bike_quiet", "Road bike, very low traffic", "Rennrad, sehr verkehrsarm", True),
    ("route_profile_bike", "Road bike", "Rennrad", True),
    ("route_profile_gravel", "Gravel bike", "Gravelbike", True),
    ("route_profile_hiking", "Mountain hiking", "Bergwandern", True),
    ("route_profile_moped", "Moped", "Moped", True),
    ("route_profile_mtb", "Mountain bike", "Mountainbike", True),
    ("route_profile_rail", "Rail", "Bahn", True),
    ("route_profile_river", "River", "Fluss", True),
    ("route_profile_shortest", "Shortest route", "Kürzeste Route", True),
    ("route_profile_skating", "Skating", "Inlineskaten", True),
    ("route_profile_softaccess", "Bike access check (softaccess)", "Rad-Zugangsprüfung (softaccess)", True),
    ("route_profile_trekking", "Trekking bike", "Tourenrad", True),
    ("route_profile_recumbent", "Fast recumbent bike", "Schnelles Liegerad", True),
    ("route_profile_velomobile", "Fast velomobile", "Schnelles Velomobil", True),
    ("routing_no_route", "No route found between points", "Keine Route zwischen den Punkten gefunden", True),
    ("routing_no_data", "No routing data downloaded", "Keine Routingdaten heruntergeladen", True),
    ("routing_failed", "Routing failed — check the routing data and start/end points", "Routenberechnung fehlgeschlagen — prüfe Routingdaten sowie Start- und Zielpunkt", True),
    ("routing_download_failed", "Downloaded 0 of %1$d segments — check network", "0 von %1$d Segmenten heruntergeladen — prüfe die Netzwerkverbindung", True),
    ("tiles_download_cancelled", "Download cancelled.", "Download abgebrochen.", True),
    ("tiles_server_missing", "No tile server configured. Set one in Settings → App. AirTAK ships no default provider, so bulk downloading is always a deliberate choice.", "Kein Kartenserver eingerichtet. Lege ihn unter Einstellungen → App fest. AirTAK gibt keinen Anbieter vor, damit Offline-Downloads immer bewusst erfolgen.", True),
    ("tiles_bulk_prohibited", "Bulk downloads from %1$s are prohibited. Choose a provider that permits offline use.", "Massendownloads von %1$s sind nicht erlaubt. Wähle einen Anbieter, der Offline-Nutzung gestattet.", True),
    ("tiles_server_invalid", "Invalid tile server URL. Use http or https and a valid host without embedded login credentials.", "Ungültige Kartenserver-URL. Verwende http oder https und einen gültigen Hostnamen ohne eingebettete Anmeldedaten.", True),
    ("tiles_download_failed", "Tile download failed. Check the network, server URL and available storage.", "Karten-Download fehlgeschlagen. Prüfe Netzwerk, Server-URL und freien Speicherplatz.", True),
    ("tiles_download_limit", "Selected area has %1$d tiles; limit: %2$d. Narrow the map or zoom range.", "Der Bereich umfasst %1$d Kacheln; erlaubt sind höchstens %2$d. Verkleinere den Kartenbereich oder die Zoomspanne.", True),
    ("tiles_no_store", "The provider forbids offline storage (Cache-Control: no-store). Download stopped; existing tiles are untouched.", "Der Anbieter verbietet Offline-Speicherung (Cache-Control: no-store). Der Download wurde gestoppt; vorhandene Kacheln bleiben erhalten.", True),
    ("tiles_download_stopped", "Download stopped: %1$s", "Download gestoppt: %1$s", True),
    ("tiles_incomplete", "Incomplete: %1$d missing, %2$d failed, %3$d stored, %4$d already present. The partial area is usable offline.", "Unvollständig: %1$d fehlen, %2$d fehlgeschlagen, %3$d gespeichert, %4$d bereits vorhanden. Der Teilbereich ist offline nutzbar.", True),
    ("hud_gps_fix", "GPS fix", "GPS-Fix", True),
    ("hud_gps_waiting", "GPS…", "GPS…", True),
    ("map_routing_second_corner", "Routing region: second corner", "Routingbereich: zweite Ecke", True),
    ("map_routing_first_corner", "Routing region: first corner", "Routingbereich: erste Ecke", True),
    ("map_segments_progress", "Segments %1$d/%2$d", "Segmente %1$d/%2$d", True),
    ("map_no_segments", "No segments downloaded", "Keine Segmente heruntergeladen", True),
    ("map_segments_ready", "Routing data ready (%1$d segments)", "Routingdaten bereit (%1$d Segmente)", True),
    ("map_route_start", "Route: tap start point", "Route: Startpunkt antippen", True),
    ("map_route_end", "Route: tap end point", "Route: Zielpunkt antippen", True),
    ("map_route_calculating", "Calculating route…", "Route wird berechnet…", True),
    ("map_tiles_count", "Zoom %1$d–%2$d · %3$d tiles", "Zoom %1$d–%2$d · %3$d Kacheln", True),
    ("map_tile_url_invalid", "URL must include {z}, {x}, and {y}.", "Die URL muss {z}, {x} und {y} enthalten.", True),
    ("map_region_too_large", "Selected area is too large; narrow the region or zoom range.", "Der gewählte Bereich ist zu groß. Verkleinere den Bereich oder die Zoomspanne.", True),
    ("map_routing_download_hint", "Download BRouter .rd5 segment files for this area to enable on-road/trail route calculation.", "Lade BRouter-Segmentdateien (.rd5) für diesen Bereich herunter, um Routen auf Straßen und Wegen zu berechnen.", True),
    ("map_routing_fallback_hint", "Without segments → fallback to straight-line route.", "Ohne Segmente → Route als Luftlinie.", True),
    ("map_changes_broadcast_hint", "Changes are broadcast to the mesh and visible to other players.", "Änderungen werden im Mesh verteilt und sind für andere Spieler sichtbar.", True),
    ("map_points_captured", "Points captured: %1$d", "Erfasste Punkte: %1$d", True),
    ("map_marker_creator", "Creator: %1$s", "Erstellt von: %1$s", True),
    ("map_creator_you", "you", "dir", True),
    ("map_tile_second_corner", "Tile region: second corner", "Kartenbereich: zweite Ecke", True),
    ("map_tile_first_corner", "Tile region: first corner", "Kartenbereich: erste Ecke", True),
    ("map_field_tiles_zoom", "Field tiles to z%1$d", "Detailkarten bis Zoom %1$d", True),
    ("map_offline_overview", "Offline overview", "Offline-Übersicht", True),
    ("map_tiles_ready", "Tiles ready", "Kartenkacheln bereit", True),
    ("map_status_active_description", "In play, full position broadcasting", "Im Spiel, normale Positionsübertragung", True),
    ("map_status_inactive_description", "Paused, reduced broadcasting", "Pausiert, reduzierte Positionsübertragung", True),
    ("map_status_hit_description", "Hit — waiting to respawn", "Getroffen — wartet auf Wiedereinstieg", True),
    ("map_status_respawning_description", "Returning to play", "Kehrt ins Spiel zurück", True),
    ("map_status_eliminated_description", "Out for the remainder", "Für den Rest des Spiels ausgeschieden", True),
    ("fw_step_idle", "Not started", "Noch nicht gestartet", True),
    ("fw_step_parse", "Parsing DFU package…", "DFU-Paket wird gelesen…", True),
    ("fw_step_init", "Uploading init packet…", "Initialisierungspaket wird übertragen…", True),
    ("fw_step_request_bootloader", "Requesting bootloader entry…", "Bootloader-Modus wird angefordert…", True),
    ("fw_step_prepare", "Preparing DFU package…", "DFU-Paket wird vorbereitet…", True),
    ("fw_step_connect", "Connecting to bootloader…", "Verbindung zum Bootloader wird hergestellt…", True),
    ("fw_step_enable", "Enabling DFU mode…", "DFU-Modus wird aktiviert…", True),
    ("fw_step_detect", "Detecting device…", "Gerät wird erkannt…", True),
    ("fw_step_transfer", "Transferring firmware…", "Firmware wird übertragen…", True),
    ("fw_step_finalize", "Finalizing…", "Wird abgeschlossen…", True),
    ("fw_step_start_ota", "Starting OTA…", "OTA-Update wird gestartet…", True),
    ("fw_step_finalize_ota", "Finalizing OTA…", "OTA-Update wird abgeschlossen…", True),
    # Remaining multi-line/custom-composable UI strings (translation coverage audit).
    ("settings_restart_explanation", "Applying this group restarts the LoRa radio for ~300 ms. The BLE link is preserved.", "Beim Übernehmen wird der LoRa-Funk für etwa 300 ms neu gestartet. Die BLE-Verbindung bleibt bestehen.", True),
    ("settings_units_explanation", "All fields are optional and self-assigned. Each unit can be linked to a dedicated channel for scoped communications.", "Alle Felder sind freiwillig und frei wählbar. Jede Einheit kann für die interne Kommunikation einen eigenen Kanal verwenden.", True),
    ("settings_hit_toggle", "Device button = HIT toggle", "Gerätetaste schaltet Trefferstatus um", True),
    ("settings_hit_toggle_hint", "When enabled, long-press the device button to toggle HIT status.", "Wenn aktiviert, schaltet langes Drücken der Gerätetaste den Trefferstatus um.", True),
    ("settings_profile_number", "Profile %1$d", "Profil %1$d", True),
    ("settings_cr_light", "Light — faster (4/5)", "Gering — schneller (4/5)", True),
    ("settings_cr_moderate", "Moderate (4/6)", "Mittel (4/6)", True),
    ("settings_cr_heavy", "Heavy (4/7)", "Stark (4/7)", True),
    ("settings_cr_maximum", "Maximum — slowest, most reliable (4/8)", "Maximal — am langsamsten, am zuverlässigsten (4/8)", True),
    ("settings_channel_setup_title", "Setting up group channels", "Gruppenkanäle einrichten", True),
    ("settings_channel_setup_help", "Slots 0–2 are fixed by AirTAK; slots 3–7 are free for unit-scoped traffic (squad, fireteam, platoon, company). Pick a recognisable name and a shared password — every device in that unit must use the same slot index, name, and password. Link a slot to a unit level from Settings → Player.", "Slots 0–2 sind von AirTAK vorgegeben; Slots 3–7 sind für die Kommunikation innerhalb von Einheiten frei. Wähle einen eindeutigen Namen und ein gemeinsames Passwort. Alle Geräte der Einheit müssen denselben Slot, Namen und dasselbe Passwort verwenden. Unter Einstellungen → Spieler ordnest du einer Einheit einen Slot zu.", True),
    ("settings_slot_layout", "Slot layout:", "Slot-Belegung:", True),
    ("settings_slot_layout_help", "Slot 0 — AirTAK (primary, Meshtastic-compatible) — all players\\nSlot 1 — Tactical (AirTAK-wide secondary) — all players\\nSlot 2 — Audio (reserved for future PTT)\\nSlot 3+ — Unit channels (squad, platoon, company)", "Slot 0 — AirTAK (primär, Meshtastic-kompatibel) — alle Spieler\\nSlot 1 — Taktik (AirTAK-weiter Zweitkanal) — alle Spieler\\nSlot 2 — Audio (für künftiges PTT reserviert)\\nAb Slot 3 — Einheitenkanäle (Gruppe, Zug, Kompanie)", True),
    ("settings_channel_tabs_hint", "Slots appear as tabs in the Messages screen once enabled.", "Aktivierte Slots erscheinen als Reiter im Funkbereich.", True),
    ("settings_multiple_phy_hint", "Active slots use multiple PHY profiles. Keep teammates on the same profile unless intentionally isolating traffic.", "Aktive Slots verwenden unterschiedliche PHY-Profile. Teammitglieder sollten dasselbe Profil nutzen, außer der Funkverkehr soll bewusst getrennt werden.", True),
    ("settings_new_channel_hint", "↓ New channel for your unit — set a password below and tap Apply.", "↓ Neuer Kanal für deine Einheit — unten ein Passwort festlegen und Übernehmen antippen.", True),
    ("settings_hide_password", "Hide password", "Passwort verbergen", True),
    ("settings_reveal_password", "Reveal password", "Passwort anzeigen", True),
    ("settings_open_channel_hint", "Open channel — anyone on the same frequency can send and receive.", "Offener Kanal — jeder auf derselben Frequenz kann senden und empfangen.", True),
    ("settings_encrypted_channel_hint", "Encrypted — only devices sharing this password can communicate here.", "Verschlüsselt — nur Geräte mit demselben Passwort können hier kommunizieren.", True),
    ("settings_precision_hidden", "Hidden — your position is not shared on this channel", "Verborgen — deine Position wird auf diesem Kanal nicht geteilt", True),
    ("settings_precision_city", "City level (~100 km) · %1$d bits", "Stadt (~100 km) · %1$d Bit", True),
    ("settings_precision_neighbourhood", "Neighbourhood (~5 km) · %1$d bits", "Stadtviertel (~5 km) · %1$d Bit", True),
    ("settings_precision_street", "Street level (~500 m) · %1$d bits", "Straße (~500 m) · %1$d Bit", True),
    ("settings_precision_building", "Building level (~50 m) · %1$d bits", "Gebäude (~50 m) · %1$d Bit", True),
    ("settings_precision_full", "Full precision (~1 m) · %1$d bits", "Volle Genauigkeit (~1 m) · %1$d Bit", True),
    ("settings_channel_send", "Send on this channel", "Auf diesem Kanal senden", True),
    ("settings_channel_receive", "Receive on this channel", "Auf diesem Kanal empfangen", True),
    ("settings_smart_beaconing", "Smart beaconing", "Intelligente Positionsmeldungen", True),
    ("settings_phone_gps", "Smartphone GPS", "Smartphone-GPS", True),
    ("settings_device_gps", "Radio GPS", "GPS des Funkgeräts", True),
    ("settings_gps_source_unknown", "Unknown source — please choose", "Unbekannte Quelle — bitte wählen", True),
    ("settings_gps_source_hint", "Choose where your position comes from. Tap Apply to save this choice to the radio.", "Wähle, woher deine Position kommt. Mit Übernehmen speicherst du die Auswahl am Funkgerät.", True),
    ("settings_position_channel_hint", "Select which channel carries your GPS position. Use an encrypted channel shared with your team — avoid unencrypted channels to prevent enemy tracking.", "Wähle den Kanal für deine GPS-Position. Verwende einen verschlüsselten Teamkanal — auf offenen Kanälen können Gegner deine Position mitlesen.", True),
    ("settings_gps_duty_cycle", "GPS duty cycle (Phase 6+)", "GPS-Energiesparbetrieb (ab Phase 6)", True),
    ("settings_no_bans", "No active bans. When 3 distinct players vote to kick someone, they appear here.", "Keine aktiven Sperren. Wenn drei verschiedene Spieler für einen Ausschluss stimmen, erscheint die Sperre hier.", True),
    ("settings_node_number", "Node %1$s", "Knoten %1$s", True),
    ("settings_banned", "BANNED", "GESPERRT", True),
    ("settings_ban_expires", "Expires in %1$dh %2$dm", "Läuft in %1$d Std. %2$d Min. ab", True),
    ("settings_invalid_qr", "The scanned QR code does not contain a valid AirTAK config.", "Der gescannte QR-Code enthält keine gültige AirTAK-Konfiguration.", True),
    ("settings_import_lora_summary", "LoRa: %1$s, TX %2$d dBm, %3$d PHY profiles", "LoRa: %1$s, Sendeleistung %2$d dBm, %3$d PHY-Profile", True),
    ("settings_import_channels_summary", "Channels: %1$d active, %2$d with PSK", "Kanäle: %1$d aktiv, %2$d mit PSK", True),
    ("settings_import_company", "Company: %1$s", "Kompanie: %1$s", True),
    ("settings_import_platoon", "Platoon: %1$s", "Zug: %1$s", True),
    ("settings_import_squad", "Squad: %1$s", "Gruppe: %1$s", True),
    ("settings_import_fireteam", "Fireteam: %1$s", "Trupp: %1$s", True),
    ("settings_import_done", "Config applied! LoRa and channel settings have been pushed to the device. A radio restart may be in progress.", "Konfiguration übernommen! LoRa- und Kanaleinstellungen wurden an das Gerät gesendet. Der Funk wird möglicherweise gerade neu gestartet.", True),
    ("settings_info_firmware", "Firmware", "Firmware", True),
    ("settings_info_board", "Board", "Platine", True),
    ("settings_info_node_id", "Node ID", "Knoten-ID", True),
    ("settings_info_bt_mac", "BT MAC", "Bluetooth-MAC", True),
    ("settings_info_uptime", "Uptime", "Laufzeit", True),
    ("settings_info_mesh_nodes", "Mesh nodes", "Mesh-Knoten", True),
    ("settings_channel_none", "Channel: none", "Kanal: keiner", True),
    ("settings_slot_unconfigured", "Slot %1$d — not yet set up", "Slot %1$d — noch nicht eingerichtet", True),
    ("settings_channel_slot_name", "%1$s (slot %2$d)", "%1$s (Slot %2$d)", True),
    ("settings_none", "None", "Keine", True),
    ("settings_global_channels_hint", "Use global channels for this unit level.", "Globale Kanäle für diese Einheit verwenden.", True),
    ("settings_encrypted", "Encrypted", "Verschlüsselt", True),
    ("settings_open", "Open (no PSK)", "Offen (ohne PSK)", True),
    ("settings_channel_claimed", "%1$s · already linked to %2$s", "%1$s · bereits mit %2$s verknüpft", True),
    ("settings_setup_unit_channel", "Set up a new channel for %1$s…", "Neuen Kanal für %1$s einrichten…", True),
    ("settings_setup_slot_hint", "Uses slot %1$d. Set the password next.", "Verwendet Slot %1$d. Als Nächstes legst du das Passwort fest.", True),
    ("settings_no_free_slots", "No free slots — open Channels…", "Keine freien Slots — Kanäle öffnen…", True),
    ("settings_manage_channels", "Manage channels…", "Kanäle verwalten…", True),
    ("settings_mesh_channel_hint", "Mesh-wide / Meshtastic interop. All players share this channel.", "Mesh-weit und Meshtastic-kompatibel. Alle Spieler teilen diesen Kanal.", True),
    ("settings_tactical_channel_hint", "AirTAK-wide tactical traffic. All players share this channel.", "AirTAK-weiter Taktikfunk. Alle Spieler teilen diesen Kanal.", True),
    ("settings_audio_reserved_hint", "Reserved for PTT audio (Phase 7).", "Für PTT-Audio reserviert (Phase 7).", True),
    ("settings_theme_system", "System", "System", True),
    ("settings_theme_dark", "Dark", "Dunkel", True),
    ("settings_theme_light", "Light", "Hell", True),
    ("settings_psk_even", "Hex PSK must have an even number of digits.", "Ein Hex-PSK muss eine gerade Anzahl an Zeichen haben.", True),
    ("settings_psk_hex", "Hex PSK contains a non-hex character.", "Der Hex-PSK enthält ein ungültiges Hex-Zeichen.", True),
    ("settings_psk_size", "PSK must be 32 bytes or fewer.", "Der PSK darf höchstens 32 Byte lang sein.", True),
    # ── Shared actions ───────────────────────────────────────────────────────
    # These replaced literals in narrow buttons. `action_apply`, `action_revert`,
    # `action_retry`, `action_cancel` and `action_confirm` already existed.
    ("action_close", "Close", "Schließen", True),
    ("action_save", "Save", "Speichern", True),
    ("action_delete", "Delete", "Löschen", True),
    ("action_send", "Send", "Senden", True),
    ("action_download", "Download", "Laden", True),
    ("action_done", "Done", "Fertig", True),
    ("action_back", "Back", "Zurück", True),
    ("action_clear", "Clear", "Leeren", True),
    ("action_undo", "Undo", "Rückgängig", True),
    ("action_commit", "Commit", "Übernehmen", True),
    ("action_import", "Import", "Importieren", True),
    ("action_replace", "Replace", "Ersetzen", True),
    ("action_remove", "Remove", "Entfernen", True),
    ("action_go", "Go", "Los", True),
    ("action_try_again", "Try again", "Nochmal versuchen", True),

    # ── Shared field labels ──────────────────────────────────────────────────
    ("common_label", "Label", "Bezeichnung", True),
    ("common_description_optional", "Description (optional)", "Beschreibung (optional)", True),
    ("common_color", "Color", "Farbe", True),
    ("common_visible_to", "Visible to", "Sichtbar für", True),
    ("common_controlling_team", "Controlling team", "Kontrolle durch", True),
    ("common_priority", "Priority", "Priorität", True),

    # ── Marker / scope / team / priority vocabulary ──────────────────────────
    # Read through ui/i18n/Labels.kt. Four screens each carried their own private
    # English copy of these words.
    ("marker_type_point", "Point", "Punkt", True),
    ("marker_type_note", "Note", "Notiz", True),
    ("marker_type_objective", "Objective", "Ziel", True),
    ("marker_type_route", "Route", "Route", True),
    ("marker_type_area", "Area", "Fläche", True),
    ("marker_type_unknown", "Marker", "Marker", True),
    ("scope_all", "All players", "Alle Spieler", True),
    ("scope_company", "Company", "Kompanie", True),
    ("scope_platoon", "Platoon", "Zug", True),
    ("scope_squad", "Squad", "Gruppe", True),
    ("scope_fireteam", "Fireteam", "Trupp", True),
    ("team_own", "Own", "Eigene", True),
    ("team_opposing", "Opposing", "Gegner", True),
    ("team_neutral", "Neutral", "Neutral", True),
    ("team_unknown", "Unknown", "Unbekannt", True),
    ("priority_info", "Info", "Info", True),
    ("priority_low", "Low", "Niedrig", True),
    ("priority_medium", "Medium", "Mittel", True),
    ("priority_high", "High", "Hoch", True),
    ("priority_critical", "Critical", "Kritisch", True),

    # ── Map: icon descriptions (read aloud by TalkBack) ──────────────────────
    ("map_cd_recenter", "Recenter", "Zentrieren", True),
    ("map_cd_search", "Search location", "Ort suchen", True),
    ("map_cd_add_marker", "Add marker", "Marker hinzufügen", True),
    ("map_cd_end_game", "End game", "Spiel beenden", True),
    ("map_cd_resume_game", "Resume game", "Spiel fortsetzen", True),
    ("map_cd_players", "Players", "Spieler", True),
    ("map_cd_waypoints", "Waypoints", "Wegpunkte", True),
    ("map_cd_mission_overlay", "Mission overlay", "Auftragsüberlagerung", True),
    ("map_cd_download_tiles", "Download tiles", "Kacheln laden", True),
    ("map_cd_download_routing", "Download routing data", "Routendaten laden", True),
    ("map_cd_plan_route", "Plan route", "Route planen", True),
    ("map_cd_rebroadcast_alignment", "Re-broadcast alignment", "Ausrichtung erneut senden", True),
    ("map_cd_nudge_up", "Nudge up", "Nach oben schieben", True),
    ("map_cd_nudge_left", "Nudge left", "Nach links schieben", True),
    ("map_cd_nudge_right", "Nudge right", "Nach rechts schieben", True),
    ("map_cd_nudge_down", "Nudge down", "Nach unten schieben", True),

    # ── Map: dialogs and controls ────────────────────────────────────────────
    ("map_waypoint_dialog_title", "Waypoint", "Wegpunkt", True),
    ("map_tile_region_title", "Tile Region", "Kachelbereich", True),
    ("map_tile_url_field", "Tile URL", "Kachel-URL", True),
    ("map_routing_region_title", "Routing Data Region", "Bereich für Routendaten", True),
    ("map_import_alignment_title", "Import alignment", "Ausrichtung importieren", True),
    ("map_no_alignments", "No alignments received yet.", "Noch keine Ausrichtungen empfangen.", True),
    ("map_import_alignment_hint",
     "Pick the alignment to import, then select the matching image file on your device.",
     "Wähle zuerst die Ausrichtung und dann die passende Bilddatei auf deinem Gerät.", True),
    ("map_search_title", "Search Location", "Ort suchen", True),
    ("map_search_placeholder", "Town, place, coordinates…", "Ort, Platz oder Koordinaten …", True),
    ("map_player_status_title", "Player Status", "Spielerstatus", True),
    # "%1$s" is the marker type. The English original interpolated a lowercased
    # type into "New %1$s"; German nouns carry grammatical gender, so a lowercase
    # suffix would read wrong for "Notiz". Both locales use the "Neu: Punkt" form.
    ("map_new_marker_title", "New: %1$s", "Neu: %1$s", True),
    ("map_edit_marker_title", "Edit: %1$s", "Bearbeiten: %1$s", True),
    ("map_detail_type", "Type: %1$s", "Typ: %1$s", True),
    ("map_detail_scope", "Scope: %1$s", "Sichtbarkeit: %1$s", True),
    ("map_detail_team", "Team: %1$s", "Partei: %1$s", True),
    ("map_detail_priority", "Priority: %1$s", "Priorität: %1$s", True),
    ("map_detail_distance", "Distance: %1$s", "Entfernung: %1$s", True),
    ("map_navigate", "Navigate", "Navigieren", True),
    ("map_mission_overlay_title", "Mission Overlay", "Auftragsüberlagerung", True),
    ("map_opacity", "Opacity", "Deckkraft", True),
    ("map_share_alignment", "Share alignment", "Ausrichtung teilen", True),
    ("map_nudge_corner", "Nudge %1$s", "%1$s schieben", True),
    ("map_points_placed", "%1$s: %2$d point(s) placed", "%1$s: %2$d Punkte gesetzt", True),
    ("map_draw_hint_single", "Tap on the map to place.", "Tippe auf die Karte, um zu setzen.", True),
    ("map_draw_hint_route",
     "Tap 2–%1$d points along the route, then Commit.",
     "Tippe 2–%1$d Punkte entlang der Route und dann Übernehmen.", True),
    ("map_draw_hint_area",
     "Tap 3–%1$d corners, then Commit (closes polygon).",
     "Tippe 3–%1$d Ecken und dann Übernehmen (schließt das Polygon).", True),
    ("map_tile_url_placeholder", "https://server/{z}/{x}/{y}.png", None, False),
    ("marker_check_glyph", "✓", None, False),
    ("corner_top_left", "TL", "OL", True),
    ("corner_top_right", "TR", "OR", True),
    ("corner_bottom_right", "BR", "UR", True),
    ("corner_bottom_left", "BL", "UL", True),

    # ── Not language content ────────────────────────────────────────────────
    # Kept in resources so a scan for Kotlin literals comes back clean, but these
    # are units, counters and format patterns, so they are marked non-translatable
    # rather than left as bare literals that look like missed work.
    ("percent_value", "%1$d%%", None, False),
    ("counter_value", "%1$d/%2$d", None, False),
    ("distance_meters", "%1$d m", None, False),
    ("distance_km", "%1$.1f km", None, False),

    # ── AppShell HUD ─────────────────────────────────────────────────────────
    ("hud_bat", "BAT", "Akku", True),
    ("hud_gps", "GPS", "GPS", True),
    ("hud_rssi", "RSSI", "RSSI", True),
    ("hud_dc", "DC", "DC", True),
    ("hud_satellites", "%1$d sat", "%1$d Sat", True),
    ("hud_fix", "Fix", "Fix", True),
    ("hud_dropped_relays", "drp:%1$d", "verw:%1$d", True),
    ("dc_ok", "OK", "OK", True),
    ("dc_caution", "CAUT", "WARN", True),
    ("dc_warning", "WARN", "KRIT", True),
    ("dc_critical", "CRIT", "AKUT", True),
    ("percent_value_f", "%1$.1f%%", None, False),
    ("metric_placeholder", "—", None, False),

    # ── Firmware update ──────────────────────────────────────────────────────
    ("fw_title", "Firmware Update", "Firmware-Aktualisierung", True),
    ("fw_subtitle",
     "Choose USB or Bluetooth, then select the firmware file. USB does not need a Bluetooth connection. Bluetooth requires AirTAK firmware already running on the device.",
     "Wähle USB oder Bluetooth und danach die Firmware-Datei. USB braucht keine Bluetooth-Verbindung. Für Bluetooth muss bereits AirTAK-Firmware auf dem Gerät laufen.", True),
    ("fw_via_ble", "Update via Bluetooth", "Update über Bluetooth", True),
    ("fw_via_usb", "Update via USB cable", "Update über USB-Kabel", True),
    ("fw_updated_title", "Firmware Updated", "Firmware aktualisiert", True),
    ("fw_updated_body",
     "The device will restart and reconnect shortly.",
     "Das Gerät startet neu und verbindet sich kurz darauf erneut.", True),
    ("fw_failed_title", "Update Failed", "Update fehlgeschlagen", True),
    ("fw_back_to_settings", "Back to Settings", "Zurück zur Konfiguration", True),
    ("fw_transferring", "Transferring firmware…", "Firmware wird übertragen …", True),
    ("fw_eta_remaining", "~%1$d s remaining", "noch ~%1$d s", True),
    ("fw_verifying", "Verifying firmware…", "Firmware wird geprüft …", True),
    ("fw_rebooting", "Rebooting device…", "Gerät startet neu …", True),
    ("fw_reconnect_note",
     "The device will reconnect in a few seconds.",
     "Das Gerät verbindet sich in wenigen Sekunden erneut.", True),
    ("fw_updated_to", "Updated to %1$s", "Aktualisiert auf %1$s", True),
    ("fw_debug_log", "Debug Log", "Protokoll", True),
    ("bytes_b", "%1$d B", None, False),
    ("bytes_kb", "%1$d KB", None, False),
    ("byte_pair", "%1$s / %2$s", None, False),

    # ── Pairing ──────────────────────────────────────────────────────────────
    ("pair_heading", "PAIR AIRTAK DEVICE", "AIRTAK-GERÄT KOPPELN", True),
    ("pair_heading_link", "DEVICE LINK", "VERBINDUNG", True),
    ("pair_current_device", "Current device", "Aktuelles Gerät", True),
    ("pair_connected_body",
     "Your AirTAK transponder is shown below. Disconnect temporarily drops the "
     "link but keeps the device remembered. Forget unpairs completely.",
     "Dein AirTAK-Transponder steht unten. „Trennen“ unterbricht die Verbindung, "
     "das Gerät bleibt gespeichert. „Entfernen“ löst die Kopplung vollständig.", True),
    ("action_disconnect", "Disconnect", "Trennen", True),
    ("pair_reconnect", "Reconnect", "Neu verbinden", True),
    ("pair_forget", "Forget", "Entfernen", True),
    ("pair_different_device", "Pair different device", "Anderes Gerät koppeln", True),
    ("pair_rescan", "Rescan", "Neu suchen", True),
    ("pair_discovered", "Discovered", "Gefunden", True),
    ("pair_scanning", "Scanning for AirTAK transponders…", "Suche AirTAK-Transponder …", True),
    ("pair_no_devices",
     "No devices yet. Bring your device within range and try again.",
     "Noch keine Geräte. Bring dein Gerät in Reichweite und versuche es erneut.", True),
    ("pair_status_linked", "LINKED", "VERBUNDEN", True),
    ("pair_status_linking", "LINKING…", "VERBINDE …", True),
    ("pair_status_reacquiring", "REACQUIRING…", "SUCHE ERNEUT …", True),
    ("pair_status_offline", "OFFLINE", "OFFLINE", True),
    ("pair_transponder", "AirTAK Transponder", "AirTAK-Transponder", True),
    ("pair_airtak_device", "AirTAK device", "AirTAK-Gerät", True),
    ("rssi_dbm", "%1$d dBm", None, False),

    # ── Roster / players ─────────────────────────────────────────────────────
    ("players_online_heading", "ONLINE", "ONLINE", True),
    ("players_filter", "FILTER", "FILTER", True),
    ("players_all", "ALL", "ALLE", True),
    ("players_search_hint", "Search callsign or unit…", "Rufzeichen oder Einheit suchen …", True),
    ("players_empty", "No mesh players heard yet.", "Noch keine Spieler im Mesh gehört.", True),
    ("players_vote_kick_title", "Vote-kick %1$s?", "%1$s per Abstimmung kicken?", True),
    ("players_vote_kick_body",
     "When 3 distinct players cast a vote-kick at the same scope, the target is "
     "hidden from your local UI for 24 h. This affects display only — the mesh "
     "keeps relaying their packets.",
     "Wenn drei verschiedene Spieler im selben Sichtbarkeitsbereich per Abstimmung "
     "kicken, ist das Ziel 24 Stunden in deiner App ausgeblendet. Das ist nur die "
     "Anzeige — das Mesh überträgt ihre Pakete weiter.", True),
    ("players_vote_kick_confirm", "Vote kick", "Abstimmen", True),
    ("players_own_status", "Own status", "Eigener Status", True),
    ("players_broadcast", "Broadcast", "Rundruf", True),
    ("players_show_all", "Show all", "Alle zeigen", True),
    ("players_online_only", "Online only", "Nur Online", True),
    ("players_no_unit", "No unit", "Keine Einheit", True),
    ("players_message", "Message", "Nachricht", True),
    ("players_locate", "Locate", "Zeigen", True),
    ("players_ping", "Ping", "Ping", True),
    ("players_get_markers", "Get markers", "Marker holen", True),
    ("players_vote_kick_menu", "Vote-kick…", "Kicken …", True),
    ("players_bat_unknown", "BAT —", "Akku —", True),
    ("status_active_badge", "ACTIVE", "AKTIV", True),
    ("status_inactive_badge", "INACT", "INAKT", True),
    ("status_hit_badge", "HIT", "TREFF", True),
    ("status_respawning_badge", "RESPAWN", "RESPAWN", True),
    ("status_eliminated_badge", "ELIM", "RAUS", True),
    ("status_offline_badge", "OFFLINE", "OFFLINE", True),
    ("status_active_button", "Active", "Aktiv", True),
    ("status_inactive_button", "Inact", "Inakt", True),
    ("status_hit_button", "Hit", "Treff", True),
    ("status_respawning_button", "Resp", "Resp", True),
    ("status_eliminated_button", "Elim", "Raus", True),
    ("age_seconds", "%1$ds", "%1$ds", True),
    ("age_minutes", "%1$dm", "%1$dm", True),
    ("age_hours", "%1$dh", "%1$dh", True),

    # ── Markers ──────────────────────────────────────────────────────────────
    ("markers_title", "Markers", "Marker", True),
    ("markers_cd_request_snapshot", "Request marker snapshot", "Marker-Snapshot anfordern", True),
    ("markers_broadcast_target", "All players (broadcast)", "Alle Spieler (Rundruf)", True),
    ("markers_request_from", "Request from %1$s", "Anfordern von %1$s", True),
    ("markers_cd_sync", "Sync markers and messages", "Marker und Nachrichten synchronisieren", True),
    ("markers_search_hint", "Search markers…", "Marker suchen …", True),
    ("markers_filter", "Filter", "Filter", True),
    ("markers_clear_filters", "Clear all filters", "Alle Filter löschen", True),
    ("markers_visible_count", "%1$d visible", "%1$d sichtbar", True),
    ("markers_no_match", "No markers match your filters.", "Keine Marker passen zu deinen Filtern.", True),
    ("markers_empty",
     "No tactical markers in your channels yet.\\nUse the Map tab to drop a point, route, or area.",
     "Noch keine taktischen Marker in deinen Kanälen.\\nPunkte, Routen und Flächen setzt du in der Karten-Ansicht.", True),
    ("markers_delete_title", "Delete marker?", "Marker löschen?", True),
    ("markers_delete_body",
     "Broadcasts a tombstone so other players will also remove \\u201c%1$s\\u201d.",
     "Sendet einen Löschbefehl, damit andere Spieler „%1$s“ ebenfalls entfernen.", True),
    ("markers_cd_delivered", "Delivered", "Zugestellt", True),
    ("markers_cd_pending", "Pending mesh delivery", "Übermittlung im Mesh ausstehend", True),
    ("markers_cd_received", "Received via mesh", "Über Mesh empfangen", True),
    ("markers_unnamed", "(unnamed)", "(ohne Namen)", True),
    ("markers_point_count", "%1$d pt", "%1$d Pkt", True),
    ("markers_age_seconds", "%1$ds ago", "vor %1$ds", True),
    ("markers_age_minutes", "%1$dm ago", "vor %1$dm", True),
    ("markers_age_hours", "%1$dh ago", "vor %1$dh", True),
    ("markers_never", "—", None, False),

    # ── Messages ─────────────────────────────────────────────────────────────
    ("msg_slot_title", "Slot %1$d", "Slot %1$d", True),
    ("msg_direct", "DIRECT", "DIREKT", True),
    ("msg_private", "private", "privat", True),
    ("msg_empty_history_long",
     "No traffic yet. Messages from your mesh will appear here as they arrive.",
     "Noch kein Funkverkehr. Nachrichten aus deinem Mesh erscheinen hier, sobald sie eintreffen.", True),
    ("msg_visible_to", "Visible to: %1$s", "Sichtbar für: %1$s", True),
    ("msg_compose_hint_peer", "Message %1$s…", "%1$s schreiben …", True),
    ("msg_cd_set_scope", "Set message scope", "Sichtbarkeit wählen", True),
    # Emitted by the ViewModels, which cannot call stringResource. They publish the resource id
    # and the byte count; the composable renders it. A ViewModel that builds a display sentence
    # is a ViewModel that cannot be translated.
    ("msg_too_long_bytes",
     "Too long: %1$d B exceeds the %2$d B LoRa limit.",
     "Zu lang: %1$d B übersteigen das LoRa-Limit von %2$d B.", True),
    ("msg_bytes_of_limit", "%1$d / %2$d B", None, False),
    ("msg_channel_open", "Open", "Offen", True),
    ("msg_channel_encrypted", "Encrypted", "Verschlüsselt", True),
    ("msg_meshtastic_interop", " · Meshtastic interop", " · Meshtastic-kompatibel", True),
    ("msg_role_primary", "primary", "primär", True),
    ("msg_role_secondary", "secondary", "sekundär", True),
    ("msg_role_disabled", "disabled", "deaktiviert", True),
    ("msg_you", "You", "Du", True),
    ("msg_node_id", "Node %1$s", "Knoten %1$s", True),
    ("msg_delivered", "Delivered", "Zugestellt", True),
    ("msg_pending", "Pending", "Ausstehend", True),
    ("msg_recent_conversations", "Recent conversations", "Letzte Gespräche", True),
    ("msg_start_conversation", "Start a conversation", "Gespräch beginnen", True),
    ("msg_no_nodes",
     "No nodes seen yet. Direct messages can be sent to any node that has been heard on the mesh.",
     "Noch keine Knoten gesehen. Direktnachrichten gehen an jeden Knoten, der im Mesh gehört wurde.", True),
    ("msg_cd_back_to_peers", "Back to peer list", "Zurück zur Teilnehmerliste", True),
    ("msg_direct_legend",
     "Direct · unicast · ✓ = received by device",
     "Direkt · Unicast · ✓ = am Gerät empfangen", True),
    ("msg_no_messages_peer",
     "No messages yet. Your message will be routed through the mesh to %1$s.",
     "Noch keine Nachrichten. Deine Nachricht wird über das Mesh an %1$s geleitet.", True),
    ("msg_time_hms", "HH:mm:ss", None, False),
    ("msg_date_short", "MMM d", None, False),

    # ── Settings ─────────────────────────────────────────────────────────────
    ("settings_read", "Read", "Lesen", True),
    ("settings_applying", "Applying…", "Wende an …", True),
    ("settings_callsign_field", "Callsign (max 16 B)", "Rufzeichen (max. 16 B)", True),
    ("settings_long_name_field", "Long name (max 39 B)", "Klarname (max. 39 B)", True),
    ("settings_short_name_field", "Short name (max 4 B)", "Kurzname (max. 4 B)", True),
    ("settings_generate_identity", "Generate random identity", "Zufällige Identität erzeugen", True),
    ("settings_company_field", "Company (e.g. \\u201c1st Company\\u201d)", "Kompanie (z. B. „1. Kompanie“)", True),
    ("settings_platoon_field", "Platoon (e.g. \\u201cRed Platoon\\u201d)", "Zug (z. B. „Zug Rot“)", True),
    ("settings_squad_field", "Squad (e.g. \\u201c1st Squad\\u201d)", "Gruppe (z. B. „Gruppe 1“)", True),
    ("settings_fireteam_field", "Fireteam (e.g. \\u201cAlpha\\u201d)", "Trupp (z. B. „Alpha“)", True),
    ("settings_player_color_field", "Player color (hex RRGGBB)", "Spielerfarbe (hex RRGGBB)", True),
    ("settings_profile_name", "Profile name", "Profilname", True),
    ("settings_profile_range", "Range", "Reichweite", True),
    ("settings_profile_bandwidth", "Channel width", "Kanalbreite", True),
    ("settings_profile_coding_rate", "Error correction", "Fehlerkorrektur", True),
    ("settings_channel_name", "Channel name", "Kanalname", True),
    ("settings_radio_profile", "Radio profile", "Funkprofil", True),
    ("settings_channel_password", "Password (blank = open channel)", "Schlüssel (leer = offener Kanal)", True),
    ("settings_broadcast_interval", "Broadcast interval (s)", "Sendintervall (s)", True),
    ("settings_min_tx_gap", "Min TX gap (s)", "Min. Sendeabstand (s)", True),
    ("settings_channel", "Channel", "Kanal", True),
    ("settings_display_off_after", "Display off after (s, 0 = never)", "Display aus nach (s, 0 = nie)", True),
    ("settings_unban", "Unban", "Entsperren", True),
    ("settings_tile_server_url", "Map tile server URL (optional)", "Kartenserver-URL (optional)", True),
    ("settings_qr_content_description", "QR code with AirTAK config", "QR-Code mit AirTAK-Konfiguration", True),
    ("settings_scan_qr", "Scan QR code", "QR-Code scannen", True),
    ("settings_apply_to_device", "Apply to device", "Auf Gerät anwenden", True),
    ("settings_scan_another", "Scan another", "Weitere scannen", True),
    ("settings_radio_restart", "Radio Restart", "Funk neu starten", True),
    ("settings_reboot_device", "Reboot Device", "Gerät neu starten", True),
    ("settings_update_firmware", "Update Firmware", "Firmware aktualisieren", True),
    ("settings_battery_optimization", "Battery Optimization", "Akku-Optimierung", True),
    # ── Settings: remaining section headings ───────────────────────────────
    ("settings_sec_player_identity", "Player identity", "Spieler-Identität", True),
    ("settings_sec_appearance", "Appearance", "Aussehen", True),
    ("settings_sec_region", "Region", "Region", True),
    ("settings_tx_power", "TX Power: %1$d dBm", "Sendeleistung: %1$d dBm", True),
    ("settings_phy_profiles", "PHY profiles (%1$d)", "PHY-Profile (%1$d)", True),
    ("settings_location_detail_shared", "Location detail shared", "Geteilte Positionsdetails", True),
    ("settings_position_broadcast", "Position broadcast", "Positionsübertragung", True),
    ("settings_gps_source", "GPS source", "GPS-Quelle", True),
    ("settings_position_channel", "Position broadcast channel", "Kanal der Positionsübertragung", True),
    ("settings_power_saving", "Power saving", "Energiesparen", True),
    ("settings_bonded_devices", "Bonded devices: %1$d", "Gespeicherte Kopplungen: %1$d", True),
    ("settings_active_bans", "Active vote-kick bans", "Aktive Abstimmungssperren", True),
    ("settings_theme", "Theme", "Design", True),
    ("settings_import_qr", "Import config from QR code", "Konfiguration per QR importieren", True),
    ("settings_configured_channels", "Configured channels", "Konfigurierte Kanäle", True),
    ("settings_reserved_slots", "Reserved slots (not selectable)", "Reservierte Slots (nicht wählbar)", True),

    # ── Map: alignment list row ────────────────────────────────────────────
    ("map_alignment_row", "Node %1$s · %2$s", "Knoten %1$s · %2$s", True),

    # ── Geocoder failures shown in the search dialog ───────────────────────
    # The ViewModel publishes the typed failure, not a sentence: it cannot call
    # stringResource, and a ViewModel that phrases its own errors is a ViewModel
    # whose errors cannot be translated.
    ("geocode_err_http_busy",
     "Geocoder is busy; try again in %1$d s.",
     "Kartensuchdienst ist beschäftigt; bitte in %1$d s erneut versuchen.", True),
    ("geocode_err_http", "Geocoder returned HTTP %1$d.", "Kartensuchdienst meldete HTTP %1$d.", True),
    ("geocode_err_malformed",
     "Geocoder returned a response AirTAK could not read.",
     "Die Antwort des Kartensuchdiensts war für AirTAK nicht lesbar.", True),
    ("geocode_err_too_large",
     "Geocoder response was too large to be a result set.",
     "Die Antwort des Kartensuchdiensts war zu groß für eine Ergebnisliste.", True),
    ("geocode_err_transport",
     "Geocoder unreachable: %1$s",
     "Kartensuchdienst nicht erreichbar: %1$s", True),

    ("settings_value_with_unit", "%1$s %2$s", None, False),

    # ── Settings: section headings and the remaining literals ───────────────
    ("settings_sec_device_ids", "Device identifiers", "Geräte-Identifikation", True),
    ("settings_sec_unit_hierarchy",
     "Unit hierarchy (optional, self-organized)",
     "Einheitshierarchie (optional, selbst organisiert)", True),
    ("settings_profile_n", "Profile %1$d", "Profil %1$d", True),
    ("settings_brightness", "Brightness: %1$d%%", "Helligkeit: %1$d%%", True),
    ("settings_close_app_dfu_busy", "Wait for the firmware update to finish before closing AirTAK.", "Warte, bis das Firmware-Update abgeschlossen ist, bevor du AirTAK schließt.", True),
    ("settings_exit_mode", "Close AirTAK", "AirTAK schließen", True),
    ("settings_exit_double_back", "Press Back twice", "Zweimal Zurück drücken", True),
    ("settings_exit_recents", "Remove from recent apps", "Aus letzten Apps entfernen", True),
    ("settings_exit_button_only", "Button only", "Nur per Knopf", True),
    ("settings_exit_button_only_hint", "Back and removing AirTAK from recent apps keep the connection running. The Close button stops phone tracking and Bluetooth and closes AirTAK. Reopening reconnects normally.", "Zurück und das Entfernen aus den letzten Apps lassen die Verbindung bestehen. Der Schließen-Knopf stoppt Handy-Tracking und Bluetooth und beendet AirTAK. Beim erneuten Öffnen verbindet sich die App wie gewohnt.", True),
    ("settings_exit_double_back_hint", "Press Back twice on the main screen to disconnect and close. Removing AirTAK from recent apps keeps the link running.", "Auf dem Hauptbildschirm zweimal Zurück drücken, um die Verbindung zu trennen und die App zu schließen. Beim Entfernen aus den letzten Apps bleibt die Verbindung bestehen.", True),
    ("settings_exit_recents_hint", "Back keeps the link running. Swipe AirTAK away in Android’s recent apps or tap Close all to disconnect and close. Active firmware updates are not interrupted.", "Zurück lässt die Verbindung bestehen. AirTAK in Androids letzten Apps wegwischen oder Alle schließen antippen, um die Verbindung zu trennen und die App zu schließen. Laufende Firmware-Updates werden nicht unterbrochen.", True),
    ("settings_theme_mode", "Theme: %1$s.", "Design: %1$s.", True),
    ("settings_export_qr", "Export config as QR code", "Konfiguration als QR-Code exportieren", True),
    ("settings_scan_prompt", "Scan an AirTAK config QR code", "AirTAK-Konfigurations-QR scannen", True),
    ("settings_import_summary", "Import summary", "Überblick über den Import", True),
    ("settings_moderation_votes",
     "%1$d / %2$d votes · %3$d distinct voters",
     "%1$d / %2$d Stimmen · %3$d verschiedene Wähler", True),
    ("settings_adc_multiplier", "Battery ADC multiplier", "Akku-ADC-Multiplikator", True),
    ("settings_factory_reset_note",
     "Factory reset requires a HMAC confirm-token and ships in Phase 6.",
     "Der Werkseinstellung braucht ein HMAC-Bestätigungstoken und kommt in Phase 6.", True),

    # ── Settings: LoRa PHY explanations ───────────────────────────────────
    ("preset_long_range", "Long range / moderate speed", "Weite Reichweite / mittlere Geschwindigkeit", True),
    ("preset_short_fast", "Short range / fast", "Kurze Reichweite / schnell", True),
    ("preset_very_long_slow", "Very long range / slow", "Sehr weite Reichweite / langsam", True),
    ("preset_medium", "Medium range / moderate", "Mittlere Reichweite / mittel", True),
    ("preset_user_defined", "User Defined", "Eigene Einstellung", True),
    ("preset_custom", "Custom", "Eigene", True),
    ("phy_not_configured", "Not configured — set values below", "Nicht gesetzt — Werte unten eintragen", True),
    ("phy_range_sf7", "short range (~1–2 km)", "kurze Reichweite (~1–2 km)", True),
    ("phy_range_sf8", "medium range (~2–4 km)", "mittlere Reichweite (~2–4 km)", True),
    ("phy_range_sf9", "medium range (~3–5 km)", "mittlere Reichweite (~3–5 km)", True),
    ("phy_range_sf10", "long range (~3–8 km)", "weite Reichweite (~3–8 km)", True),
    ("phy_range_sf11", "long range (~5–10 km)", "weite Reichweite (~5–10 km)", True),
    ("phy_range_sf12", "very long range (~8–15 km)", "sehr weite Reichweite (~8–15 km)", True),
    ("phy_speed_fast", "fast (~%1$s kbps)", "schnell (~%1$s kbit/s)", True),
    ("phy_speed_moderate", "moderate (~%1$s kbps)", "mittel (~%1$s kbit/s)", True),
    ("phy_speed_slow", "slow (~%1$s kbps)", "langsam (~%1$s kbit/s)", True),
    ("phy_separator", "%1$s · %2$s", None, False),
    ("sf_desc_7", "Fastest, short range (SF7)", "Am schnellsten, kurze Reichweite (SF7)", True),
    ("sf_desc_8", "Fast, short-medium (SF8)", "Schnell, kurz bis mittel (SF8)", True),
    ("sf_desc_9", "Balanced (SF9)", "Ausgewogen (SF9)", True),
    ("sf_desc_10", "Slower, long range (SF10)", "Langsamer, weite Reichweite (SF10)", True),
    ("sf_desc_11", "Slow, very long range (SF11)", "Langsam, sehr weite Reichweite (SF11)", True),
    ("sf_desc_12", "Slowest, maximum range (SF12)", "Am langsamsten, maximale Reichweite (SF12)", True),
    ("sf_label", "SF%1$d", None, False),
    ("bw_desc_125", "125 kHz — narrow (extra range, less interference)",
     "125 kHz — schmal (mehr Reichweite, weniger Störungen)", True),
    ("bw_desc_250", "250 kHz — standard", "250 kHz — Standard", True),
    ("bw_desc_500", "500 kHz — wide (faster, more interference)",
     "500 kHz — breit (schneller, mehr Störungen)", True),
    ("bw_khz", "%1$d kHz", None, False),

    # ── Settings: channel role ──────────────────────────────────────────────
    ("role_primary", "Primary", "Primär", True),
    ("role_secondary", "Secondary", "Sekundär", True),
    ("role_disabled", "Disabled", "Deaktiviert", True),
    ("role_primary_desc",
     "Main team channel — all mesh traffic flows here.",
     "Hauptkanal der Einheit — hier läuft der gesamte Mesh-Verkehr.", True),
    ("role_secondary_desc",
     "Extra channel (command net, cross-team link, etc).",
     "Zusätzlicher Kanal (Führung, Verbindung zu anderen Einheiten …).", True),
    ("role_disabled_desc",
     "Slot unused — no traffic sent or received.",
     "Slot unbenutzt — es wird weder gesendet noch empfangen.", True),
    ("open_or_encrypted", "%1$s · %2$s", None, False),
    ("index_with_label", "%1$d: %2$s", None, False),

    # ── Back-to-close hint (ui/BackToExit.kt) ────────────────────────────────
    # The two-press exit shows the "again" form; the counted form exists so the hint stays honest
    # if the press count is ever raised, and keeps the German short enough for a toast.
    ("exit_back_again", "Press back again to close AirTAK",
     "Nochmal Zurück drücken, um AirTAK zu schließen", True),
    ("exit_back_more", "Press back %1$d more times to close AirTAK",
     "Noch %1$d Mal Zurück drücken, um AirTAK zu schließen", True),
]


def esc(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


BEGIN = "<!-- i18n:begin generated (tools/i18n_strings.py) -->"
END = "<!-- i18n:end generated -->"


def render(index: int) -> str:
    keys = [row[0] for row in ROWS]
    if len(keys) != len(set(keys)):
        raise SystemExit("duplicate resource key in ROWS")
    lines = [BEGIN, ""]
    for row in ROWS:
        key, value = row[0], row[index]
        if value is None:
            value = row[1]
        translatable = "" if row[3] else ' translatable="false"'
        if "'" in value:
            raise SystemExit(f"{key}: unescaped apostrophe in {value!r}")
        lines.append(f'    <string name="{key}"{translatable}>{esc(value)}</string>')
    lines.append(END)
    return "\n".join(lines) + "\n"


def main() -> int:
    base = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")
    mode = sys.argv[2] if len(sys.argv) > 2 else "write"
    for path, index in ((base / "values/strings.xml", 1), (base / "values-de/strings.xml", 2)):
        text = path.read_text(encoding="utf-8")
        block = render(index)
        if BEGIN in text:
            head, _, rest = text.partition(BEGIN)
            _, _, tail = rest.partition(END)
            # The block already ends in a newline; the text after END starts with one too.
            updated = head + block + tail.lstrip("\n")
        else:
            assert text.rstrip().endswith("</resources>"), f"{path} does not end with </resources>"
            head, _, _ = text.rpartition("</resources>")
            updated = head + block + "</resources>\n"
        if mode == "check":
            if updated != text:
                raise SystemExit(f"{path}: generated block is out of date, re-run tools/i18n_strings.py write")
            print(f"{path}: up to date")
            continue
        path.write_text(updated, encoding="utf-8")
        print(f"{path}: wrote {len(ROWS)} generated entries")

    # Parity: every English key must have a German counterpart and vice versa.
    import re
    en_names = re.findall(r'<string name="([^"]+)"', (base / "values/strings.xml").read_text(encoding="utf-8"))
    de_names = re.findall(r'<string name="([^"]+)"', (base / "values-de/strings.xml").read_text(encoding="utf-8"))
    if len(en_names) != len(set(en_names)) or len(de_names) != len(set(de_names)):
        raise SystemExit("duplicate resource key across generated and handwritten strings")
    en, de = set(en_names), set(de_names)
    if en != de:
        raise SystemExit(f"locale key parity broken: missing in de={sorted(en - de)} extra in de={sorted(de - en)}")
    print(f"locale key parity OK: {len(en)} keys in both values/ and values-de/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
