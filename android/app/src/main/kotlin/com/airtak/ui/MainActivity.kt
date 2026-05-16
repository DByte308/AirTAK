// AirTAK Android — ui/MainActivity.kt
// Roadmap: 3.1
//
// Single-activity Compose host. The activity:
//   - applies the user's theme preference
//   - hosts the navigation graph
//   - starts the persistent LinkService once required permissions are present
//
// All UI lives in @Composable functions reachable via AppNavHost.

package com.airtak.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.airtak.data.ble.service.LinkService
import com.airtak.data.prefs.AppPreferences
import com.airtak.ui.theme.AirTakTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result evaluated reactively by repositories */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensurePermissions()
        // Foreground service is started once the user has a bonded device. The
        // pairing screen invokes LinkService.start once pairing completes.
        if (prefs.bondedAddress() != null) LinkService.start(this)

        setContent {
            var themeMode by remember { mutableStateOf(prefs.themeMode()) }
            LaunchedEffect(Unit) {
                prefs.observe().collect { themeMode = prefs.themeMode() }
            }
            AirTakTheme(mode = themeMode) {
                AppShell()
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        needed += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPerms.launch(missing.toTypedArray())
    }
}
