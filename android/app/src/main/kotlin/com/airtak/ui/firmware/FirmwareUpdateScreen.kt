// AirTAK Android — ui/firmware/FirmwareUpdateScreen.kt
// Planning: §6.1, §8.2 | Roadmap: 6.1 (revised)
//
// Full-screen firmware update progress UI. Receives a firmware URI via
// navigation argument, reads the file, and drives the update through
// FirmwareUpdateViewModel. Displays the state machine from §8.2.

package com.airtak.ui.firmware

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.config.constants.BYTES_PER_KB
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit

@Composable
fun FirmwareUpdateScreen(
    firmwareUri: String?,
    onDone: () -> Unit,
    viewModel: FirmwareUpdateViewModel = hiltViewModel(),
) {
    val progress by viewModel.progress.collectAsState()
    val state by viewModel.state.collectAsState()
    val debugLog by viewModel.debugLog.collectAsState()
    var selectedUri by remember { mutableStateOf<Uri?>(firmwareUri?.let { Uri.parse(it) }) }
    // Track which transport the user chose for the currently picked file so
    // the LaunchedEffect below dispatches to the right ViewModel entry point.
    var transportMode by remember { mutableStateOf(TransportMode.Ble) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val s = state) {
                is FirmwareUpdateViewModel.FwUpdateState.Idle -> {
                    Icon(
                        Icons.Outlined.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.fw_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.fw_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        transportMode = TransportMode.Ble
                        filePicker.launch("*/*")
                    }) {
                        Text(stringResource(R.string.fw_via_ble))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        transportMode = TransportMode.Usb
                        filePicker.launch("*/*")
                    }) {
                        Text(stringResource(R.string.fw_via_usb))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDone) {
                        Text(stringResource(R.string.action_back))
                    }
                }

                is FirmwareUpdateViewModel.FwUpdateState.InProgress -> {
                    FirmwareProgress(progress)
                    Spacer(Modifier.height(16.dp))
                    DebugLogView(debugLog)
                }

                is FirmwareUpdateViewModel.FwUpdateState.Done -> {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StatusActive,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.fw_updated_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = StatusActive,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.fw_updated_body),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        viewModel.reset()
                        onDone()
                    }) {
                        Text(stringResource(R.string.action_done))
                    }
                }

                is FirmwareUpdateViewModel.FwUpdateState.Error -> {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = StatusHit,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.fw_failed_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = StatusHit,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    DebugLogView(debugLog)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        viewModel.reset()
                        selectedUri = null
                    }) {
                        Text(stringResource(R.string.action_try_again))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        viewModel.reset()
                        onDone()
                    }) {
                        Text(stringResource(R.string.fw_back_to_settings))
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedUri) {
        selectedUri?.let { uri ->
            when (transportMode) {
                TransportMode.Ble -> viewModel.startUpdate(uri)
                TransportMode.Usb -> viewModel.startUpdateOverUsb(uri)
            }
        }
    }
}

private enum class TransportMode { Ble, Usb }

@Composable
private fun FirmwareProgress(progress: UpdateProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (progress) {
            is UpdateProgress.Initializing -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    progress.step,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            is UpdateProgress.Transferring -> {
                val pct = if (progress.totalBytes > 0)
                    (progress.bytesWritten * 100 / progress.totalBytes).toInt()
                else 0

                Text(
                    stringResource(R.string.fw_transferring),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.percent_value, pct),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (progress.totalBytes > 0) progress.bytesWritten.toFloat() / progress.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (progress.etaSeconds > 0) {
                    Text(
                        stringResource(R.string.fw_eta_remaining, progress.etaSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        R.string.byte_pair,
                        formatBytes(progress.bytesWritten),
                        formatBytes(progress.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateProgress.Verifying -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.fw_verifying), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
            is UpdateProgress.Rebooting -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.fw_rebooting),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.fw_reconnect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateProgress.Reconnected -> {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = StatusActive,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.fw_updated_to, progress.newVersion),
                    style = MaterialTheme.typography.titleMedium,
                    color = StatusActive,
                )
            }
            is UpdateProgress.Error -> {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = StatusHit,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = StatusHit,
                )
            }
            is UpdateProgress.Done -> {
                // handled by state
            }
            is UpdateProgress.Debug -> {
                // shown in DebugLogView; no inline rendering
            }
        }
    }
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return stringResource(R.string.bytes_b, bytes)
    val kb = bytes / BYTES_PER_KB
    return stringResource(R.string.bytes_kb, kb)
}

@Composable
private fun DebugLogView(log: List<String>) {
    if (log.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.fw_debug_log),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(log) { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
