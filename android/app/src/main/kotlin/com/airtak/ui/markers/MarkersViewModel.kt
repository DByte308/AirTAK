// AirTAK Android — ui/markers/MarkersViewModel.kt
// Phase 5: Hilt VM backing the Markers list tab.

package com.airtak.ui.markers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.db.TacticalMarkerEntity
import com.airtak.data.marker.TacticalMarkerRepository
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import com.airtak.proto.MarkerType
import com.airtak.proto.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarkersFilter(
    val query: String = "",
    val typeFilter: MarkerType? = null,
    val scopeFilter: Scope? = null,
)

@HiltViewModel
class MarkersViewModel @Inject constructor(
    private val repo: TacticalMarkerRepository,
    private val mesh: MeshRepository,
    remoteNodes: RemoteNodeRepository,
) : ViewModel() {
    private val _filter = MutableStateFlow(MarkersFilter())

    val markers: StateFlow<List<TacticalMarkerEntity>> =
        combine(repo.visibleMarkers, _filter) { list, filter ->
            list.filter { m ->
                val matchesQuery = filter.query.isBlank() ||
                    m.label.contains(filter.query, ignoreCase = true) ||
                    m.description.contains(filter.query, ignoreCase = true)
                val matchesType = filter.typeFilter == null ||
                    filter.typeFilter == MarkerType.forNumber(m.typeValue)
                val matchesScope = filter.scopeFilter == null ||
                    filter.scopeFilter == Scope.forNumber(m.scopeValue)
                matchesQuery && matchesType && matchesScope
            }.sortedByDescending { it.updatedAtUnix }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filter: StateFlow<MarkersFilter> = _filter

    fun setQuery(query: String) { _filter.value = _filter.value.copy(query = query) }
    fun setTypeFilter(type: MarkerType?) { _filter.value = _filter.value.copy(typeFilter = type) }
    fun setScopeFilter(scope: Scope?) { _filter.value = _filter.value.copy(scopeFilter = scope) }
    fun clearFilters() { _filter.value = MarkersFilter() }

    data class NodeInfo(val nodeId: Long, val callsign: String, val shortName: String)

    val remoteNodeIds: StateFlow<List<NodeInfo>> = remoteNodes.nodes
        .map { nodes -> nodes.map { NodeInfo(it.nodeId, it.callsign, it.shortName) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun creatorName(marker: TacticalMarkerEntity): String {
        if (marker.ownedLocally) return "You"
        return remoteNodeIds.value
            .firstOrNull { it.nodeId == marker.creatorNodeId }
            ?.callsign
            ?.takeIf { it.isNotBlank() }
            ?: "Node ${marker.creatorNodeId.toString(16).uppercase().take(8)}"
    }

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
