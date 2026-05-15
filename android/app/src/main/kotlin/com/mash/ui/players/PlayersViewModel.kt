// MASH Android — ui/players/PlayersViewModel.kt
// Planning: §11.7 | Roadmap: 3.3

package com.mash.ui.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mash.data.db.NodeDao
import com.mash.data.db.NodeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class PlayersUiState(
    val nodes: List<NodeEntity> = emptyList(),
    val selectedNodeId: Long? = null,
)

@HiltViewModel
class PlayersViewModel @Inject constructor(
    private val nodeDao: NodeDao,
) : ViewModel() {

    private val _selected = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<PlayersUiState> = nodeDao.observeAll()
        .combine(_selected) { nodes, selected ->
            PlayersUiState(nodes = nodes, selectedNodeId = selected)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayersUiState())

    fun onNodeSelected(nodeId: Long) {
        _selected.value = if (_selected.value == nodeId) null else nodeId
    }
}
