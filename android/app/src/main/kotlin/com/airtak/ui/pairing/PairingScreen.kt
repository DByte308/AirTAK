// AirTAK Android — ui/pairing/PairingScreen.kt
// Roadmap: 3.2 (BLE), 3.7 (foreground service)
//
// Device management screen. When a device is already bonded/connected it shows
// a "Current Device" panel with Disconnect and Forget options. Scanning for a
// new device is opt-in (user taps "Pair different device").

package com.airtak.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.DiscoveredDevice
import com.airtak.data.ble.LinkState
import com.airtak.data.ble.service.LinkService
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@Composable
fun PairingScreen(onDone: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val link by viewModel.linkState.collectAsState()
    val devices by viewModel.discovered.collectAsState()
    val bondedAddress = viewModel.bondedAddress
    val context = LocalContext.current

    // showScanner = true only when user explicitly wants to pair a new device.
    var showScanner by remember { mutableStateOf(bondedAddress == null) }

    // Auto-start scan only when entering with no bonded device.
    DisposableEffect(showScanner) {
        if (showScanner) viewModel.startScan()
        onDispose { if (showScanner) viewModel.stopScan() }
    }

    // When a new device has just connected (was scanning → now Connected), pop the screen.
    DisposableEffect(link) {
        if (showScanner && link is LinkState.Connected) {
            LinkService.start(context)
            onDone()
        }
        onDispose {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            if (bondedAddress != null) stringResource(R.string.pair_heading_link)
            else stringResource(R.string.pair_heading),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))

        if (bondedAddress != null && !showScanner) {
            // ── Current device panel ─────────────────────────────────────────
            Text(
                stringResource(R.string.pair_connected_body),
                    
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.pair_current_device))
            Spacer(Modifier.height(8.dp))

            CurrentDeviceCard(link = link, address = bondedAddress)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (link is LinkState.Connected) viewModel.disconnect()
                        else viewModel.reconnect()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (link is LinkState.Connected) Icons.Outlined.LinkOff
                        else Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (link is LinkState.Connected) stringResource(R.string.action_disconnect)
                        else stringResource(R.string.pair_reconnect),
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.forget(); showScanner = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusHit),
                ) {
                    Icon(Icons.Outlined.BluetoothDisabled, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.pair_forget))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showScanner = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pair_different_device))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.action_done)) }
        } else {
            // ── Scanner panel ────────────────────────────────────────────────
            Text(
                stringResource(R.string.pair_subtitle),
                    
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.pair_discovered))
            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty()) {
                HudCard {
                    Text(
                        when (link) {
                            is LinkState.Scanning -> stringResource(R.string.pair_scanning)
                            else -> stringResource(R.string.pair_no_devices)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device, onSelect = { viewModel.connect(device.address) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.startScan() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pair_rescan))
                }
                Button(
                    onClick = {
                        if (bondedAddress != null) showScanner = false else onDone()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text(if (bondedAddress != null) stringResource(R.string.action_back) else stringResource(R.string.action_done)) }
            }
        }
    }
}

@Composable
private fun CurrentDeviceCard(link: LinkState, address: String) {
    val (statusColor, statusLabel) = when (link) {
        is LinkState.Connected    -> StatusActive to stringResource(R.string.pair_status_linked)
        is LinkState.Connecting   -> MaterialTheme.colorScheme.primary to stringResource(R.string.pair_status_linking)
        is LinkState.Reconnecting -> MaterialTheme.colorScheme.primary to stringResource(R.string.pair_status_reacquiring)
        else                      -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.pair_status_offline)
    }
    HudCard(modifier = Modifier.fillMaxWidth(), accent = statusColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Router,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.pair_transponder),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TacticalText(address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onSelect: () -> Unit) {
    HudCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(StatusActive),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pair_airtak_device),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TacticalText(
                    device.address,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TacticalText(stringResource(R.string.rssi_dbm, device.rssi), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val ble: BleRepository,
) : ViewModel() {
    val linkState = ble.linkState
    val discovered = ble.discovered
    val bondedAddress: String? get() = ble.bondedAddress()
    fun startScan()  { if (ble.hasRequiredPermissions()) ble.startScan() }
    fun stopScan()   { ble.stopScan() }
    fun connect(address: String) { ble.connect(address) }
    fun disconnect() { ble.disconnect() }
    fun reconnect()  { ble.reconnectBonded() }
    fun forget()     { ble.forget() }
}

