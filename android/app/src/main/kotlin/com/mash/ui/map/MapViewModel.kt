// MASH Android — ui/map/MapViewModel.kt
// Planning: §11.4, §11.5 | Roadmap: 3.1, 3.4, 3.5

package com.mash.ui.map

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    // TODO Phase 3.1: Inject ObservePlayersUseCase, GameRepository, TileRepository
) : ViewModel() {
    // TODO Phase 3.1: StateFlow<List<MashNode>> for node pins
    // TODO Phase 3.4: StateFlow<List<ZoneMirror>> for zone overlays
    // TODO Phase 3.5: StateFlow<LatLng?> for own position
}
