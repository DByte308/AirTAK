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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.config.ConfigRepository
import com.airtak.data.prefs.AppPreferences
import com.airtak.proto.AdminCommand
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ChannelSlot
import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceConfig
import com.airtak.proto.GameConfig
import com.airtak.proto.LoraConfig
import com.airtak.proto.LoraRegion
import com.airtak.proto.PositionConfig
import com.airtak.proto.PowerConfig
import com.airtak.proto.SecurityConfig
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private enum class Section(val title: String, val subtitle: String) {
    Device("Device",     "Callsign, long/short name"),
    Lora("LoRa Radio",   "Region, TX power, PHY profiles"),
    Channels("Channels", "8 channel slots with PSK"),
    Position("Position", "Broadcast interval, smart-beacon"),
    Power("Power",       "GPS duty cycle, display"),
    Game("Game",         "Player callsign, team, admin"),
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
    Column(Modifier.padding(vertical = 8.dp)) {
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
    Column(Modifier.padding(vertical = 8.dp)) {
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
                Column {
                    Text("$i  ${p.name}", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.onSurface)
                    TacticalText("SF${p.spreadingFactor}  BW${p.bandwidthHz / 1000}k  CR4/${p.codingRate}",
                                 color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        ApplyRow(onRead = vm::readLora, onApply = vm::applyLora, busy = vm.busy.collectAsState().value)
    }
}

// ── CHANNELS ──────────────────────────────────────────────────────────────────

@Composable
private fun ChannelsSection(vm: SettingsViewModel) {
    val state by vm.channels.collectAsState()
    LaunchedEffect(Unit) { vm.readChannels() }
    Column(Modifier.padding(vertical = 8.dp)) {
        RadioRestartBanner()
        Spacer(Modifier.height(16.dp))
        state.slots.forEach { slot ->
            HudCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Slot ${slot.index}  ${slot.name}",
                             style = MaterialTheme.typography.titleSmall,
                             color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        TacticalText(slot.role.name, color = MaterialTheme.colorScheme.primary)
                    }
                    TacticalText("PSK ${slot.psk.size()} B  ·  PHY ${slot.phyProfileIndex}  ·  precision ${slot.positionPrecision} bit",
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "Slot editing is read-only in Phase 3. PSK regeneration and slot-role changes ship in Phase 4.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        ApplyRow(onRead = vm::readChannels, onApply = { /* no-op until Phase 4 */ }, busy = false)
    }
}

// ── POSITION ──────────────────────────────────────────────────────────────────

@Composable
private fun PositionSection(vm: SettingsViewModel) {
    val state by vm.position.collectAsState()
    LaunchedEffect(Unit) { vm.readPosition() }
    Column(Modifier.padding(vertical = 8.dp)) {
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
    Column(Modifier.padding(vertical = 8.dp)) {
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
    Column(Modifier.padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = state.callsign,
            onValueChange = { vm.updateGame { copy(callsign = it.take(16)) } },
            label = { Text("Callsign (max 16 B)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.teamId.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { vm.updateGame { copy(teamId = it) } }
            },
            label = { Text("Team ID (0 = spectator)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Admin role", style = MaterialTheme.typography.bodyMedium,
                 modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = state.isAdmin,
                   onCheckedChange = { vm.updateGame { copy(isAdmin = it) } })
        }
        ApplyRow(onRead = vm::readGame, onApply = vm::applyGame, busy = vm.busy.collectAsState().value)
    }
}

// ── SECURITY ──────────────────────────────────────────────────────────────────

@Composable
private fun SecuritySection(vm: SettingsViewModel) {
    val state by vm.security.collectAsState()
    LaunchedEffect(Unit) { vm.readSecurity() }
    Column(Modifier.padding(vertical = 8.dp)) {
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

    Column(Modifier.padding(vertical = 8.dp)) {
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
    Column(Modifier.padding(vertical = 8.dp)) {
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
        Spacer(Modifier.height(24.dp))
        Text(
            "Factory reset requires a HMAC confirm-token and ships in Phase 6.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
