// AirTAK Android — ui/markers/MarkersViewModel.kt
// Phase 5: Hilt VM backing the Markers list tab. Wraps the visibleMarkers
// flow from TacticalMarkerRepository (already scope-filtered) and exposes
// a delete action that broadcasts a tombstone.

package com.airtak.ui.markers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.data.marker.TacticalMarkerRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MarkersViewModel @Inject constructor(
    private val repo: TacticalMarkerRepository,
    private val mesh: MeshRepository,
    remoteNodes: RemoteNodeRepository,
) : ViewModel() {
    val markers: StateFlow<List<TacticalMarkerEntity>> =
        repo.visibleMarkers
            .map { list -> list.sortedByDescending { it.updatedAtUnix } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val remoteNodeIds: StateFlow<List<Pair<Long, String>>> = remoteNodes.nodes
        .map { nodes -> nodes.map { it.nodeId to (it.callsign.ifBlank { "Node ${it.nodeId.toString(16).take(8)}" }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMarker(markerId: Long) {
        viewModelScope.launch { repo.deleteMarker(markerId) }
    }

    fun syncNow() {
        viewModelScope.launch {
            mesh.triggerSync()
            repo.rebroadcastOwned()
        }
    }

    fun requestMarkerSnapshot(targetNodeId: Long) {
        mesh.requestMarkerSnapshot(targetNodeId)
    }
}
