// AirTAK Android — AirTAKNavGraph.kt
// Planning: §11 | Roadmap: Phase 3
//
// Bottom-navigation host: Map, Messages, Players, Settings.

package com.airtak

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.airtak.ui.map.MapScreen
import com.airtak.ui.messages.MessagesScreen
import com.airtak.ui.players.PlayersScreen
import com.airtak.ui.settings.SettingsScreen

private enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Map("map", "Map", Icons.Filled.Map),
    Messages("messages", "Messages", Icons.Filled.List),
    Players("players", "Players", Icons.Filled.Person),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun AirTAKNavGraph() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected  = current?.hierarchy?.any { it.route == screen.route } == true,
                        onClick   = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon      = { Icon(screen.icon, contentDescription = screen.label) },
                        label     = { Text(screen.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Map.route)      { MapScreen() }
            composable(Screen.Messages.route) { MessagesScreen() }
            composable(Screen.Players.route)  { PlayersScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
