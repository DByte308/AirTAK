// MASH Android — ui/settings/SettingsViewModel.kt
// Planning: §11.9, §14.2 | Roadmap: 3.6

package com.mash.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mash.data.ble.BleRepository
import com.mash.data.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isConnected: Boolean = false,
    val isApplying: Boolean = false,
    val errorMessage: String? = null,
    /** Raw DeviceStatus proto bytes received from DEVICE_STATUS characteristic. */
    val deviceStatusBytes: ByteArray? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _isApplying = MutableStateFlow(false)
    private val _error      = MutableStateFlow<String?>(null)
    private val _lastStatus = MutableStateFlow<ByteArray?>(null)

    init {
        viewModelScope.launch {
            bleRepository.deviceStatusFlow().collect { _lastStatus.value = it }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        bleRepository.connectionStateFlow(),
        _lastStatus,
        _isApplying,
        _error,
    ) { conn, statusBytes, applying, error ->
        SettingsUiState(
            isConnected       = conn == ConnectionState.CONNECTED,
            isApplying        = applying,
            errorMessage      = error,
            deviceStatusBytes = statusBytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * Write a config group to the device. Sends the encoded proto bytes with commit=true.
     * The BleRepository handles chunked framing (§10.5).
     */
    fun applyConfig(pb: ByteArray) {
        viewModelScope.launch {
            _isApplying.value = true
            _error.value = null
            try {
                val ok = bleRepository.writeConfig(pb)
                if (!ok) _error.value = "Config apply failed — check BLE connection"
            } finally {
                _isApplying.value = false
            }
        }
    }

    fun dismissError() { _error.value = null }
}
