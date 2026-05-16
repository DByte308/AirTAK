// AirTAK Android — data/status/DeviceStatusRepository.kt
// Planning: §10.4
// Roadmap: 3.3
//
// Subscribes to DEVICE_STATUS BLE notifications and exposes the latest decoded
// DeviceStatus protobuf as a StateFlow consumed by the HUD chrome (link chip,
// Settings > About, Map own-position).

package com.airtak.data.status

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundChannel
import com.airtak.proto.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceStatusRepository @Inject constructor(
    private val ble: BleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    init {
        scope.launch {
            ble.inbound
                .filter { it.channel == InboundChannel.DeviceStatus }
                .collect { frame ->
                    runCatching { DeviceStatus.parseFrom(frame.payload) }
                        .getOrNull()
                        ?.let { _status.value = it }
                }
        }
    }
}
