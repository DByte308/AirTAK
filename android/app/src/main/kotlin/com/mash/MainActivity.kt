// MASH Android — MainActivity.kt
// Planning: §11 | Roadmap: Phase 3
//
// Single-activity host for Compose navigation graph.
// Requests runtime permissions on first launch (BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
// ACCESS_FINE_LOCATION, POST_NOTIFICATIONS per §11.2).

package com.mash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.mash.data.db.MeshRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var meshRepository: MeshRepository

    private val requiredPermissions: Array<String> get() {
        val base = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base += Manifest.permission.BLUETOOTH_SCAN
            base += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base += Manifest.permission.POST_NOTIFICATIONS
        }
        return base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Results handled per-permission if needed. BLE will still attempt connection. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(requiredPermissions)

        // Start bridging MESH_RX BLE notifications → Room DB.
        meshRepository.startProcessing()

        setContent {
            MaterialTheme {
                MashNavGraph()
            }
        }
    }
}
