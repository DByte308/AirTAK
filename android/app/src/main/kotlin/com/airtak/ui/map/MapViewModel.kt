// AirTAK Android — ui/map/MapViewModel.kt

package com.airtak.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.status.DeviceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    status: DeviceStatusRepository,
) : ViewModel() {
    /** Own GPS position as (lat, lng) once the device reports a fix. */
    val ownPosition: StateFlow<Pair<Double, Double>?> = status.status
        .map { s ->
            val gps = s?.gps ?: return@map null
            if (!gps.fix) null else gps.latitude to gps.longitude
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
