// MASH Android — ui/messages/MessagesViewModel.kt
// Planning: §11.6 | Roadmap: 3.2

package com.mash.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mash.data.db.MessageDao
import com.mash.data.db.MessageEntity
import com.mash.data.ble.BleRepository
import com.mash.data.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Maximum text message payload in bytes per §8.3 (LoRa 255 − OTA header 12 − Data header 6).
private const val MAX_MESSAGE_BYTES = 228

data class MessagesUiState(
    val messages: List<MessageEntity> = emptyList(),
    val draftText: String = "",
    val isConnected: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val messageDao:    MessageDao,
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _activeChannel = MutableStateFlow(0)
    private val _draft         = MutableStateFlow("")
    private val _isSending     = MutableStateFlow(false)
    private val _error         = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MessagesUiState> = combine(
        messageDao.observeByChannel(0), // TODO Phase 3.5: multi-channel
        bleRepository.connectionStateFlow(),
        _draft,
        _isSending,
        _error,
    ) { messages, connState, draft, sending, error ->
        MessagesUiState(
            messages    = messages,
            draftText   = draft,
            isConnected = connState == ConnectionState.CONNECTED,
            isSending   = sending,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun onDraftChanged(text: String) {
        // Clamp draft to MAX_MESSAGE_BYTES in UTF-8 before updating.
        val clamped = if (text.encodeToByteArray().size <= MAX_MESSAGE_BYTES) text
                      else _draft.value // reject if would exceed limit
        _draft.value = clamped
    }

    fun sendMessage() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        if (bytes.size > MAX_MESSAGE_BYTES) { _error.value = "Message too long"; return }

        viewModelScope.launch {
            _isSending.value = true
            _error.value     = null
            try {
                // The firmware accepts raw UTF-8 bytes for TEXT_MESSAGE_APP.
                // The BLE layer wraps in a meshtastic.Data proto (portnum=1, payload=bytes)
                // on the Android side before writing to MESH_TX.
                val dataPb = buildDataProto(portnum = 1, payload = bytes)
                val ok = bleRepository.writeMeshTx(dataPb)
                if (ok) {
                    _draft.value = ""
                } else {
                    _error.value = "Send failed — check BLE connection"
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun dismissError() { _error.value = null }

    // ── meshtastic.Data proto builder ──────────────────────────────────────────
    // Builds a minimal meshtastic.Data proto: portnum(1, varint) + payload(2, bytes).
    private fun buildDataProto(portnum: Int, payload: ByteArray): ByteArray {
        val buf = ArrayList<Byte>()
        fun writeVarint(v: Int) {
            var rem = v
            while (rem >= 0x80) { buf.add((rem or 0x80).toByte()); rem = rem ushr 7 }
            buf.add(rem.toByte())
        }
        // field 1 (portnum): tag = (1 << 3) | 0 = 0x08
        buf.add(0x08); writeVarint(portnum)
        // field 2 (payload): tag = (2 << 3) | 2 = 0x12
        buf.add(0x12); writeVarint(payload.size); buf.addAll(payload.toList())
        return buf.toByteArray()
    }
}

