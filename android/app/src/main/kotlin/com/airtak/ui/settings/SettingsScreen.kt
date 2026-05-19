// AirTAK Android — ui/settings/SettingsScreen.kt
// Planning: §11.7
// Roadmap: 3.4
//
// Sectioned settings home + per-group editor screens. Each editor reads its
// current bytes from ConfigRepository, decodes the proto, lets the user edit
// fields, then on Apply serializes and calls ConfigRepository.apply().
//
// LORA and CHANNELS sections show an amber radio-restart warning banner
// because applying them triggers the controlled radio restart procedure
// (§9.4.2).

package com.airtak.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.proto.AdminCommand
import com.airtak.proto.ChannelRole
import com.airtak.proto.GpsSource
import com.airtak.proto.LoraRegion
import com.airtak.proto.PhyProfile
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.ThemeMode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private enum class Section(val title: String, val subtitle: String) {
    Player("Player", "Callsign, name, unit hierarchy, hit button"),
    Lora("LoRa Radio", "Region, TX power, PHY profiles"),
    Channels("Channels", "8 channel slots with PSK"),
    PositionPower("Position & Power", "GPS broadcast, smart-beacon, battery saving"),
    Security("Security", "BLE bond count"),
    Moderation("Moderation", "Vote-kick bans, ban list, unban"),
    App("Application", "Theme, tile server, defaults"),
    Share("Share Config", "QR export / scan import of LoRa, channels, unit setup"),
    About("About", "Diagnostics, factory reset"),
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onFirmwareSelected: ((Uri) -> Unit)? = null,
) {
    var current by remember { mutableStateOf<Section?>(null) }
    // When non-null, the Channels editor scrolls / highlights the given slot
    // index on entry (used by the "Set up channel" flow from the Player section).
    var focusChannelSlot by remember { mutableStateOf<Int?>(null) }

    when (val sec = current) {
        null -> SectionList(onPick = { current = it })
        else -> {
            val title = sec.title
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                TopRow(
                        title = title,
                        onBack = {
                            current = null
                            focusChannelSlot = null
                        }
                )
                when (sec) {
                    Section.Player ->
                            PlayerSection(
                                    vm = viewModel,
                                    onNavigateToChannels = { current = Section.Channels },
                                    onNavigateToChannelSlot = { slot ->
                                        focusChannelSlot = slot
                                        current = Section.Channels
                                    },
                            )
                    Section.Lora -> LoraSection(viewModel)
                    Section.Channels ->
                            ChannelsSection(viewModel, initialFocusSlot = focusChannelSlot)
                    Section.PositionPower -> PositionPowerSection(viewModel)
                    Section.Security -> SecuritySection(viewModel)
                    Section.Moderation -> ModerationSection(viewModel)
                    Section.App -> AppSection(viewModel)
                    Section.Share -> ShareSection(viewModel)
                    Section.About -> AboutSection(viewModel, onFirmwareSelected)
                }
            }
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 12.dp)
    ) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.size(12.dp))
        Text(
                title.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun SectionList(onPick: (Section) -> Unit) {
    LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                    "CONFIG",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
        }
        items(Section.entries) { sec ->
            HudCard(modifier = Modifier.fillMaxWidth().clickable { onPick(sec) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                                sec.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                        )
                        TacticalText(
                                sec.subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioRestartBanner() {
    Row(
            Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentAmber.copy(alpha = 0.12f))
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = AccentAmber)
        Spacer(Modifier.size(10.dp))
        Text(
                "Applying this group restarts the LoRa radio for ~300 ms. The BLE link is preserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ApplyRow(onRead: () -> Unit, onApply: () -> Unit, busy: Boolean) {
    Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onRead, modifier = Modifier.weight(1f), enabled = !busy) {
            Text("Read")
        }
        Button(onClick = onApply, modifier = Modifier.weight(1f), enabled = !busy) {
            Text(if (busy) "Applying…" else "Apply")
        }
    }
}

// ── PLAYER ────────────────────────────────────────────────────────────────────

@Composable
private fun PlayerSection(
        vm: SettingsViewModel,
        onNavigateToChannels: () -> Unit = {},
        onNavigateToChannelSlot: (Int) -> Unit = { onNavigateToChannels() },
) {
    val device by vm.device.collectAsState()
    val game by vm.game.collectAsState()
    val channels by vm.channels.collectAsState()
    LaunchedEffect(Unit) { vm.readDevice() }
    LaunchedEffect(Unit) { vm.readGame() }
    LaunchedEffect(Unit) { vm.readChannels() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Player identity")
        TacticalText(
                "Your callsign is visible to all players on the mesh grid.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = game.callsign,
                onValueChange = { vm.updateGame { copy(callsign = it.take(16)) } },
                label = { Text("Callsign (max 16 B)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Device identifiers")
        TacticalText(
                "These names identify your device on the mesh. The short name is shown on device displays and node lists.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = device.longName,
                onValueChange = { vm.updateDevice { copy(longName = it.take(39)) } },
                label = { Text("Long name (max 39 B)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = device.shortName,
                onValueChange = { vm.updateDevice { copy(shortName = it.take(4)) } },
                label = { Text("Short name (max 4 B)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { vm.generateRandomIdentity() }) {
                Text("Generate random identity")
            }
        }
        TacticalText(
            "Generates random hexadecimal identifiers for callsign, long name, and short name. These are unique per device and are not included in QR config sharing.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Unit hierarchy (optional, self-organized)")
        TacticalText(
                "Assign yourself to a unit and link it to a channel slot below. Nodes sharing the same unit ID are grouped in the roster. " +
                        "Slots 0 and 1 are reserved for mesh-wide traffic, slot 2 is reserved for PTT audio — pick slots 3+ for unit-scoped comms.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val channelOptions: List<UnitChannelOption> =
                channels.slots.map { editor ->
                    val slot = editor.slot
                    val role = slot.role
                    val encrypted = slot.psk.size() > 0
                    val kind =
                            when {
                                slot.index == 0 -> UnitChannelKind.GlobalMesh
                                slot.index == 1 -> UnitChannelKind.GlobalTactical
                                slot.index == 2 -> UnitChannelKind.AudioReserved
                                role == ChannelRole.DISABLED -> UnitChannelKind.Available
                                else -> UnitChannelKind.UnitScoped
                            }
                    UnitChannelOption(
                            index = slot.index,
                            name = slot.name,
                            kind = kind,
                            encrypted = encrypted,
                            hasPsk = encrypted,
                            role = role,
                    )
                }
        // Collect slot indices already claimed by another unit level so users see
        // the conflict in the dropdown.
        val claimedSlots: Map<Int, String> = buildMap {
            game.fireteamChannelSlot.takeIf { it >= 0 }?.let { put(it, "fireteam") }
            game.squadChannelSlot.takeIf { it >= 0 }?.let { put(it, "squad") }
            game.platoonChannelSlot.takeIf { it >= 0 }?.let { put(it, "platoon") }
            game.companyChannelSlot.takeIf { it >= 0 }?.let { put(it, "company") }
        }

        // Company
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                    value = game.companyId,
                    onValueChange = { vm.updateGame { copy(companyId = it.take(32)) } },
                    label = { Text("Company (e.g. \"1st Company\")") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
            )
            UnitChannelDropdown(
                    level = "company",
                    unitLabel = game.companyId.ifBlank { "Company" },
                    selectedSlot = game.companyChannelSlot,
                    channelOptions = channelOptions,
                    claimedSlots = claimedSlots,
                    onSelect = { slot -> vm.updateGame { copy(companyChannelSlot = slot) } },
                    onSetupNewChannel = {
                        val newSlot =
                                vm.prepareUnitChannel(
                                        "company",
                                        game.companyId.ifBlank { "Company" }
                                )
                        if (newSlot != null) onNavigateToChannelSlot(newSlot)
                        else onNavigateToChannels()
                    },
                    onManageChannels = onNavigateToChannels,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Platoon
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                    value = game.platoonId,
                    onValueChange = { vm.updateGame { copy(platoonId = it.take(32)) } },
                    label = { Text("Platoon (e.g. \"Red Platoon\")") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
            )
            UnitChannelDropdown(
                    level = "platoon",
                    unitLabel = game.platoonId.ifBlank { "Platoon" },
                    selectedSlot = game.platoonChannelSlot,
                    channelOptions = channelOptions,
                    claimedSlots = claimedSlots,
                    onSelect = { slot -> vm.updateGame { copy(platoonChannelSlot = slot) } },
                    onSetupNewChannel = {
                        val newSlot =
                                vm.prepareUnitChannel(
                                        "platoon",
                                        game.platoonId.ifBlank { "Platoon" }
                                )
                        if (newSlot != null) onNavigateToChannelSlot(newSlot)
                        else onNavigateToChannels()
                    },
                    onManageChannels = onNavigateToChannels,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Squad
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                    value = game.squadId,
                    onValueChange = { vm.updateGame { copy(squadId = it.take(32)) } },
                    label = { Text("Squad (e.g. \"1st Squad\")") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
            )
            UnitChannelDropdown(
                    level = "squad",
                    unitLabel = game.squadId.ifBlank { "Squad" },
                    selectedSlot = game.squadChannelSlot,
                    channelOptions = channelOptions,
                    claimedSlots = claimedSlots,
                    onSelect = { slot -> vm.updateGame { copy(squadChannelSlot = slot) } },
                    onSetupNewChannel = {
                        val newSlot =
                                vm.prepareUnitChannel("squad", game.squadId.ifBlank { "Squad" })
                        if (newSlot != null) onNavigateToChannelSlot(newSlot)
                        else onNavigateToChannels()
                    },
                    onManageChannels = onNavigateToChannels,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Fireteam
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                    value = game.fireteamId,
                    onValueChange = { vm.updateGame { copy(fireteamId = it.take(32)) } },
                    label = { Text("Fireteam (e.g. \"Alpha\")") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
            )
            UnitChannelDropdown(
                    level = "fireteam",
                    unitLabel = game.fireteamId.ifBlank { "Fireteam" },
                    selectedSlot = game.fireteamChannelSlot,
                    channelOptions = channelOptions,
                    claimedSlots = claimedSlots,
                    onSelect = { slot -> vm.updateGame { copy(fireteamChannelSlot = slot) } },
                    onSetupNewChannel = {
                        val newSlot =
                                vm.prepareUnitChannel(
                                        "fireteam",
                                        game.fireteamId.ifBlank { "Fireteam" }
                                )
                        if (newSlot != null) onNavigateToChannelSlot(newSlot)
                        else onNavigateToChannels()
                    },
                    onManageChannels = onNavigateToChannels,
            )
        }
        Spacer(Modifier.height(8.dp))
        TacticalText(
                "All fields are optional and self-assigned. Each unit can be linked to a dedicated channel for scoped communications.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Appearance")
        OutlinedTextField(
                value = game.playerColorRgb.toString(16).uppercase().padStart(6, '0'),
                onValueChange = { hex ->
                    hex.take(6).toIntOrNull(16)?.let { vm.updateGame { copy(playerColorRgb = it) } }
                },
                label = { Text("Player color (hex RRGGBB)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    "Device button = HIT toggle",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                    checked = game.hitButtonEnabled,
                    onCheckedChange = { vm.updateGame { copy(hitButtonEnabled = it) } }
            )
        }
        TacticalText(
                "When enabled, long-press the device button to toggle HIT status.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val busy = vm.busy.collectAsState().value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                    onClick = {
                        vm.readDevice()
                        vm.readGame()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
            ) { Text("Read") }
            Button(
                    onClick = {
                        vm.applyDevice()
                        vm.applyGame()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
            ) { Text(if (busy) "Applying…" else "Apply") }
        }
    }
}

// ── LORA ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraSection(vm: SettingsViewModel) {
    val state by vm.lora.collectAsState()
    LaunchedEffect(Unit) { vm.readLora() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        RadioRestartBanner()
        Spacer(Modifier.height(16.dp))
        SectionLabel("Region")
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.region.name)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LoraRegion.values().filter { it != LoraRegion.UNRECOGNIZED }.forEach { r ->
                    DropdownMenuItem(
                            text = { Text(r.name) },
                            onClick = {
                                vm.updateLora { copy(region = r) }
                                expanded = false
                            }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel("TX Power: ${state.txPower} dBm")
        Slider(
                value = state.txPower.toFloat(),
                onValueChange = { vm.updateLora { copy(txPower = it.toInt()) } },
                valueRange = 2f..22f,
                steps = 19,
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel("PHY profiles (${state.profiles.size})")
        state.profiles.forEachIndexed { i, p ->
            HudCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                ProfileEditor(
                        index = i,
                        profile = p,
                        onName = { name -> vm.updateProfile(i) { setName(name.take(16)) } },
                        onSf = { sf -> vm.updateProfile(i) { setSpreadingFactor(sf) } },
                        onBandwidth = { bw -> vm.updateProfile(i) { setBandwidthHz(bw) } },
                        onCodingRate = { cr -> vm.updateProfile(i) { setCodingRate(cr) } },
                )
            }
        }
        ApplyRow(
                onRead = vm::readLora,
                onApply = vm::applyLora,
                busy = vm.busy.collectAsState().value
        )
    }
}

@Composable
private fun ProfileEditor(
        index: Int,
        profile: PhyProfile,
        onName: (String) -> Unit,
        onSf: (Int) -> Unit,
        onBandwidth: (Int) -> Unit,
        onCodingRate: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                        "Profile $index",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                )
                TacticalText(profilePreset(index), color = MaterialTheme.colorScheme.primary)
            }
        }
        // Plain-English summary of what this profile delivers in the field
        Text(
                phyDescription(profile.spreadingFactor, profile.bandwidthHz, profile.codingRate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
                value = profile.name,
                onValueChange = onName,
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        // Range/speed tradeoff (Spreading Factor)
        NumberDropdown(
                label = "Range",
                value = profile.spreadingFactor,
                options = (7..12).toList(),
                onValue = onSf,
                modifier = Modifier.fillMaxWidth(),
        ) { sf ->
            when (sf) {
                7 -> "Fastest, short range (SF7)"
                8 -> "Fast, short-medium (SF8)"
                9 -> "Balanced (SF9)"
                10 -> "Slower, long range (SF10)"
                11 -> "Slow, very long range (SF11)"
                12 -> "Slowest, maximum range (SF12)"
                else -> "SF$sf"
            }
        }
        // Channel width (Bandwidth)
        NumberDropdown(
                label = "Channel width",
                value = profile.bandwidthHz,
                options = listOf(125000, 250000, 500000),
                onValue = onBandwidth,
                modifier = Modifier.fillMaxWidth(),
        ) { bw ->
            when (bw) {
                125000 -> "125 kHz — narrow (extra range, less interference)"
                250000 -> "250 kHz — standard"
                500000 -> "500 kHz — wide (faster, more interference)"
                else -> "${bw / 1000} kHz"
            }
        }
        // Error correction (Coding Rate)
        NumberDropdown(
                label = "Error correction",
                value = profile.codingRate,
                options = (5..8).toList(),
                onValue = onCodingRate,
                modifier = Modifier.fillMaxWidth(),
        ) { cr ->
            when (cr) {
                5 -> "Light — faster (4/5)"
                6 -> "Moderate (4/6)"
                7 -> "Heavy (4/7)"
                8 -> "Maximum — slowest, most reliable (4/8)"
                else -> "4/$cr"
            }
        }
    }
}

// ── CHANNELS ──────────────────────────────────────────────────────────────────

@Composable
private fun ChannelsSection(vm: SettingsViewModel, initialFocusSlot: Int? = null) {
    val state by vm.channels.collectAsState()
    val lora by vm.lora.collectAsState()
    LaunchedEffect(Unit) { vm.readChannels() }
    LaunchedEffect(Unit) { vm.readLora() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        RadioRestartBanner()
        Spacer(Modifier.height(16.dp))

        // ── Group messaging hierarchy guide ───────────────────────────────────
        HudCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                        "Setting up group channels",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                        "Slots 0–2 are fixed by AirTAK; slots 3–7 are free for unit-scoped traffic (squad, fireteam, platoon, company). " +
                                "Pick a recognisable name and a shared password — every device in that unit must use the same slot index, name, and password. " +
                                "Link a slot to a unit level from Settings → Player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                        "Slot layout:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                        "Slot 0 — AirTAK (primary, Meshtastic-compatible) — all players\n" +
                                "Slot 1 — Tactical (AirTAK-wide secondary) — all players\n" +
                                "Slot 2 — Audio (reserved for PTT in a future phase)\n" +
                                "Slot 3+ — Unit channels (e.g. \"Squad 1\", \"Platoon A\", \"Company Red\")",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                        "Slots appear as tabs in the Messages screen once enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        val mixedActiveProfiles =
                state.slots
                        .map { it.slot }
                        .filter { it.role != ChannelRole.DISABLED }
                        .map { it.phyProfileIndex }
                        .distinct()
                        .size > 1
        if (mixedActiveProfiles) {
            Text(
                    "Active slots use multiple PHY profiles. Keep teammates on the same profile unless intentionally isolating traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentAmber,
                    modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        state.validationError?.let { error ->
            Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        state.slots.forEachIndexed { index, editor ->
            val highlighted = initialFocusSlot != null && initialFocusSlot == editor.slot.index
            if (highlighted) {
                Text(
                        "↓ New channel for your unit — set a password below and tap Apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            ChannelSlotEditor(
                    editor = editor,
                    profileNames =
                            lora.profiles.mapIndexed { i, profile ->
                                "${i}: ${profile.name.ifBlank { profilePreset(i) }}"
                            },
                    onName = { name -> vm.updateChannelSlot(index) { setName(name.take(16)) } },
                    onRole = { role -> vm.updateChannelSlot(index) { setRole(role) } },
                    onUplink = { enabled ->
                        vm.updateChannelSlot(index) { setUplinkEnabled(enabled) }
                    },
                    onDownlink = { enabled ->
                        vm.updateChannelSlot(index) { setDownlinkEnabled(enabled) }
                    },
                    onPrecision = { bits ->
                        vm.updateChannelSlot(index) { setPositionPrecision(bits) }
                    },
                    onProfile = { profileIndex ->
                        vm.updateChannelSlot(index) { setPhyProfileIndex(profileIndex) }
                    },
                    onPsk = { text -> vm.updateChannelPsk(index, text) },
                    onRevealPsk = { revealed -> vm.setChannelPskRevealed(index, revealed) },
            )
        }
        ApplyRow(
                onRead = vm::readChannels,
                onApply = vm::applyChannels,
                busy = vm.busy.collectAsState().value
        )
    }
}

@Composable
private fun ChannelSlotEditor(
        editor: SettingsViewModel.ChannelSlotEditor,
        profileNames: List<String>,
        onName: (String) -> Unit,
        onRole: (ChannelRole) -> Unit,
        onUplink: (Boolean) -> Unit,
        onDownlink: (Boolean) -> Unit,
        onPrecision: (Int) -> Unit,
        onProfile: (Int) -> Unit,
        onPsk: (String) -> Unit,
        onRevealPsk: (Boolean) -> Unit,
) {
    val slot = editor.slot
    val isOpen = slot.psk.size() == 0
    HudCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), accent = slot.role.roleColor()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                            "Slot ${slot.index}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                    )
                    TacticalText(
                            "${if (isOpen) "Open" else "Encrypted"} · ${slot.role.roleLabel()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TacticalText(slot.role.roleLabel(), color = slot.role.roleColor())
            }
            // Role description shown inline so users understand what they're choosing
            Text(
                    slot.role.roleDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                    value = slot.name,
                    onValueChange = onName,
                    label = { Text("Channel name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
            )
            Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
            ) {
                RoleDropdown(slot.role, onRole, Modifier.weight(1f))
                NumberDropdown(
                        label = "Radio profile",
                        value = slot.phyProfileIndex,
                        options = profileNames.indices.toList().ifEmpty { (0..4).toList() },
                        onValue = onProfile,
                        modifier = Modifier.weight(1f),
                ) { value -> profileNames.getOrNull(value) ?: "Profile $value" }
            }
            OutlinedTextField(
                    value = editor.pskText,
                    onValueChange = onPsk,
                    label = { Text("Password (blank = open channel)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = editor.pskError != null,
                    visualTransformation =
                            if (editor.pskRevealed) VisualTransformation.None
                            else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onRevealPsk(!editor.pskRevealed) }) {
                            Icon(
                                    imageVector =
                                            if (editor.pskRevealed) Icons.Outlined.VisibilityOff
                                            else Icons.Outlined.Visibility,
                                    contentDescription =
                                            if (editor.pskRevealed) "Hide password"
                                            else "Reveal password",
                            )
                        }
                    },
            )
            if (editor.pskError != null) {
                Text(
                        editor.pskError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                        if (editor.pskText.isBlank())
                                "Open channel — anyone on the same frequency can send and receive."
                        else "Encrypted — only devices sharing this password can communicate here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionLabel("Location detail shared")
            Text(
                    when {
                        slot.positionPrecision == 0 ->
                                "Hidden — your position is not shared on this channel"
                        slot.positionPrecision <= 11 ->
                                "City level (~100 km) · ${slot.positionPrecision} bits"
                        slot.positionPrecision <= 16 ->
                                "Neighbourhood (~5 km) · ${slot.positionPrecision} bits"
                        slot.positionPrecision <= 21 ->
                                "Street level (~500 m) · ${slot.positionPrecision} bits"
                        slot.positionPrecision <= 28 ->
                                "Building level (~50 m) · ${slot.positionPrecision} bits"
                        else -> "Full precision (~1 m) · ${slot.positionPrecision} bits"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                    value = slot.positionPrecision.toFloat(),
                    onValueChange = { onPrecision(it.toInt().coerceIn(0, 32)) },
                    valueRange = 0f..32f,
                    steps = 31,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                        "Send on this channel",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                )
                Switch(checked = slot.uplinkEnabled, onCheckedChange = onUplink)
                Spacer(Modifier.size(16.dp))
                Text(
                        "Receive on this channel",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                )
                Switch(checked = slot.downlinkEnabled, onCheckedChange = onDownlink)
            }
        }
    }
}

@Composable
private fun NumberDropdown(
        label: String,
        value: Int,
        options: List<Int>,
        onValue: (Int) -> Unit,
        modifier: Modifier = Modifier,
        render: (Int) -> String = { it.toString() },
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label ${render(value)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                        text = { Text(render(option)) },
                        onClick = {
                            expanded = false
                            onValue(option)
                        },
                )
            }
        }
    }
}

@Composable
private fun RoleDropdown(
        value: ChannelRole,
        onValue: (ChannelRole) -> Unit,
        modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value.roleLabel())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChannelRole.values().filter { it != ChannelRole.UNRECOGNIZED }.forEach { role ->
                DropdownMenuItem(
                        text = {
                            Column {
                                Text(role.roleLabel(), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                        role.roleDescription(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onValue(role)
                        },
                )
            }
        }
    }
}

private fun profilePreset(index: Int): String =
        when (index) {
            0 -> "Long range / moderate speed"
            1 -> "Short range / fast"
            2 -> "Very long range / slow"
            3 -> "Medium range / moderate"
            4 -> "User Defined"
            else -> "Custom"
        }

/** Returns a plain-English summary of a LoRa PHY profile's real-world tradeoffs. */
private fun phyDescription(sf: Int, bwHz: Int, cr: Int): String {
    if (sf == 0 || bwHz == 0 || cr == 0) return "Not configured — set values below"
    // Approximate on-air data rate (bps) = SF × BW / 2^SF × 4/CR
    val bitrateKbps = sf.toDouble() * bwHz / (1L shl sf) * (4.0 / cr) / 1000.0
    val rangeStr =
            when {
                sf <= 7 -> "short range (~1–2 km)"
                sf == 8 -> "medium range (~2–4 km)"
                sf == 9 -> "medium range (~3–5 km)"
                sf == 10 -> "long range (~3–8 km)"
                sf == 11 -> "long range (~5–10 km)"
                else -> "very long range (~8–15 km)"
            }
    val speedStr =
            when {
                bitrateKbps >= 5.0 -> "fast (~%.0f kbps)".format(bitrateKbps)
                bitrateKbps >= 1.0 -> "moderate (~%.1f kbps)".format(bitrateKbps)
                else -> "slow (~%.2f kbps)".format(bitrateKbps)
            }
    return "$rangeStr · $speedStr"
}

private fun ChannelRole.roleLabel(): String =
        when (this) {
            ChannelRole.PRIMARY -> "Primary"
            ChannelRole.SECONDARY -> "Secondary"
            ChannelRole.DISABLED -> "Disabled"
            else -> name
        }

private fun ChannelRole.roleDescription(): String =
        when (this) {
            ChannelRole.PRIMARY -> "Main team channel — all mesh traffic flows here."
            ChannelRole.SECONDARY -> "Extra channel (command net, cross-team link, etc)."
            ChannelRole.DISABLED -> "Slot unused — no traffic sent or received."
            else -> ""
        }

@Composable
private fun ChannelRole.roleColor(): Color =
        when (this) {
            ChannelRole.PRIMARY -> MaterialTheme.colorScheme.primary
            ChannelRole.SECONDARY -> AccentAmber
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

// ── POSITION & POWER ───────────────────────────────────────────────────────────

@Composable
private fun PositionPowerSection(vm: SettingsViewModel) {
    val position by vm.position.collectAsState()
    val power by vm.power.collectAsState()
    LaunchedEffect(Unit) { vm.readPosition() }
    LaunchedEffect(Unit) { vm.readPower() }
    LaunchedEffect(Unit) { vm.readChannels() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Position broadcast")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = position.broadcastIntervalSecs.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { vm.updatePosition { copy(broadcastIntervalSecs = it) } }
                },
                label = { Text("Broadcast interval (s)") },
                keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                        ),
                modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = position.minTxIntervalSecs.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { vm.updatePosition { copy(minTxIntervalSecs = it) } }
                },
                label = { Text("Min TX gap (s)") },
                keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                        ),
                modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    "Smart beaconing",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                    checked = position.smartBroadcast,
                    onCheckedChange = { vm.updatePosition { copy(smartBroadcast = it) } }
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("GPS source")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    if (position.gpsSource == GpsSource.PHONE_GPS) "Phone GPS" else "Device GPS (L76K)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                    checked = position.gpsSource == GpsSource.PHONE_GPS,
                    onCheckedChange = { checked ->
                        vm.updatePosition {
                            copy(gpsSource = if (checked) GpsSource.PHONE_GPS else GpsSource.DEVICE_GPS)
                        }
                    }
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("Position broadcast channel")
        Spacer(Modifier.height(8.dp))
        Text(
            "Select which channel carries your GPS position. Use an encrypted channel shared with your team — avoid unencrypted channels to prevent enemy tracking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val channels by vm.channels.collectAsState()
        val activeSlotOptions = channels.slots
            .filter { it.slot.role != ChannelRole.DISABLED }
            .map { it.slot.index to it.slot.name }
        NumberDropdown(
            label = "Channel",
            value = position.positionChannelIndex,
            options = activeSlotOptions.map { it.first },
            onValue = { vm.updatePosition { copy(positionChannelIndex = it) } },
            modifier = Modifier.fillMaxWidth(),
        ) { value ->
            activeSlotOptions.firstOrNull { it.first == value }?.second ?: "Slot $value"
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel("Power saving")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    "GPS duty cycle (Phase 6+)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                    checked = power.gpsDutyCycle,
                    onCheckedChange = { vm.updatePower { copy(gpsDutyCycle = it) } }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
                value = power.displayOffSecs.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { vm.updatePower { copy(displayOffSecs = it) } }
                },
                label = { Text("Display off after (s, 0 = never)") },
                keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                        ),
                modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Brightness: ${power.brightnessPct}%")
        Slider(
                value = power.brightnessPct.toFloat(),
                onValueChange = { v ->
                    vm.updatePower { copy(brightnessPct = v.toInt()) }
                },
                valueRange = 0f..100f,
                steps = 19,
                modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        val busy = vm.busy.collectAsState().value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                    onClick = {
                        vm.readPosition()
                        vm.readPower()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
            ) { Text("Read") }
            Button(
                    onClick = {
                        vm.applyPower()
                        vm.applyPosition()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy
            ) { Text(if (busy) "Applying…" else "Apply") }
        }
    }
}

// ── SECURITY ──────────────────────────────────────────────────────────────────

@Composable
private fun SecuritySection(vm: SettingsViewModel) {
    val state by vm.security.collectAsState()
    LaunchedEffect(Unit) { vm.readSecurity() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Bonded devices: ${state.maxBonds}")
        Slider(
                value = state.maxBonds.toFloat(),
                onValueChange = {
                    vm.updateSecurity { copy(maxBonds = it.toInt().coerceIn(1, 4)) }
                },
                valueRange = 1f..4f,
                steps = 2,
        )
        ApplyRow(
                onRead = vm::readSecurity,
                onApply = vm::applySecurity,
                busy = vm.busy.collectAsState().value
        )
    }
}

// ── MODERATION ────────────────────────────────────────────────────────────────

@Composable
private fun ModerationSection(vm: SettingsViewModel) {
    val bans by vm.moderation.bans.collectAsState(initial = emptyList())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Active vote-kick bans")
        Spacer(Modifier.height(4.dp))
        if (bans.isEmpty()) {
            Text(
                    "No active bans. When 3 distinct players vote to kick someone, they appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            bans
                    .filter { it.isBanned || it.voteCount > 0 }
                    .sortedByDescending { it.lastVoteUnix }
                    .let { filtered ->
                        for (ban in filtered) {
                            HudCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                                "Node ${ban.targetNodeId.toString(16).uppercase().take(8)}",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                        )
                                        if (ban.isBanned) {
                                            Surface(
                                                    color =
                                                            MaterialTheme.colorScheme.error.copy(
                                                                    alpha = 0.12f
                                                            ),
                                                    shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                        "BANNED",
                                                        modifier =
                                                                Modifier.padding(
                                                                        horizontal = 8.dp,
                                                                        vertical = 2.dp
                                                                ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                            "Scope: ${scopeLabel(ban.scope)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                            "${ban.voteCount} / 3 votes · ${ban.voters.size} distinct voters",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val banTtl =
                                            (ban.lastVoteUnix + 86400) -
                                                    (System.currentTimeMillis() / 1000L)
                                    if (banTtl > 0) {
                                        Text(
                                                "Expires in ${banTtl / 3600}h ${(banTtl % 3600) / 60}m",
                                                style = MaterialTheme.typography.bodySmall,
                                                color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                                .copy(alpha = 0.7f),
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedButton(
                                            onClick = { vm.unbanNode(ban.targetNodeId, ban.scope) },
                                            modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Unban") }
                                }
                            }
                        }
                    }
        }
    }
}

private fun scopeLabel(scope: com.airtak.proto.Scope): String =
        when (scope) {
            com.airtak.proto.Scope.SCOPE_ALL -> "All players"
            com.airtak.proto.Scope.SCOPE_COMPANY -> "Company"
            com.airtak.proto.Scope.SCOPE_PLATOON -> "Platoon"
            com.airtak.proto.Scope.SCOPE_SQUAD -> "Squad"
            com.airtak.proto.Scope.SCOPE_FIRE_TEAM -> "Fireteam"
            else -> "All players"
        }

// ── APP (local prefs) ─────────────────────────────────────────────────────────

@Composable
private fun AppSection(vm: SettingsViewModel) {
    val mode by vm.themeMode.collectAsState()
    val tileUrl by vm.tileUrl.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Theme")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { tm ->
                val selected = mode == tm
                if (selected) {
                    Button(onClick = { vm.setThemeMode(tm) }, modifier = Modifier.weight(1f)) {
                        Text(tm.name)
                    }
                } else {
                    OutlinedButton(
                            onClick = { vm.setThemeMode(tm) },
                            modifier = Modifier.weight(1f)
                    ) { Text(tm.name) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
                value = tileUrl,
                onValueChange = vm::setTileUrl,
                label = { Text("Map tile server URL (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
                "Theme: ${mode.name}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── SHARE CONFIG ─────────────────────────────────────────────────────────────

@Composable
private fun ShareSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrUriString by remember { mutableStateOf("") }
    var importState by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    val busy = vm.busy.collectAsState().value

    // Pre-fill QR on first visit
    if (qrBitmap == null) {
        val config = vm.buildShareableConfig()
        val uri = vm.encodeConfigToUri(config)
        qrUriString = uri
        qrBitmap = vm.renderQrBitmap(uri)
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val decoded = vm.decodeConfigFromUri(result.contents)
            if (decoded != null) {
                importState = ImportState.Ready(decoded)
            } else {
                importState = ImportState.Error("The scanned QR code does not contain a valid AirTAK config.")
            }
        }
        // result.contents == null means the user cancelled
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        // ── Export ──────────────────────────────────────────────────────────
        SectionLabel("Export config as QR code")
        Spacer(Modifier.height(12.dp))

        qrBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR code with AirTAK config",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(16.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ── Import ──────────────────────────────────────────────────────────
        SectionLabel("Import config from QR code")

        Spacer(Modifier.height(12.dp))

        when (val state = importState) {
            is ImportState.Idle -> {
                Button(
                    onClick = {
                        val options = ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan an AirTAK config QR code")
                            .setBeepEnabled(false)
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
            }
            is ImportState.Error -> {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        importState = ImportState.Idle
                        val options = ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan an AirTAK config QR code")
                            .setBeepEnabled(false)
                        scanLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Try again") }
            }
            is ImportState.Ready -> {
                val cfg = state.config
                HudCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Import summary",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (cfg.hasLora()) {
                            val lora = cfg.lora
                            Text(
                                "LoRa: ${lora.region.name}, TX ${lora.txPowerDbm} dBm, ${lora.profilesCount} PHY profiles",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (cfg.hasChannels()) {
                            val ch = cfg.channels
                            val activeCount = ch.slotsList.count { it.role != ChannelRole.DISABLED }
                            val encryptedCount = ch.slotsList.count { it.psk.size() > 0 }
                            Text(
                                "Channels: $activeCount active, $encryptedCount with PSK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (cfg.hasGame()) {
                            val game = cfg.game
                            val unitParts = listOfNotNull(
                                game.companyId.takeIf { it.isNotBlank() }?.let { "Company: $it" },
                                game.platoonId.takeIf { it.isNotBlank() }?.let { "Platoon: $it" },
                                game.squadId.takeIf { it.isNotBlank() }?.let { "Squad: $it" },
                                game.fireteamId.takeIf { it.isNotBlank() }?.let { "Fireteam: $it" },
                            )
                            if (unitParts.isNotEmpty()) {
                                Text(
                                    unitParts.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { importState = ImportState.Idle },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            vm.importShareableConfig(cfg)
                            importState = ImportState.Applied
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text(if (busy) "Applying…" else "Apply to device") }
                }

                RadioRestartBanner()
            }
            is ImportState.Applied -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = StatusActive,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Config applied! LoRa and channel settings have been pushed to the device. A radio restart may be in progress.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusActive,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { importState = ImportState.Idle },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan another") }
            }
        }
    }
}

private sealed class ImportState {
    data object Idle : ImportState()
    data class Ready(val config: com.airtak.proto.ShareableConfig) : ImportState()
    data class Error(val message: String) : ImportState()
    data object Applied : ImportState()
}

// ── ABOUT ─────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSection(vm: SettingsViewModel, onFirmwareSelected: ((Uri) -> Unit)?) {
    val status by vm.deviceStatus.collectAsState()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFirmwareSelected?.invoke(it) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        HudCard(Modifier.fillMaxWidth()) {
            Column {
                LabelValue("Firmware", status?.firmwareVersion ?: "—")
                LabelValue("Board", status?.boardId ?: "—")
                LabelValue(
                        "Node ID",
                        status?.nodeId?.toString(16)?.uppercase()?.padStart(8, '0') ?: "—"
                )
                LabelValue("BT MAC", status?.bluetoothAddress ?: "—")
                LabelValue("Uptime", status?.uptimeSeconds?.let { "$it s" } ?: "—")
                LabelValue("Mesh nodes", status?.meshNodeCount?.toString() ?: "—")
            }
        }
        Spacer(Modifier.height(16.dp))
        RadioRestartBanner()
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
                onClick = { vm.sendAdmin(AdminCommand.RADIO_RESTART) },
                modifier = Modifier.fillMaxWidth(),
        ) { Text("Radio Restart") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
                onClick = { vm.sendAdmin(AdminCommand.REBOOT) },
                modifier = Modifier.fillMaxWidth(),
        ) { Text("Reboot Device") }
        Spacer(Modifier.height(8.dp))
        Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
        ) { Text("Update Firmware") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
                onClick = { openBatteryOptimizationSettings(context) },
                modifier = Modifier.fillMaxWidth(),
        ) { Text("Battery Optimization") }
        Spacer(Modifier.height(24.dp))
        Text(
                "Factory reset requires a HMAC confirm-token and ships in Phase 6.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intents =
            listOf(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    },
            )
    intents.firstOrNull { it.resolveActivity(context.packageManager) != null }?.let { intent ->
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
        )
        TacticalText(value, color = MaterialTheme.colorScheme.onSurface)
    }
}

// Kinds of slots presented in the unit-hierarchy dropdown.
private enum class UnitChannelKind {
    GlobalMesh, // slot 0 — Meshtastic-compatible global; not selectable for unit scope
    GlobalTactical, // slot 1 — AirTAK-wide tactical; not selectable for unit scope
    AudioReserved, // slot 2 — reserved for Phase 7 PTT audio; not selectable
    UnitScoped, // slots 3–7 with a non-DISABLED role — selectable
    Available, // slots 3–7 currently DISABLED — pickable via "Set up"
}

private data class UnitChannelOption(
        val index: Int,
        val name: String,
        val kind: UnitChannelKind,
        val encrypted: Boolean,
        val hasPsk: Boolean,
        val role: ChannelRole,
)

@Composable
private fun UnitChannelDropdown(
        level: String,
        unitLabel: String,
        selectedSlot: Int,
        channelOptions: List<UnitChannelOption>,
        claimedSlots: Map<Int, String>,
        onSelect: (Int) -> Unit,
        onSetupNewChannel: () -> Unit,
        onManageChannels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val selectedOption = channelOptions.firstOrNull { it.index == selectedSlot }
    val buttonLabel =
            when {
                selectedSlot < 0 -> "Channel: none"
                selectedOption == null -> "Slot $selectedSlot"
                selectedOption.kind == UnitChannelKind.Available ->
                        "Slot ${selectedOption.index} — not yet set up"
                else ->
                        "${selectedOption.name.ifBlank { "Ch ${selectedOption.index}" }} (slot ${selectedOption.index})"
            }

    val tactical = channelOptions.filter { it.kind == UnitChannelKind.UnitScoped }
    val available = channelOptions.filter { it.kind == UnitChannelKind.Available }
    val reserved =
            channelOptions.filter {
                it.kind == UnitChannelKind.GlobalMesh ||
                        it.kind == UnitChannelKind.GlobalTactical ||
                        it.kind == UnitChannelKind.AudioReserved
            }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(buttonLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "None" — clear binding
            DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                    "None",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                            if (selectedSlot < 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                    "Use global channels for this unit level.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(-1)
                    },
            )

            if (tactical.isNotEmpty()) {
                HorizontalDivider()
                DropdownSectionLabel("Configured channels")
                tactical.forEach { option ->
                    val claimedBy = claimedSlots[option.index]
                    val claimedByOther = claimedBy != null && claimedBy != level
                    DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                            "${option.name.ifBlank { "Ch ${option.index}" }} · slot ${option.index}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color =
                                                    if (option.index == selectedSlot)
                                                            MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                    )
                                    val subtitle = buildString {
                                        append(
                                                if (option.encrypted) "Encrypted"
                                                else "Open (no PSK)"
                                        )
                                        if (claimedByOther)
                                                append(" · already linked to ${claimedBy}")
                                    }
                                    Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color =
                                                    if (claimedByOther) AccentAmber
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(option.index)
                            },
                    )
                }
            }

            HorizontalDivider()
            if (available.isNotEmpty()) {
                DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                        "Set up a new channel for $unitLabel…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                        "Uses slot ${available.first().index}. You'll set the password next.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSetupNewChannel()
                        },
                )
            } else if (tactical.size < MAX_UNIT_SLOTS) {
                // Should not happen given 5 tactical slots; defensive only.
                DropdownMenuItem(
                        text = {
                            Text(
                                    "No free slots — open Channels…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = false,
                        onClick = {},
                )
            }
            DropdownMenuItem(
                    text = {
                        Text(
                                "Manage channels…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onManageChannels()
                    },
            )

            if (reserved.isNotEmpty()) {
                HorizontalDivider()
                DropdownSectionLabel("Reserved slots (not selectable)")
                reserved.forEach { option ->
                    val subtitle =
                            when (option.kind) {
                                UnitChannelKind.GlobalMesh ->
                                        "Mesh-wide / Meshtastic interop. All players share this channel."
                                UnitChannelKind.GlobalTactical ->
                                        "AirTAK-wide tactical traffic. All players share this channel."
                                UnitChannelKind.AudioReserved -> "Reserved for PTT audio (Phase 7)."
                                else -> ""
                            }
                    DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                            "${option.name.ifBlank { "Ch ${option.index}" }} · slot ${option.index}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            enabled = false,
                            onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownSectionLabel(text: String) {
    Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private const val MAX_UNIT_SLOTS = 5 // slots 3–7
