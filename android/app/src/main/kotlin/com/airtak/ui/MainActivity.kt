// AirTAK Android — ui/MainActivity.kt
// Roadmap: 3.1
//
// Single-activity Compose host. The activity:
//   - applies the user's theme preference
//   - hosts the navigation graph
//   - starts the persistent LinkService once required permissions are present
//   - owns the back-to-close behaviour at the root of the app
//
// All UI lives in @Composable functions reachable via AppNavHost.

package com.airtak.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import com.airtak.R
import com.airtak.config.constants.UI_EXIT_KILL_DELAY_MS
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.service.LinkService
import com.airtak.data.gps.PhoneGpsProvider
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.prefs.ExitMode
import com.airtak.ui.i18n.withAppLanguage
import com.airtak.ui.theme.AirTakTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: AppPreferences

    /**
     * Phone GPS is a singleton built while the permission dialog is still on screen, so its first
     * registration attempt can fail for a reason that disappears seconds later. Nothing else
     * observes the grant, so this activity — which owns the prompt — hands the result to it.
     */
    @Inject lateinit var phoneGps: PhoneGpsProvider

    /** Injected so an explicit exit can drop the link instead of leaving the reconnect loop running. */
    @Inject lateinit var bleRepository: BleRepository

    private val appShellViewModel: AppShellViewModel by viewModels()

    /** Back-press counting lives in [BackToExit] so the window rules are testable without an activity. */
    private val backToExit = BackToExit(nowMs = SystemClock::elapsedRealtime)

    private val exitHandler = Handler(Looper.getMainLooper())

    /**
     * Posted by [closeApp] and cancelled if the user returns inside the grace window. Held past
     * onDestroy on purpose: removing it there would cancel the exit the user just asked for,
     * because finishing the task destroys the activity well before the delay elapses.
     */
    private var pendingHardExit: Runnable? = null

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
        if (prefs.bondedAddress() != null && !prefs.connectionPaused()) LinkService.start(this)

        // Registered here, before setContent, so this is the oldest callback on the dispatcher.
        // OnBackPressedDispatcher invokes the newest enabled callback first, so the navigation
        // back stack and screen-level BackHandlers (a settings sub-page, an open dialog) still
        // consume the press. This one runs only when nothing else wanted it: the root of the app.
        onBackPressedDispatcher.addCallback(this, rootBackCallback)

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
        resumedActivities.incrementAndGet()
        // The user is back inside the grace window: no hard exit, and a back press from before
        // the pause must not count towards closing the app now.
        pendingHardExit?.let { exitHandler.removeCallbacks(it) }
        pendingHardExit = null
        backToExit.reset()
        // Covers the path where the grant arrives through system settings rather than through our
        // own callback, and the case where the user turns device location back on.
        phoneGps.refresh()
    }

    override fun onPause() {
        resumedActivities.decrementAndGet()
        super.onPause()
    }

    /**
     * Back at the root of the app: the first press asks, the second one within the window closes.
     * One press used to finish the activity while the BLE foreground service kept the process,
     * the notification and the reconnect loop alive, which read as "the app never closes".
     */
    private val rootBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (prefs.exitMode() == ExitMode.Recents) {
                backToExit.reset()
                // Keep the task in Recents so swiping it away remains an explicit exit action.
                moveTaskToBack(true)
                return
            }
            when (val decision = backToExit.onBackPressed()) {
                is ExitDecision.Confirm -> Toast.makeText(
                    this@MainActivity,
                    exitHint(decision.pressesLeft),
                    Toast.LENGTH_SHORT,
                ).show()

                ExitDecision.Exit -> closeApp()
            }
        }
    }

    private fun exitHint(pressesLeft: Int): String =
        if (pressesLeft <= 1) getString(R.string.exit_back_again)
        else getString(R.string.exit_back_more, pressesLeft)

    /**
     * Closes AirTAK and keeps it closed: the link goes, the foreground service and its
     * notification go, the task leaves Recents, and nothing restarts it. LinkService returns
     * START_NOT_STICKY, so the system will not bring the service back after this.
     */
    private fun closeApp() {
        runCatching { bleRepository.disconnect() }
        LinkService.stop(this)
        finishAndRemoveTask()

        if (bleRepository.firmwareUpdateInFlight) {
            // A DFU transfer runs in its own foreground service. Cutting the process off here
            // would abort it mid-write and leave the radio sitting in its bootloader, so the UI
            // closes and the update is left alone.
            return
        }
        pendingHardExit?.let { exitHandler.removeCallbacks(it) }
        pendingHardExit = Runnable {
            pendingHardExit = null
            if (resumedActivities.get() == 0) exitProcess(0)
        }.also { exitHandler.postDelayed(it, UI_EXIT_KILL_DELAY_MS) }
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

    companion object {
        /** Activities of this app currently resumed. Guards the delayed hard exit. */
        private val resumedActivities = AtomicInteger(0)
    }
}
