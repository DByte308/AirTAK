// AirTAK Android — ui/AppShell.kt
//
// Top-level Compose container: persistent top "HUD bar", scaffold body with
// the NavHost, and the bottom NavigationBar. The HUD bar shows the BLE link
// chip, own callsign, and three tactical metrics (BAT / GPS / RSSI) sourced
// from DEVICE_STATUS. The bottom NavigationBar exposes the 5 §11.4 routes.

package com.airtak.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.SportsKabaddi
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.airtak.R
import com.airtak.proto.DcLevel
import com.airtak.ui.components.LinkChip
import com.airtak.ui.components.MetricTile
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit
// (legacy GameScreen import retired — Markers tab now lives in com.airtak.ui.markers.MarkersScreen)
import com.airtak.ui.map.MapScreen
import com.airtak.ui.messages.MessagesScreen
import com.airtak.ui.pairing.PairingScreen
import com.airtak.ui.players.PlayersScreen
import com.airtak.ui.settings.SettingsScreen
import com.airtak.ui.firmware.FirmwareUpdateScreen

enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    Map      ("map",      R.string.nav_map,      Icons.Outlined.Map),
    Messages ("messages", R.string.nav_messages, Icons.Outlined.Forum),
    Players  ("players",  R.string.nav_players,  Icons.Outlined.Groups),
    Game     ("game",     R.string.nav_game,     Icons.Outlined.SportsKabaddi),
    Settings ("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun AppShell(viewModel: AppShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val link by viewModel.linkState.collectAsState()
    val status by viewModel.deviceStatus.collectAsState()
    val hudTitle by viewModel.hudTitle.collectAsState()
    val phoneGpsActive by viewModel.phoneGpsActive.collectAsState()
    val phoneGpsFix by viewModel.phoneGpsFix.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val pendingDmNodeId by viewModel.pendingDmNodeId.collectAsState()
    val pendingLocateNodeId by viewModel.pendingLocateNodeId.collectAsState()
    val pendingLocateMarkerId by viewModel.pendingLocateMarkerId.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        viewModel.usbFirmwareTrigger.collect {
            navController.navigate("firmwareUpdate/")
        }
    }

    if (isLandscape) {
        // Landscape: NavigationRail on the left, slim HUD at top, body beside rail
        Row(Modifier.fillMaxSize().statusBarsPadding()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Spacer(Modifier.height(8.dp))
                Destination.entries.forEach { dest ->
                    val selected = currentRoute == dest.route ||
                        currentRoute?.startsWith(dest.route + "/") == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navController.navigateTopLevel(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(androidx.compose.ui.res.stringResource(dest.labelRes)) },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            Column(Modifier.fillMaxSize()) {
                HudBar(
                    linkState = link,
                    callsign = hudTitle,
                    batteryPercent = status?.batteryLevel?.let { (it * 100).toInt() },
                    satellites = status?.gps?.satellites,
                    rssi = status?.radio?.lastRssi,
                    hasDeviceStatus = status != null,
                    deviceGpsFix = status?.gps?.fix == true,
                    phoneGpsActive = phoneGpsActive,
                    phoneGpsFix = phoneGpsFix,
                    onLinkClick = { navController.navigateTopLevel("pair") },
                    compact = true,
                    dcLevel = status?.dcLevel ?: DcLevel.DC_OK,
                    dcPercentage = status?.dcPercentage ?: 0,
                    dcRelaysDropped = status?.dcRelaysDropped ?: 0,
                )
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(
                        navController = navController,
                        pendingDmNodeId = pendingDmNodeId,
                        onDmOpened = viewModel::clearDmTarget,
                        pendingLocateNodeId = pendingLocateNodeId,
                        onLocateConsumed = viewModel::clearLocateTarget,
                        pendingLocateMarkerId = pendingLocateMarkerId,
                        onLocateMarkerConsumed = viewModel::clearLocateMarker,
                        onMessagePlayer = { nodeId ->
                            viewModel.setDmTarget(nodeId)
                            navController.navigateTopLevel(Destination.Messages.route)
                        },
                        onLocatePlayer = { nodeId ->
                            viewModel.setLocateTarget(nodeId)
                            navController.navigateTopLevel(Destination.Map.route)
                        },
                        onLocateMarker = { markerId ->
                            viewModel.setLocateMarker(markerId)
                            navController.navigateTopLevel(Destination.Map.route)
                        },
                    )
                }
            }
        }
    } else {
        // Portrait: standard Scaffold with top HUD + bottom NavigationBar
        Scaffold(
        topBar = {
            HudBar(
                linkState = link,
                callsign = hudTitle,
                batteryPercent = status?.batteryLevel?.let { (it * 100).toInt() },
                satellites = status?.gps?.satellites,
                rssi = status?.radio?.lastRssi,
                hasDeviceStatus = status != null,
                deviceGpsFix = status?.gps?.fix == true,
                phoneGpsActive = phoneGpsActive,
                phoneGpsFix = phoneGpsFix,
                onLinkClick = { navController.navigateTopLevel("pair") },
                compact = false,
                dcLevel = status?.dcLevel ?: DcLevel.DC_OK,
                dcPercentage = status?.dcPercentage ?: 0,
                dcRelaysDropped = status?.dcRelaysDropped ?: 0,
            )
        },
        bottomBar = {
            BottomNav(
                currentRoute = currentRoute,
                onSelect = { dest -> navController.navigateTopLevel(dest.route) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AppNavHost(
                    navController = navController,
                    pendingDmNodeId = pendingDmNodeId,
                    onDmOpened = viewModel::clearDmTarget,
                    pendingLocateNodeId = pendingLocateNodeId,
                    onLocateConsumed = viewModel::clearLocateTarget,
                    pendingLocateMarkerId = pendingLocateMarkerId,
                    onLocateMarkerConsumed = viewModel::clearLocateMarker,
                    onMessagePlayer = { nodeId ->
                        viewModel.setDmTarget(nodeId)
                        navController.navigateTopLevel(Destination.Messages.route)
                    },
                    onLocatePlayer = { nodeId ->
                        viewModel.setLocateTarget(nodeId)
                        navController.navigateTopLevel(Destination.Map.route)
                    },
                    onLocateMarker = { markerId ->
                        viewModel.setLocateMarker(markerId)
                        navController.navigateTopLevel(Destination.Map.route)
                    },
                )
            }
        }
    }
}

@Composable
private fun DcLevel.displayColor(): Color = when (this) {
    DcLevel.DC_OK -> StatusActive
    DcLevel.DC_CAUTION -> AccentAmber
    // TODO: centralize to Color.kt (local theming decision)
    DcLevel.DC_WARNING -> Color(0xFFFF7043)
    DcLevel.DC_CRITICAL -> StatusHit
    DcLevel.UNRECOGNIZED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun DcLevel.shortName(): String = when (this) {
    DcLevel.DC_OK -> "OK"
    DcLevel.DC_CAUTION -> "CAUT"
    DcLevel.DC_WARNING -> "WARN"
    DcLevel.DC_CRITICAL -> "CRIT"
    DcLevel.UNRECOGNIZED -> "?"
}

private fun formatDcPercent(hundredths: Int): String {
    val pct = hundredths / 100.0
    return "%.1f%%".format(pct)
}

@Composable
private fun HudBar(
    linkState: com.airtak.data.ble.LinkState,
    callsign: String,
    batteryPercent: Int?,
    satellites: Int?,
    rssi: Float?,
    hasDeviceStatus: Boolean,
    deviceGpsFix: Boolean,
    phoneGpsActive: Boolean,
    phoneGpsFix: Boolean,
    onLinkClick: () -> Unit,
    compact: Boolean = false,
    dcLevel: DcLevel = DcLevel.DC_OK,
    dcPercentage: Int = 0,
    dcRelaysDropped: Int = 0,
) {
    if (compact) {
        // Landscape: single slim row — just link chip + callsign + inline metrics
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkChip(state = linkState, onClick = onLinkClick)
            Text(
                callsign.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            batteryPercent?.let {
                Text(
                    "$it%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            satellites?.takeIf { it > 0 }?.let {
                Text(
                    "$it sat",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deviceGpsFix) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: if (phoneGpsActive) {
                Text(
                    if (phoneGpsFix) "GPS fix" else "GPS...",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (phoneGpsFix) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else null
            rssi?.let {
                Text(
                    "${it.toInt()} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasDeviceStatus) {
                Text(
                    "DC ${formatDcPercent(dcPercentage)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dcLevel.displayColor(),
                )
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinkChip(state = linkState, onClick = onLinkClick)
                Text(
                    callsign.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = "BAT",
                    value = batteryPercent?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "GPS",
                    value = if (satellites != null && satellites > 0) "$satellites sat"
                        else if (phoneGpsActive) {
                            if (phoneGpsFix) "Fix" else "..."
                        } else "—",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "RSSI",
                    value = rssi?.let { "${it.toInt()} dBm" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "DC",
                    value = if (hasDeviceStatus) "${dcLevel.shortName()} ${formatDcPercent(dcPercentage)}" else "—",
                    accent = dcLevel.displayColor(),
                    secondary = if (hasDeviceStatus && dcRelaysDropped > 0) "drp:$dcRelaysDropped" else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNav(currentRoute: String?, onSelect: (Destination) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Destination.entries.forEach { dest ->
            val selected = currentRoute == dest.route ||
                currentRoute?.startsWith(dest.route + "/") == true
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(text = androidx.compose.ui.res.stringResource(dest.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    pendingDmNodeId: Long?,
    onDmOpened: () -> Unit,
    pendingLocateNodeId: Long?,
    onLocateConsumed: () -> Unit,
    pendingLocateMarkerId: Long?,
    onLocateMarkerConsumed: () -> Unit,
    onMessagePlayer: (Long) -> Unit,
    onLocatePlayer: (Long) -> Unit,
    onLocateMarker: (Long) -> Unit,
) {
    NavHost(navController = navController, startDestination = Destination.Map.route) {
        composable(Destination.Map.route)      {
            MapScreen(
                focusNodeId = pendingLocateNodeId,
                onFocusConsumed = onLocateConsumed,
                focusMarkerId = pendingLocateMarkerId,
                onMarkerFocusConsumed = onLocateMarkerConsumed,
            )
        }
        composable(Destination.Messages.route) {
            MessagesScreen(
                pendingDmNodeId = pendingDmNodeId,
                onDmOpened = onDmOpened,
            )
        }
        composable(Destination.Players.route)  { PlayersScreen(onMessagePlayer = onMessagePlayer, onLocatePlayer = onLocatePlayer) }
        composable(Destination.Game.route)     { com.airtak.ui.markers.MarkersScreen(onLocateMarker = onLocateMarker) }
        composable(Destination.Settings.route) {
            SettingsScreen(
                onFirmwareSelected = { uri ->
                    navController.navigate("firmwareUpdate/${Uri.encode(uri.toString())}")
                },
            )
        }
        composable("pair")                     { PairingScreen(onDone = { navController.popBackStack() }) }
        composable(
            "firmwareUpdate/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val uri = backStackEntry.arguments?.getString("uri")
            FirmwareUpdateScreen(
                firmwareUri = uri,
                onDone = { navController.popBackStack() },
            )
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
