// AirTAK Android — ui/AppShellViewModel.kt

package com.airtak.ui

import androidx.lifecycle.ViewModel
import com.airtak.data.ble.BleRepository
import com.airtak.data.status.DeviceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    ble: BleRepository,
    status: DeviceStatusRepository,
) : ViewModel() {
    val linkState = ble.linkState
    val deviceStatus = status.status

    private val _pendingDmNodeId = MutableStateFlow<Long?>(null)
    val pendingDmNodeId = _pendingDmNodeId.asStateFlow()

    fun setDmTarget(nodeId: Long) { _pendingDmNodeId.value = nodeId }
    fun clearDmTarget() { _pendingDmNodeId.value = null }

    private val _pendingLocateNodeId = MutableStateFlow<Long?>(null)
    val pendingLocateNodeId = _pendingLocateNodeId.asStateFlow()

    fun setLocateTarget(nodeId: Long) { _pendingLocateNodeId.value = nodeId }
    fun clearLocateTarget() { _pendingLocateNodeId.value = null }

    private val _pendingLocateMarkerId = MutableStateFlow<Long?>(null)
    val pendingLocateMarkerId = _pendingLocateMarkerId.asStateFlow()

    fun setLocateMarker(markerId: Long) { _pendingLocateMarkerId.value = markerId }
    fun clearLocateMarker() { _pendingLocateMarkerId.value = null }
}
