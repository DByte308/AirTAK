// AirTAK Android — ui/messages/MessagesViewModel.kt

package com.airtak.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.config.ConfigRepository
import com.airtak.data.db.MessageEntity
import com.airtak.data.mesh.MeshRepository
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ChannelRole
import com.airtak.proto.ChannelSlot
import com.airtak.proto.ConfigGroup
import com.airtak.proto.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repo: MeshRepository,
    configRepo: ConfigRepository,
) : ViewModel() {

    /**
     * Non-DISABLED channel slots from the device config, in slot-index order.
     * Falls back to slots 0 and 1 with placeholder names when config has not
     * yet been received (e.g. device not connected).
     */
    val activeSlots: StateFlow<List<ChannelSlot>> = configRepo.groupBytes
        .map { map ->
            val bytes = map[ConfigGroup.CHANNELS]
            if (bytes != null) {
                runCatching { ChannelConfig.parseFrom(bytes) }
                    .getOrNull()
                    ?.slotsList
                    ?.filter { it.role != ChannelRole.DISABLED }
                    ?.ifEmpty { fallbackSlots() }
                    ?: fallbackSlots()
            } else {
                fallbackSlots()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), fallbackSlots())

    /** Slot index (0-7) of the currently viewed channel, driven by tab selection. */
    private val _slotIndex = MutableStateFlow(0)

    val messages: StateFlow<List<MessageEntity>> = _slotIndex
        .flatMapLatest { repo.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _scope = MutableStateFlow(Scope.SCOPE_ALL)
    val scope: StateFlow<Scope> = _scope.asStateFlow()

    fun setScope(s: Scope) { _scope.value = s }

    /** Called when the user selects tab [tabIndex] in the channel list. */
    fun selectChannel(tabIndex: Int) {
        val slots = activeSlots.value
        val slot = slots.getOrNull(tabIndex) ?: return
        _slotIndex.value = slot.index
    }

    fun setDraft(text: String) {
        _draft.value = text
        if (_error.value != null && text.toByteArray(Charsets.UTF_8).size <= 237) _error.value = null
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        when (val r = repo.sendText(_slotIndex.value, text)) {
            is MeshRepository.SendResult.Ok -> {
                _draft.value = ""
                _error.value = null
            }
            is MeshRepository.SendResult.TooLong -> {
                _error.value = "Too long: ${r.attemptedBytes} B exceeds 237 B LoRa limit."
            }
        }
    }

    fun syncNow() { repo.triggerSync() }

    companion object {
        /** Placeholder slots shown before the device config is received. */
        private fun fallbackSlots(): List<ChannelSlot> = listOf(
            ChannelSlot.newBuilder().setIndex(0).setName("Mesh").setRole(ChannelRole.PRIMARY).build(),
            ChannelSlot.newBuilder().setIndex(1).setName("Game").setRole(ChannelRole.SECONDARY).build(),
        )
    }
}
