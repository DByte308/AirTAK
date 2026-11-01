package com.airtak.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airtak.R
import com.airtak.ui.firmware.FirmwareUpdateScreen
import com.airtak.ui.map.MapScreen
import com.airtak.ui.markers.MarkersScreen
import com.airtak.ui.messages.MessagesScreen
import com.airtak.ui.pairing.PairingScreen
import com.airtak.ui.players.PlayersScreen
import com.airtak.ui.settings.SettingsScreen

/** A pushed destination, not a top-level tab switch: the saved help page stays underneath. */
@Composable
fun HelpDestinationScreen(
    destination: HelpDestination,
    firmwareUpdateActive: Boolean,
    nodeId: Long?,
    markerId: Long?,
    onNavigate: (HelpDestination, Long?, Long?) -> Unit,
    onReturnToHelp: () -> Unit,
) {
    // Popping the update's NavHost entry would cancel its ViewModel mid-flash.
    val returnToHelp: () -> Unit = { if (!firmwareUpdateActive) onReturnToHelp() }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = returnToHelp, enabled = !firmwareUpdateActive, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.help_back_to_guide))
        }
        Box(Modifier.weight(1f)) {
            when (destination.screen) {
                HelpScreen.Pairing -> PairingScreen(onDone = returnToHelp)
                HelpScreen.Map -> MapScreen(
                    focusNodeId = nodeId,
                    focusMarkerId = markerId,
                    initialHelpAction = destination.mapAction,
                )
                HelpScreen.Messages -> MessagesScreen(
                    pendingDmNodeId = nodeId,
                    initialDirectMessages = destination.directMessages,
                    initialChannelSlot = destination.messageSlot,
                )
                HelpScreen.Players -> PlayersScreen(
                    onMessagePlayer = { onNavigate(HelpDestination.DirectMessages, it, null) },
                    onLocatePlayer = { onNavigate(HelpDestination.Map, it, null) },
                )
                HelpScreen.Markers -> MarkersScreen(
                    onLocateMarker = { onNavigate(HelpDestination.Map, null, it) },
                )
                HelpScreen.Settings -> SettingsScreen(
                    initialSection = destination.section,
                    onBackToHelp = returnToHelp,
                    onHelpNavigate = { if (it != HelpDestination.Help) onNavigate(it, null, null) },
                    onFirmwareUpdate = { onNavigate(HelpDestination.Firmware, null, null) },
                )
                HelpScreen.Firmware -> FirmwareUpdateScreen(onDone = returnToHelp)
            }
        }
    }
    // Registered after the child, so system Back returns to the guide rather than a tab/root exit.
    BackHandler(onBack = returnToHelp)
}
