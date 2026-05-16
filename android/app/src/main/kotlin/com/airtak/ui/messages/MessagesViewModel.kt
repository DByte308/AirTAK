// AirTAK Android — ui/messages/MessagesViewModel.kt

package com.airtak.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.db.MessageEntity
import com.airtak.data.mesh.MeshRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repo: MeshRepository,
) : ViewModel() {

    private val _channel = MutableStateFlow(0)
    val messages: StateFlow<List<MessageEntity>> = _channel
        .flatMapLatest { repo.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun selectChannel(index: Int) { _channel.value = index }
    fun setDraft(text: String) {
        _draft.value = text
        if (_error.value != null && text.toByteArray(Charsets.UTF_8).size <= 237) _error.value = null
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        when (val r = repo.sendText(_channel.value, text)) {
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
