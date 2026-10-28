// AirTAK Android — ui/AppShellViewModel.kt

package com.airtak.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.ble.BleRepository
import com.airtak.data.config.ConfigRepository
import com.airtak.data.gps.PhoneGpsProvider
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.GameConfig
import com.airtak.util.NodeNames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    ble: BleRepository,
    status: DeviceStatusRepository,
    phoneGps: PhoneGpsProvider,
    config: ConfigRepository,
) : ViewModel() {
    val linkState = ble.linkState
    val deviceStatus = status.status
    val phoneGpsActive = phoneGps.isActive
    val phoneGpsFix = phoneGps.hasFix

    /**
     * HUD title. The bar used to show DeviceStatus.node_name unconditionally, and
     * that field is DeviceConfig.long_name (proto/airtak/ble.proto), so the top bar
     * displayed the 39-byte device long name while the map showed the callsign. Now
     * uses the shared rule: callsign first, long name only as fallback (issue #7).
     */
    val hudTitle =
        combine(status.status, config.groupBytes) { st, groups ->
            val callsign = groups[ConfigGroup.GAME]
                ?.let { runCatching { GameConfig.parseFrom(it).callsign }.getOrNull() }
            NodeNames.resolveOrNull(callsign = callsign, longName = st?.nodeName) ?: "AirTAK"
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "AirTAK")

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

    private val _usbFirmwareTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val usbFirmwareTrigger = _usbFirmwareTrigger.asSharedFlow()

    fun onUsbDeviceAttached() {
        _usbFirmwareTrigger.tryEmit(Unit)
    }
}
