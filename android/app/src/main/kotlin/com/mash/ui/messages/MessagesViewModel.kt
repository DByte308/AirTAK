// MASH Android — ui/messages/MessagesViewModel.kt
// Planning: §11.6 | Roadmap: 3.2

package com.mash.ui.messages

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    // TODO Phase 3.2: Inject MessageDao, SendMessageUseCase
) : ViewModel() {
    // TODO Phase 3.2: StateFlow<List<MessageEntity>> grouped by channel
    // TODO Phase 3.2: fun sendMessage(channelIndex: Int, text: String)
}
