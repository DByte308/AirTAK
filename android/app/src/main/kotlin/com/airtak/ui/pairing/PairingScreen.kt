// AirTAK Android — ui/pairing/PairingScreen.kt
// Roadmap: 3.2 (BLE), 3.7 (foreground service)
//
// First-run pairing wizard. The user sees a tactical-styled list of scanned
// AirTAK devices and a 6-digit numeric-comparison hint. Tapping a device
// triggers connectGatt; the OS shows its bonding dialog. On link-up the
// LinkService is started and the wizard pops.

package com.airtak.ui.pairing

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.DiscoveredDevice
import com.airtak.data.ble.LinkState
import com.airtak.data.ble.service.LinkService
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.StatusActive
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@Composable
fun PairingScreen(onDone: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val link by viewModel.linkState.collectAsState()
    val devices by viewModel.discovered.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    if (link is LinkState.Connected) {
        DisposableEffect(Unit) {
            LinkService.start(context)
            onDone()
            onDispose {}
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            "PAIR AIRTAK DEVICE",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Power on your T-Echo Plus and confirm the 6-digit numeric comparison " +
                "code shown on its e-ink display matches the prompt that appears on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        SectionLabel("Discovered")
        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            HudCard {
                Text(
                    when (link) {
                        is LinkState.Scanning -> "Scanning for AirTAK transponders…"
                        else -> "No devices yet. Bring your device within range and try again."
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
                Text("Rescan")
            }
            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("Done") }
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
                    device.name?.takeIf { it.isNotBlank() } ?: "AirTAK device",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TacticalText(
                    device.address,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TacticalText("${device.rssi} dBm", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val ble: BleRepository,
) : ViewModel() {
    val linkState = ble.linkState
    val discovered = ble.discovered
    fun startScan() { if (ble.hasRequiredPermissions()) ble.startScan() }
    fun stopScan()  { ble.stopScan() }
    fun connect(address: String) { ble.connect(address) }
}
