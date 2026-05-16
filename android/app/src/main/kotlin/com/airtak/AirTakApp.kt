// AirTAK Android — AirTakApp.kt
// Planning: §11.3 (architecture)
//
// Application entry. Hilt initializes all DI graphs. The foreground BLE link
// service is started by the connection coordinator once a bonded device is
// known; the app itself does not pin the service from onCreate so that a fresh
// install reaches the pairing wizard without an immediate disconnect notification.

package com.airtak

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirTakApp : Application()
