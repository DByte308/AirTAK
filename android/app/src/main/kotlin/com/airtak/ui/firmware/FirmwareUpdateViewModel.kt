// AirTAK Android — ui/firmware/FirmwareUpdateViewModel.kt
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Holds firmware update progress state. Takes a firmware URI, reads bytes,
// executes via FirmwareUpdateManager over the active BLE link, and reports
// progress via StateFlow<UpdateProgress>.

package com.airtak.ui.firmware

import android.bluetooth.BluetoothGatt
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.LinkState
import com.airtak.data.ota.FirmwareUpdateManager
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import com.airtak.data.status.DeviceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class FirmwareUpdateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ble: BleRepository,
    private val deviceStatusRepo: DeviceStatusRepository,
) : ViewModel() {

    private val manager = FirmwareUpdateManager(appContext)

    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Initializing("Not started"))
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()

    private val _state = MutableStateFlow<FwUpdateState>(FwUpdateState.Idle)
    val state: StateFlow<FwUpdateState> = _state.asStateFlow()

    fun startUpdate(firmwareUri: Uri) {
        if (_state.value !is FwUpdateState.Idle) return
        _state.value = FwUpdateState.InProgress

        viewModelScope.launch {
            try {
                val firmwareBytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(firmwareUri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read firmware file")
                }

                val linkState = ble.linkState.value
                val gatt = when (linkState) {
                    is LinkState.Connected -> ble.getGatt()
                    else -> throw Exception("Device not connected over BLE")
                } ?: throw Exception("BLE GATT not available")

                val status = deviceStatusRepo.status.value
                val transport = FirmwareUpdateManager.Transport.BleGatt(gatt)
                val deviceInfo = FirmwareUpdateManager.DeviceInfo(
                    boardId = status?.boardId ?: "unknown",
                    platform = status?.platform ?: "unknown",
                    firmwareVersion = status?.firmwareVersion ?: "unknown",
                )

                val session = manager.prepareUpdate(firmwareUri, transport, deviceInfo).getOrThrow()
                session.execute { _progress.value = it }

                _state.value = FwUpdateState.Done
            } catch (e: Exception) {
                _progress.value = UpdateProgress.Error(e.message ?: "Unknown error", canRetry = false)
                _state.value = FwUpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() {
        _progress.value = UpdateProgress.Initializing("Not started")
        _state.value = FwUpdateState.Idle
    }

    sealed class FwUpdateState {
        data object Idle : FwUpdateState()
        data object InProgress : FwUpdateState()
        data object Done : FwUpdateState()
        data class Error(val message: String) : FwUpdateState()
    }

    companion object {
        const val SUPPORTED_MIME = "application/octet-stream"
        val SUPPORTED_EXTENSIONS = arrayOf(
            "application/octet-stream",
            "application/x-binary",
            "*/*",
        )
    }
}
