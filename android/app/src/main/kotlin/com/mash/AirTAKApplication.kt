// AirTAK Android — AirTAKApplication.kt
// Planning: §11 | Roadmap: Phase 3
//
// Hilt application class. Entry point for dependency injection graph.

package com.airtak

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirTAKApplication : Application()
