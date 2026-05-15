// MASH Android — ui/messages/MessagesScreen.kt
// Planning: §11.6 | Roadmap: 3.2

package com.mash.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mash.data.db.MessageEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun MessagesScreen(viewModel: MessagesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Scroll to top when new messages arrive.
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Connection banner ──────────────────────────────────────────────
        if (!uiState.isConnected) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Not connected to MASH device",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
            }
        }

        // ── Error snackbar ─────────────────────────────────────────────────
        uiState.errorMessage?.let { err ->
            Snackbar(
                modifier = Modifier.padding(8.dp),
                action = { TextButton(onClick = viewModel::dismissError) { Text("OK") } }
            ) { Text(err) }
        }

        // ── Message list ───────────────────────────────────────────────────
        LazyColumn(
            state       = listState,
            modifier    = Modifier.weight(1f),
            reverseLayout = true, // newest at bottom
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(uiState.messages, key = { it.id }) { msg ->
                MessageItem(msg)
            }
        }

        // ── Compose field ──────────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value         = uiState.draftText,
                onValueChange = viewModel::onDraftChanged,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("Message…") },
                supportingText = {
                    val byteCount = uiState.draftText.encodeToByteArray().size
                    Text("$byteCount / 228 bytes", fontSize = 10.sp)
                },
                maxLines      = 4,
                enabled       = uiState.isConnected && !uiState.isSending,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (uiState.isSending) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            } else {
                Button(
                    onClick  = viewModel::sendMessage,
                    enabled  = uiState.isConnected && uiState.draftText.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageItem(msg: MessageEntity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = msg.fromCallsign,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = TIME_FMT.format(Date(msg.timestampMs)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(text = msg.text, fontSize = 14.sp)
    }
}

