// AirTAK Android — ui/AppShellViewModel.kt

package com.airtak.ui

import androidx.lifecycle.ViewModel
import com.airtak.data.ble.BleRepository
import com.airtak.data.status.DeviceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppShellViewModel @Inject constructor(
    ble: BleRepository,
    status: DeviceStatusRepository,
) : ViewModel() {
    val linkState = ble.linkState
    val deviceStatus = status.status
}
