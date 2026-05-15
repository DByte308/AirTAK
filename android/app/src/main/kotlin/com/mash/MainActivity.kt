// MASH Android — MainActivity.kt
// Planning: §11 | Roadmap: Phase 3
//
// Single-activity host for Compose navigation graph.
// Requests runtime permissions on first launch (BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
// ACCESS_FINE_LOCATION, POST_NOTIFICATIONS per §11.2).

package com.mash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // TODO Phase 3: MashNavGraph()
            }
        }
    }
}
