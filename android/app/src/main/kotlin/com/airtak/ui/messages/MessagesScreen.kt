// AirTAK Android — ui/messages/MessagesScreen.kt
// Planning: §11.6
// Roadmap: 3.5

package com.airtak.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airtak.data.db.MessageEntity
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen(viewModel: MessagesViewModel = hiltViewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) { viewModel.selectChannel(tab) }

    val messages by viewModel.messages.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val error by viewModel.error.collectAsState()
    val byteCount = draft.toByteArray(Charsets.UTF_8).size

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            CHANNELS.forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text(name.uppercase(), style = MaterialTheme.typography.labelLarge)
                })
            }
        }
        Spacer(Modifier.height(12.dp))

        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                HudCard(Modifier.fillMaxWidth()) {
                    Text(
                        "No traffic yet. Messages from your mesh will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(messages, key = { it.id }) { MessageRow(it) }
                }
            }
        }

        // Composer
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::setDraft,
                    placeholder = { Text("Mesh message…", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                IconButton(onClick = { viewModel.send() }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (draft.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val warn = byteCount > 237
                TacticalText(
                    "$byteCount / 237 B",
                    color = if (warn) StatusHit else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Spacer(Modifier.size(12.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = StatusHit)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: MessageEntity) {
    val time = remember(m.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(m.timestampMs))
    }
    val accent = if (m.outgoing) AccentCyan else StatusActive
    HudCard(accent = accent) {
        Row {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (m.outgoing) "You" else m.sourceCallsign.ifBlank {
                            "Node ${m.sourceNodeId.toString(16).uppercase().padStart(8, '0')}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                    Spacer(Modifier.size(8.dp))
                    TacticalText(time, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(m.text, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val CHANNELS = listOf("Mesh", "Game")
