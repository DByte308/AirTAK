package com.airtak.ui.help

data class HelpEntry(val label: String, val explanation: String)
data class HelpTopic(val title: String, val intro: String, val entries: List<HelpEntry>)

val HELP_TOPICS: List<HelpTopic> = listOf(
    HelpTopic(
        title = "Getting started and pairing",
        intro = "AirTAK helps you find your teammates and share messages and markers during an airsoft game. Your phone or tablet talks to the radio over BLE; the radio carries traffic over the LoRa mesh.",
        entries = listOf(
            HelpEntry("Pair a device", "Power on your compatible AirTAK radio and tap the link chip at the top of the app. Grant Bluetooth and location permissions when asked, then scan and select your radio."),
            HelpEntry("Check the pairing code", "Compare the six-digit code on the radio's e-ink display with the Android pairing prompt. Confirm only if both codes match."),
            HelpEntry("Disconnect and reconnect", "Disconnect drops the current link but keeps the radio remembered. Reconnect tries that radio again; Forget removes the saved pairing so you can start over."),
            HelpEntry("Before you join the game", "Set your callsign under Config > Player and use the same radio and channel setup as your teammates. Download the field's map and routing data while you still have internet access."),
            HelpEntry("Offline use", "Downloaded maps and radio traffic do not need mobile internet during play. Location searches, downloads and new map imagery may still need an internet connection.")
        )
    ),
    HelpTopic(
        title = "Map and navigation",
        intro = "Use the map to see positions, shared markers and your planned route. A player's last reported position may be old, so check the roster before assuming they are still there.",
        entries = listOf(
            HelpEntry("Search", "Find a town, place or coordinate and move the map there. Use this to find your field before downloading its map."),
            HelpEntry("Add marker", "Choose the kind of marker to draw, then tap the map to place its points. Undo removes the last point, Commit opens its details, and Send shares it with the selected audience."),
            HelpEntry("Pause and resume", "Pause marks your own game activity as inactive; resume makes it active again. This does not pause the game for everyone else."),
            HelpEntry("Player status", "Tap your status to report Active, HIT, RESPAWNING or ELIMINATED as appropriate. These reports inform other players; the app does not enforce the site's game rules."),
            HelpEntry("Players button", "Show or hide player positions on the map. This changes your map view, not whether those players can send updates."),
            HelpEntry("Waypoints button", "Show or hide waypoint markers on the map. Open the Markers tab when you want to search, inspect or locate a particular shared marker."),
            HelpEntry("Imagery", "Import an image of the field or mission plan and align it with the map. Adjust its corners and opacity; sharing alignment sends its placement, so another player still needs the matching image file."),
            HelpEntry("Download tiles", "Tap two corners to select an area, check the zoom range and tile URL, then download its map tiles. Download a manageable area before leaving internet coverage."),
            HelpEntry("Layers button", "This button selects an area for downloading BRouter routing data, not a list of map layers. Download these files if you want routes that follow roads and trails offline."),
            HelpEntry("Plan route", "Tap a start point and an end point to calculate a route. Without the required routing data, the app falls back to a straight line; a route is not a guarantee that access is allowed or the ground is safe."),
            HelpEntry("Routing profile", "Tap the profile chip to choose how a route is calculated. Pick the profile that suits how you plan to move, then check the resulting route against the field's rules."),
            HelpEntry("Recenter", "Return the map to your own position after looking elsewhere. A usable GPS position must be available first."),
            HelpEntry("Map tab", "Return to the field map and its drawing and route tools."),
            HelpEntry("Net tab", "Read and send radio messages without leaving the app."),
            HelpEntry("Roster tab", "Find players, inspect their latest reports and open actions such as Message or Locate."),
            HelpEntry("Markers tab", "Browse the shared points, routes, areas and objectives you have received."),
            HelpEntry("Config tab", "Change your identity, radio, channels and app preferences. Use Help here when you need a reminder during setup.")
        )
    ),
    HelpTopic(
        title = "Link and HUD readings",
        intro = "The top bar helps you check whether the app has a radio link and recent device information. Missing readings are not the same as a zero reading.",
        entries = listOf(
            HelpEntry("No link", "The app is not currently connected to its radio over BLE. Tap this chip to open device pairing or reconnect; being linked to a radio does not prove that another player is within mesh reach."),
            HelpEntry("BAT", "Check the battery level reported by your radio. It is not your phone's battery level."),
            HelpEntry("GPS", "Check satellite or fix information from the selected position source. If no fix is available, give the receiver a clearer view of the sky and check Position & Power."),
            HelpEntry("RSSI", "Check the reported received signal strength in dBm. A value closer to zero is stronger, but this reading is not a distance measurement or a guarantee of delivery across the mesh."),
            HelpEntry("DC", "Check the radio's duty-cycle load and warning level. High load can delay transmissions or cause relays to be dropped; reduce unnecessary traffic rather than bypassing the radio limits.")
        )
    ),
    HelpTopic(
        title = "Net and messages",
        intro = "Net keeps channel conversations separate from direct messages. Messages travel over the radio mesh, so delivery depends on radio coverage and available airtime.",
        entries = listOf(
            HelpEntry("Mesh", "Use the primary shared channel for traffic intended for the wider mesh, including compatible Meshtastic users. Treat an open channel as public."),
            HelpEntry("Game", "Use the secondary AirTAK channel for game coordination. Its name can differ if your group has renamed the channel."),
            HelpEntry("Other channel tabs", "Enabled channel slots appear as tabs. Select the slot used by your unit and check whether it is open or encrypted before sending."),
            HelpEntry("Direct", "Choose a heard node to open a conversation addressed to that player rather than the whole channel. The mesh may relay the message along the way; private addressing is not a promise of delivery."),
            HelpEntry("Message scope", "Choose All players or a unit level to set the intended audience. Set up your unit hierarchy and matching channels with your team before relying on scoped traffic."),
            HelpEntry("Write and send", "Type your message and tap Send. Keep it short so it takes less radio airtime."),
            HelpEntry("Byte counter", "The counter measures UTF-8 bytes against the 237-byte limit, not letters. Accents and other non-ASCII characters can use more than one byte."),
            HelpEntry("Refresh", "Request a sync of messages and markers when you think your local view is missing information. It cannot recover data that no reachable radio has available.")
        )
    ),
    HelpTopic(
        title = "Roster",
        intro = "The roster shows players heard on the mesh, their units and their latest reports. An old report does not prove a player is still in the same place or status.",
        entries = listOf(
            HelpEntry("Search and filters", "Search a callsign or unit to find a teammate. Use the available filters to narrow the list rather than scrolling through everyone."),
            HelpEntry("Player details", "Open a player to inspect their status and available position and radio information. Check how old those reports are before using them to coordinate a move."),
            HelpEntry("Message", "Open a direct conversation with this player."),
            HelpEntry("Locate", "Move the map to the player's reported position. If no position is available, wait for an update."),
            HelpEntry("Ping", "Send a request to the player's radio to check whether it responds. A missing response can mean poor coverage, not that the player has left the field."),
            HelpEntry("Get markers", "Request markers from this player to fill gaps in your local marker list."),
            HelpEntry("Vote-kick", "Cast a vote at the selected scope. Three distinct voters at the same scope hide that player from the local interface for 24 hours; their radio packets still pass through the mesh.")
        )
    ),
    HelpTopic(
        title = "Markers",
        intro = "Markers let you share a place, route, area or objective without typing coordinates into chat. Their audience, team and priority help your group decide what each marker means.",
        entries = listOf(
            HelpEntry("Search and filters", "Search labels and narrow the list by type or scope. Clear all filters if a marker seems to be missing."),
            HelpEntry("Request markers", "Request an update from all players or a particular heard node. This is useful after reconnecting or joining a game late."),
            HelpEntry("Open and locate", "Inspect a marker's description, creator, team and priority, then locate it on the map. Navigate calculates a route toward it using your selected routing profile."),
            HelpEntry("Create and edit", "Use the map's add-marker tool to place a point or draw a route or area. Set a clear label and the intended visibility before sending; edit controls are available for markers you own."),
            HelpEntry("Objective", "Use an objective marker to show a game location and its controlling team. Agree on its meaning with the marshals; it does not automatically award points or enforce capture rules."),
            HelpEntry("Delete", "Deleting a marker you own broadcasts a removal record so other players can remove their copies too. Players outside radio reach may not see that change immediately.")
        )
    ),
    HelpTopic(
        title = "Config sections",
        intro = "Read loads the current group of settings from the radio. Apply sends your edits to it; changing radio settings can briefly interrupt LoRa reception while BLE stays connected.",
        entries = listOf(
            HelpEntry("Player", "Set your callsign, device names and map colour. Add optional Fireteam, Squad, Platoon and Company names and link each unit level to its agreed channel."),
            HelpEntry("Device HIT button", "Enable the HIT toggle if you want a long press on the radio's user button to report a hit. Make sure you know which button and gesture your hardware uses before the game starts."),
            HelpEntry("LoRa Radio", "Choose the legal region, TX power and PHY profiles for your site. Coordinate these with your team; incompatible profiles can prevent radios hearing each other."),
            HelpEntry("Channels", "Manage eight slots and their PSKs or shared passwords. Slots 0 and 1 provide the shared and game channels, slot 2 is reserved for future audio, and slots 3 to 7 can carry unit traffic."),
            HelpEntry("Matching channel settings", "Teammates need matching slot, name and password settings for the intended unit channel. A blank password makes a channel open; use an agreed encrypted channel for position sharing."),
            HelpEntry("Position & Power", "Choose phone GPS or the radio's GPS, the position channel and broadcast timing. Smart beaconing adjusts position reports; shorter intervals use more airtime and power."),
            HelpEntry("Power controls", "Adjust the available display timeout and brightness settings. The GPS duty-cycle control is marked for a later development phase, so do not depend on it for battery savings yet."),
            HelpEntry("Security", "Set the allowed BLE bond count for the radio. This controls remembered Bluetooth pairings, not who shares your mesh channel password."),
            HelpEntry("Moderation", "Review active vote-kick bans, their scopes and expiry. Unban restores a hidden player in your local view; it does not remove their radio from the mesh."),
            HelpEntry("Application", "Choose your theme and optional map tile server. The tile URL needs the server's zoom and coordinate placeholders; use a source you are allowed to download from."),
            HelpEntry("Language", "Choose English, German or the system default for the app's labels. Changing language rebuilds the screens and closes the view you had open."),
            HelpEntry("Help", "Open this guide when you need to check a control or prepare the app for a game."),
            HelpEntry("Share Config", "Export a QR setup for teammates or scan one and review its summary before applying it. A shared setup can contain channel secrets, so show it only to people who should join your group."),
            HelpEntry("About", "Inspect diagnostics, restart the radio or reboot the device when troubleshooting. Open Update Firmware here; factory reset is marked as a later feature, not a working recovery button."),
            HelpEntry("Battery Optimization", "Open Android's battery settings if background restrictions keep interrupting your connection. Android controls this setting, so its wording can vary between devices.")
        )
    ),
    HelpTopic(
        title = "Firmware updates",
        intro = "Firmware runs on the radio, not in the Android app. Update it with a file intended for your exact device and board revision.",
        entries = listOf(
            HelpEntry("Choose a file", "Open Config > About > Update Firmware and select the appropriate .bin or .zip package. Do not flash a file for different hardware just because its name looks similar."),
            HelpEntry("Update via Bluetooth", "Use BLE when the radio is paired and nearby. Keep it powered and within range throughout transfer, verification and restart."),
            HelpEntry("Update via USB cable", "Connect the radio with a data-capable USB cable and use the USB update option. Grant Android access to the USB device if prompted."),
            HelpEntry("Progress and verification", "Wait while the app transfers and verifies the image, then let the radio restart. Do not unplug it or switch it off during the update."),
            HelpEntry("Failure and logs", "If the update fails, read the error and debug log before retrying. Check the file, power and connection rather than repeatedly flashing an unknown image.")
        )
    )
)
