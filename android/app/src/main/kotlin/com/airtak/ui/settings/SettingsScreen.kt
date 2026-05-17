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
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.proto.AdminCommand
import com.airtak.proto.ChannelRole
import com.airtak.proto.ChannelSlot
import com.airtak.proto.LoraRegion
import com.airtak.proto.PhyProfile
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.ThemeMode

private enum class Section(val title: String, val subtitle: String) {
    Device("Device",     "Callsign, long/short name"),
    Lora("LoRa Radio",   "Region, TX power, PHY profiles"),
    Channels("Channels", "8 channel slots with PSK"),
    Position("Position", "Broadcast interval, smart-beacon"),
    Power("Power",       "GPS duty cycle, display"),
    Game("Game",         "Callsign, unit hierarchy, hit button"),
    Security("Security", "BLE bond count"),
    App("Application",   "Theme, tile server, defaults"),
    About("About",       "Diagnostics, factory reset"),
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    var current by remember { mutableStateOf<Section?>(null) }

    when (val sec = current) {
        null -> SectionList(onPick = { current = it })
        else -> {
            val title = sec.title
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                TopRow(title = title, onBack = { current = null })
                when (sec) {
                    Section.Device   -> DeviceSection(viewModel)
                    Section.Lora     -> LoraSection(viewModel)
                    Section.Channels -> ChannelsSection(viewModel)
                    Section.Position -> PositionSection(viewModel)
                    Section.Power    -> PowerSection(viewModel)
                    Section.Game     -> GameSection(viewModel)
                    Section.Security -> SecuritySection(viewModel)
                    Section.App      -> AppSection(viewModel)
                    Section.About    -> AboutSection(viewModel)
                }
            }
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.size(12.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge,
             color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun SectionList(onPick: (Section) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("CONFIG", style = MaterialTheme.typography.headlineMedium,
                 color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
        }
        items(Section.entries) { sec ->
            HudCard(modifier = Modifier.fillMaxWidth().clickable { onPick(sec) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sec.title, style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onSurface)
                        TacticalText(sec.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                         tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun RadioRestartBanner() {
    Row(
        Modifier
            .fillMaxWidth()
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
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRead, modifier = Modifier.weight(1f), enabled = !busy) {
            Text("Read")
        }
        Button(onClick = onApply, modifier = Modifier.weight(1f), enabled = !busy) {
            Text(if (busy) "Applying…" else "Apply")
        }
    }
}

// ── DEVICE ────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceSection(vm: SettingsViewModel) {
    val state by vm.device.collectAsState()
    LaunchedEffect(Unit) { vm.readDevice() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Identification")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.longName,
            onValueChange = { vm.updateDevice { copy(longName = it.take(39)) } },
            label = { Text("Long name (max 39 B)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.shortName,
            onValueChange = { vm.updateDevice { copy(shortName = it.take(4)) } },
            label = { Text("Short name (max 4 B)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        ApplyRow(onRead = vm::readDevice, onApply = vm::applyDevice, busy = vm.busy.collectAsState().value)
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
                    DropdownMenuItem(text = { Text(r.name) }, onClick = {
                        vm.updateLora { copy(region = r) }
                        expanded = false
                    })
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
        ApplyRow(onRead = vm::readLora, onApply = vm::applyLora, busy = vm.busy.collectAsState().value)
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
                7  -> "Fastest, short range (SF7)"
                8  -> "Fast, short-medium (SF8)"
                9  -> "Balanced (SF9)"
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
                else   -> "${bw / 1000} kHz"
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
private fun ChannelsSection(vm: SettingsViewModel) {
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
                    "Each slot can serve as a dedicated radio channel for a different unit level — squad, platoon, or company. " +
                    "Give the slot a recognisable name (e.g. \"Squad 1\", \"Platoon A\", \"Company Red\") and set a shared password. " +
                    "Every device in that unit must use the same slot index, name, and password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Suggested layout:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Slot 0 — Mesh (primary, open) — all players\n" +
                    "Slot 1 — Game (admin events, match flow)\n" +
                    "Slot 2 — Squad  (e.g. \"Squad 1\", squad PSK)\n" +
                    "Slot 3 — Platoon (e.g. \"Platoon A\", platoon PSK)\n" +
                    "Slot 4 — Team   (e.g. \"Team Red\", team PSK)",
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

        val mixedActiveProfiles = state.slots
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
            ChannelSlotEditor(
                editor = editor,
                profileNames = lora.profiles.mapIndexed { i, profile -> "${i}: ${profile.name.ifBlank { profilePreset(i) }}" },
                onName = { name -> vm.updateChannelSlot(index) { setName(name.take(16)) } },
                onRole = { role -> vm.updateChannelSlot(index) { setRole(role) } },
                onUplink = { enabled -> vm.updateChannelSlot(index) { setUplinkEnabled(enabled) } },
                onDownlink = { enabled -> vm.updateChannelSlot(index) { setDownlinkEnabled(enabled) } },
                onPrecision = { bits -> vm.updateChannelSlot(index) { setPositionPrecision(bits) } },
                onProfile = { profileIndex -> vm.updateChannelSlot(index) { setPhyProfileIndex(profileIndex) } },
                onPsk = { text -> vm.updateChannelPsk(index, text) },
                onRevealPsk = { revealed -> vm.setChannelPskRevealed(index, revealed) },
            )
        }
        ApplyRow(onRead = vm::readChannels, onApply = vm::applyChannels, busy = vm.busy.collectAsState().value)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                visualTransformation = if (editor.pskRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { onRevealPsk(!editor.pskRevealed) }) {
                        Icon(
                            imageVector = if (editor.pskRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (editor.pskRevealed) "Hide password" else "Reveal password",
                        )
                    }
                },
            )
            if (editor.pskError != null) {
                Text(editor.pskError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    if (editor.pskText.isBlank())
                        "Open channel — anyone on the same frequency can send and receive."
                    else
                        "Encrypted — only devices sharing this password can communicate here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionLabel("Location detail shared")
            Text(
                when {
                    slot.positionPrecision == 0  -> "Hidden — your position is not shared on this channel"
                    slot.positionPrecision <= 11 -> "City level (~100 km) · ${slot.positionPrecision} bits"
                    slot.positionPrecision <= 16 -> "Neighbourhood (~5 km) · ${slot.positionPrecision} bits"
                    slot.positionPrecision <= 21 -> "Street level (~500 m) · ${slot.positionPrecision} bits"
                    slot.positionPrecision <= 28 -> "Building level (~50 m) · ${slot.positionPrecision} bits"
                    else                         -> "Full precision (~1 m) · ${slot.positionPrecision} bits"
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
                Text("Send on this channel", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = slot.uplinkEnabled, onCheckedChange = onUplink)
                Spacer(Modifier.size(16.dp))
                Text("Receive on this channel", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
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
private fun RoleDropdown(value: ChannelRole, onValue: (ChannelRole) -> Unit, modifier: Modifier = Modifier) {
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

private fun profilePreset(index: Int): String = when (index) {
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
    val rangeStr = when {
        sf <= 7  -> "short range (~1–2 km)"
        sf == 8  -> "medium range (~2–4 km)"
        sf == 9  -> "medium range (~3–5 km)"
        sf == 10 -> "long range (~3–8 km)"
        sf == 11 -> "long range (~5–10 km)"
        else     -> "very long range (~8–15 km)"
    }
    val speedStr = when {
        bitrateKbps >= 5.0 -> "fast (~%.0f kbps)".format(bitrateKbps)
        bitrateKbps >= 1.0 -> "moderate (~%.1f kbps)".format(bitrateKbps)
        else               -> "slow (~%.2f kbps)".format(bitrateKbps)
    }
    return "$rangeStr · $speedStr"
}

private fun ChannelRole.roleLabel(): String = when (this) {
    ChannelRole.PRIMARY   -> "Primary"
    ChannelRole.SECONDARY -> "Secondary"
    ChannelRole.DISABLED  -> "Disabled"
    else -> name
}

private fun ChannelRole.roleDescription(): String = when (this) {
    ChannelRole.PRIMARY   -> "Main team channel — all mesh traffic flows here."
    ChannelRole.SECONDARY -> "Extra channel (command net, cross-team link, etc)."
    ChannelRole.DISABLED  -> "Slot unused — no traffic sent or received."
    else -> ""
}

@Composable
private fun ChannelRole.roleColor(): Color = when (this) {
    ChannelRole.PRIMARY   -> MaterialTheme.colorScheme.primary
    ChannelRole.SECONDARY -> AccentAmber
    else                  -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ── POSITION ──────────────────────────────────────────────────────────────────

@Composable
private fun PositionSection(vm: SettingsViewModel) {
    val state by vm.position.collectAsState()
    LaunchedEffect(Unit) { vm.readPosition() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = state.broadcastIntervalSecs.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { vm.updatePosition { copy(broadcastIntervalSecs = it) } }
            },
            label = { Text("Broadcast interval (s)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.minTxIntervalSecs.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { vm.updatePosition { copy(minTxIntervalSecs = it) } }
            },
            label = { Text("Min TX gap (s)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smart beaconing", style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = state.smartBroadcast,
                   onCheckedChange = { vm.updatePosition { copy(smartBroadcast = it) } })
        }
        ApplyRow(onRead = vm::readPosition, onApply = vm::applyPosition, busy = vm.busy.collectAsState().value)
    }
}

// ── POWER ─────────────────────────────────────────────────────────────────────

@Composable
private fun PowerSection(vm: SettingsViewModel) {
    val state by vm.power.collectAsState()
    LaunchedEffect(Unit) { vm.readPower() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS duty cycle (Phase 6+)", style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = state.gpsDutyCycle,
                   onCheckedChange = { vm.updatePower { copy(gpsDutyCycle = it) } })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.displayOffSecs.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { vm.updatePower { copy(displayOffSecs = it) } }
            },
            label = { Text("Display off after (s, 0 = never)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        ApplyRow(onRead = vm::readPower, onApply = vm::applyPower, busy = vm.busy.collectAsState().value)
    }
}

// ── GAME ──────────────────────────────────────────────────────────────────────

@Composable
private fun GameSection(vm: SettingsViewModel) {
    val state by vm.game.collectAsState()
    LaunchedEffect(Unit) { vm.readGame() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel("Callsign")
        OutlinedTextField(
            value = state.callsign,
            onValueChange = { vm.updateGame { copy(callsign = it.take(16)) } },
            label = { Text("Callsign (max 16 B)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Unit hierarchy (optional, self-organized)")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.companyId,
            onValueChange = { vm.updateGame { copy(companyId = it.take(32)) } },
            label = { Text("Company (e.g. \"1st Company\")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.platoonId,
            onValueChange = { vm.updateGame { copy(platoonId = it.take(32)) } },
            label = { Text("Platoon (e.g. \"Red Platoon\")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.squadId,
            onValueChange = { vm.updateGame { copy(squadId = it.take(32)) } },
            label = { Text("Squad (e.g. \"1st Squad\")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.fireteamId,
            onValueChange = { vm.updateGame { copy(fireteamId = it.take(32)) } },
            label = { Text("Fireteam (e.g. \"Alpha\")") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        TacticalText(
            "All fields are optional and self-assigned. Nodes sharing the same unit ID will be grouped in the roster.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Appearance")
        OutlinedTextField(
            value = state.playerColorRgb.toString(16).uppercase().padStart(6, '0'),
            onValueChange = { hex ->
                hex.take(6).toIntOrNull(16)?.let { vm.updateGame { copy(playerColorRgb = it) } }
            },
            label = { Text("Player color (hex RRGGBB)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Device button = HIT toggle", style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = state.hitButtonEnabled,
                   onCheckedChange = { vm.updateGame { copy(hitButtonEnabled = it) } })
        }
        TacticalText(
            "When enabled, long-press the device button to toggle HIT status.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ApplyRow(onRead = vm::readGame, onApply = vm::applyGame, busy = vm.busy.collectAsState().value)
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
            onValueChange = { vm.updateSecurity { copy(maxBonds = it.toInt().coerceIn(1, 4)) } },
            valueRange = 1f..4f,
            steps = 2,
        )
        ApplyRow(onRead = vm::readSecurity, onApply = vm::applySecurity,
                 busy = vm.busy.collectAsState().value)
    }
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
                    OutlinedButton(onClick = { vm.setThemeMode(tm) }, modifier = Modifier.weight(1f)) {
                        Text(tm.name)
                    }
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

// ── ABOUT ─────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    val status by vm.deviceStatus.collectAsState()
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        HudCard(Modifier.fillMaxWidth()) {
            Column {
                LabelValue("Firmware", status?.firmwareVersion ?: "—")
                LabelValue("Node ID", status?.nodeId?.toString(16)?.uppercase()?.padStart(8, '0') ?: "—")
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
    val intents = listOf(
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
        Text(label, style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TacticalText(value, color = MaterialTheme.colorScheme.onSurface)
    }
}
