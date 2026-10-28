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
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.airtak.data.ble.service.LinkService
import com.airtak.data.gps.PhoneGpsProvider
import com.airtak.data.prefs.AppPreferences
import com.airtak.ui.i18n.withAppLanguage
import com.airtak.ui.theme.AirTakTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences

    /**
     * Phone GPS is a singleton built while the permission dialog is still on screen, so its first
     * registration attempt can fail for a reason that disappears seconds later. Nothing else
     * observes the grant, so this activity — which owns the prompt — hands the result to it.
     */
    @Inject lateinit var phoneGps: PhoneGpsProvider

    private val appShellViewModel: AppShellViewModel by viewModels()

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { phoneGps.refresh() }

    /**
     * Applies the stored app-language override before any resource is resolved, so
     * stringResource() picks the right values-* folder. No-op when following the
     * system language. Changing the setting calls recreate(), which re-enters here.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensurePermissions()
        // Foreground service is started once the user has a bonded device. The
        // pairing screen invokes LinkService.start once pairing completes.
        if (prefs.bondedAddress() != null) LinkService.start(this)

        // If launched via USB device attachment, trigger firmware update flow.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appShellViewModel.onUsbDeviceAttached()
        }

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

    override fun onResume() {
        super.onResume()
        // Covers the path where the grant arrives through system settings rather than through our
        // own callback, and the case where the user turns device location back on.
        phoneGps.refresh()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appShellViewModel.onUsbDeviceAttached()
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
