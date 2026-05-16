// AirTAK Android — ui/messages/DirectMessagesViewModel.kt
// Manages the DM peer list (inbox) and individual DM conversations.

package com.airtak.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.db.MessageEntity
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.data.mesh.MeshRepository
import com.airtak.data.mesh.RemoteNodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DirectMessagesViewModel @Inject constructor(
    private val mesh: MeshRepository,
    val nodeRepo: RemoteNodeRepository,
) : ViewModel() {

    /** All known remote nodes — shown in the peer picker. */
    val peers: StateFlow<List<RemoteNodeEntity>> = nodeRepo.nodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The peer whose conversation is currently open. Null = show peer list. */
    private val _activePeer = MutableStateFlow<RemoteNodeEntity?>(null)
    val activePeer: StateFlow<RemoteNodeEntity?> = _activePeer.asStateFlow()

    /** Recent DM peers (one row per peer, ordered by latest message). */
    val dmPeers: StateFlow<List<MessageEntity>> = mesh.observeDmPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Messages for the currently open conversation. */
    val messages: StateFlow<List<MessageEntity>> = _activePeer
        .flatMapLatest { peer ->
            if (peer == null) flowOf(emptyList())
            else mesh.observeDm(peer.nodeId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun openConversation(peer: RemoteNodeEntity) {
        _activePeer.value = peer
        _draft.value = ""
        _error.value = null
    }

    fun openConversationById(nodeId: Long) {
        val peer = peers.value.find { it.nodeId == nodeId } ?: return
        openConversation(peer)
    }

    fun closeConversation() {
        _activePeer.value = null
    }

    fun setDraft(text: String) {
        _draft.value = text
        if (_error.value != null && text.toByteArray(Charsets.UTF_8).size <= 237) _error.value = null
    }

    fun send() {
        val peer = _activePeer.value ?: return
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        when (val r = mesh.sendDm(peer.nodeId, peer.callsign.ifBlank { peer.shortName }, text)) {
            is MeshRepository.SendResult.Ok -> {
                _draft.value = ""
                _error.value = null
            }
            is MeshRepository.SendResult.TooLong -> {
                _error.value = "Too long: ${r.attemptedBytes} B exceeds 237 B LoRa limit."
            }
        }
    }
}
