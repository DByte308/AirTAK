// AirTAK Android — ui/AppShell.kt
//
// Top-level Compose container: persistent top "HUD bar", scaffold body with
// the NavHost, and the bottom NavigationBar. The HUD bar shows the BLE link
// chip, own callsign, and three tactical metrics (BAT / GPS / RSSI) sourced
// from DEVICE_STATUS. The bottom NavigationBar exposes the 5 §11.4 routes.

package com.airtak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.airtak.R
import com.airtak.ui.components.LinkChip
import com.airtak.ui.components.MetricTile
import com.airtak.ui.game.GameScreen
import com.airtak.ui.map.MapScreen
import com.airtak.ui.messages.MessagesScreen
import com.airtak.ui.pairing.PairingScreen
import com.airtak.ui.players.PlayersScreen
import com.airtak.ui.settings.SettingsScreen

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
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = {
            HudBar(
                linkState = link,
                callsign = status?.nodeName?.takeIf { it.isNotBlank() } ?: "AirTAK",
                batteryPercent = status?.batteryLevel?.let { (it * 100).toInt() },
                satellites = status?.gps?.satellites,
                rssi = status?.radio?.lastRssi,
                onLinkClick = { navController.navigateTopLevel("pair") },
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
            AppNavHost(navController)
        }
    }
}

@Composable
private fun HudBar(
    linkState: com.airtak.data.ble.LinkState,
    callsign: String,
    batteryPercent: Int?,
    satellites: Int?,
    rssi: Float?,
    onLinkClick: () -> Unit,
) {
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
                value = satellites?.let { "$it sat" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "RSSI",
                value = rssi?.let { "${it.toInt()} dBm" } ?: "—",
                modifier = Modifier.weight(1f),
            )
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
private fun AppNavHost(navController: androidx.navigation.NavHostController) {
    NavHost(navController = navController, startDestination = Destination.Map.route) {
        composable(Destination.Map.route)      { MapScreen() }
        composable(Destination.Messages.route) { MessagesScreen() }
        composable(Destination.Players.route)  { PlayersScreen() }
        composable(Destination.Game.route)     { GameScreen() }
        composable(Destination.Settings.route) { SettingsScreen() }
        composable("pair")                     { PairingScreen(onDone = { navController.popBackStack() }) }
    }
}

private fun androidx.navigation.NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
