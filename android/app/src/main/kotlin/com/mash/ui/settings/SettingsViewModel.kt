// MASH Android — ui/settings/SettingsViewModel.kt
// Planning: §11.9, §14.2 | Roadmap: 3.6, 2.8

package com.mash.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    // TODO Phase 3.6: Inject ObserveDeviceStatusUseCase, BleRepository
) : ViewModel() {
    // TODO Phase 3.6: StateFlow<DeviceConfigUi> per config group
    // TODO Phase 2.8: fun applyConfig(group: ConfigGroup, pb: ByteArray, commit: Boolean)
    // TODO Phase 2.8: StateFlow<Boolean> isRadioRestarting
}
