// MASH Android — ui/map/MapViewModel.kt
// Planning: §11.4, §11.5 | Roadmap: 3.1, 3.4, 3.5

package com.mash.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mash.data.db.NodeDao
import com.mash.data.db.NodeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class MapUiState(
    val nodes: List<NodeEntity> = emptyList(),
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val nodeDao: NodeDao,
) : ViewModel() {

    val uiState: StateFlow<MapUiState> = nodeDao.observeAll()
        .map { MapUiState(nodes = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())
}
