// AirTAK Android — ui/messages/MessagesScreen.kt
// Planning: §11.6
// Roadmap: 3.5

package com.airtak.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import com.airtak.data.db.RemoteNodeEntity
import com.airtak.proto.ChannelRole
import com.airtak.proto.ChannelSlot
import com.airtak.ui.components.HudCard
import com.airtak.ui.components.SectionLabel
import com.airtak.ui.components.TacticalText
import com.airtak.ui.theme.AccentAmber
import com.airtak.ui.theme.AccentCyan
import com.airtak.ui.theme.StatusActive
import com.airtak.ui.theme.StatusHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen(
    viewModel: MessagesViewModel = hiltViewModel(),
    dmViewModel: DirectMessagesViewModel = hiltViewModel(),
    pendingDmNodeId: Long? = null,
    onDmOpened: () -> Unit = {},
) {
    val slots by viewModel.activeSlots.collectAsState()
    // DM tab is always the last tab, at index slots.size.
    val dmTabIndex = slots.size

    var tab by remember { mutableIntStateOf(0) }
    // Clamp tab to valid range when slots list changes size.
    LaunchedEffect(slots.size) { if (tab > dmTabIndex) tab = 0 }
    LaunchedEffect(tab) { if (tab < dmTabIndex) viewModel.selectChannel(tab) }
    // Cross-screen DM deep-link: switch to DM tab and open conversation.
    LaunchedEffect(pendingDmNodeId) {
        if (pendingDmNodeId != null) {
            tab = dmTabIndex
            dmViewModel.openConversationById(pendingDmNodeId)
            onDmOpened()
        }
    }

    val messages by viewModel.messages.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val error by viewModel.error.collectAsState()
    val byteCount = draft.toByteArray(Charsets.UTF_8).size
    val activeSlot = slots.getOrNull(tab)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 0.dp,
        ) {
            slots.forEachIndexed { i, slot ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Icon(slotIcon(slot), contentDescription = null) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(slot.name.ifBlank { "Slot ${slot.index}" }.uppercase(),
                                 style = MaterialTheme.typography.labelLarge)
                            Text(slot.role.shortLabel(), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            }
            Tab(
                selected = tab == dmTabIndex,
                onClick = { tab = dmTabIndex },
                icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DIRECT", style = MaterialTheme.typography.labelLarge)
                        Text("private", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        if (tab == dmTabIndex) {
            DirectMessagesPane(dmViewModel)
        } else if (activeSlot != null) {
            SlotHint(activeSlot)
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
                        placeholder = { Text("Message ${activeSlot.name.ifBlank { "Slot ${activeSlot.index}" }}…",
                                            style = MaterialTheme.typography.bodyMedium) },
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
                    IconButton(onClick = { viewModel.send() }, enabled = draft.isNotBlank()) {
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
}

@Composable
private fun SlotHint(slot: ChannelSlot) {
    val accent = when (slot.role) {
        ChannelRole.PRIMARY   -> MaterialTheme.colorScheme.primary
        ChannelRole.SECONDARY -> AccentAmber
        else                  -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    HudCard(Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(slotIcon(slot), contentDescription = null, tint = accent)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.name.ifBlank { "Slot ${slot.index}" },
                     style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSurface)
                TacticalText(
                    buildString {
                        append(if (slot.psk.size() == 0) "Open" else "Encrypted")
                        append(" · ")
                        append(slot.role.shortLabel())
                        if (slot.index == 0) append(" · Meshtastic interop")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun slotIcon(slot: ChannelSlot) = when {
    slot.index == 0              -> Icons.Outlined.Groups
    slot.role == ChannelRole.PRIMARY -> Icons.Outlined.Groups
    else                         -> Icons.Outlined.SportsEsports
}

private fun ChannelRole.shortLabel(): String = when (this) {
    ChannelRole.PRIMARY   -> "primary"
    ChannelRole.SECONDARY -> "secondary"
    else                  -> "disabled"
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
                    if (m.outgoing) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = if (m.delivered) Icons.Outlined.Done else Icons.Outlined.Schedule,
                            contentDescription = if (m.delivered) "Delivered" else "Pending",
                            tint = if (m.delivered) StatusActive else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(m.text, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}



// ── Direct Messages pane ─────────────────────────────────────────────────────

@Composable
private fun DirectMessagesPane(vm: DirectMessagesViewModel) {
    val activePeer by vm.activePeer.collectAsState()
    val peers by vm.peers.collectAsState()
    val dmPeers by vm.dmPeers.collectAsState()

    if (activePeer != null) {
        DmConversation(vm, activePeer!!)
    } else {
        DmPeerList(peers = peers, recentPeers = dmPeers, onOpen = vm::openConversation)
    }
}

@Composable
private fun DmPeerList(
    peers: List<RemoteNodeEntity>,
    recentPeers: List<MessageEntity>,
    onOpen: (RemoteNodeEntity) -> Unit,
) {
    val recentPeerIds = recentPeers.map { it.peerNodeId }.toSet()
    val recent = peers.filter { it.nodeId in recentPeerIds }
    val others = peers.filter { it.nodeId !in recentPeerIds }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recent.isNotEmpty()) {
            item { SectionLabel("Recent conversations") }
            items(recent, key = { it.nodeId }) { node ->
                val lastMsg = recentPeers.firstOrNull { it.peerNodeId == node.nodeId }
                DmPeerRow(node = node, preview = lastMsg?.text, onOpen = onOpen)
            }
        }
        if (others.isNotEmpty()) {
            item { SectionLabel("Start a conversation") }
            items(others, key = { it.nodeId }) { node ->
                DmPeerRow(node = node, preview = null, onOpen = onOpen)
            }
        }
        if (peers.isEmpty()) {
            item {
                HudCard(Modifier.fillMaxWidth()) {
                    Text(
                        "No nodes seen yet. Direct messages can be sent to any node that has been heard on the mesh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DmPeerRow(node: RemoteNodeEntity, preview: String?, onOpen: (RemoteNodeEntity) -> Unit) {
    HudCard(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(node) },
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.callsign.ifBlank { node.shortName.ifBlank {
                        "Node ${node.nodeId.toString(16).uppercase().padStart(8, '0')}"
                    }},
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!preview.isNullOrBlank()) {
                    TacticalText(
                        preview.take(60) + if (preview.length > 60) "…" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DmConversation(vm: DirectMessagesViewModel, peer: RemoteNodeEntity) {
    val messages by vm.messages.collectAsState()
    val draft by vm.draft.collectAsState()
    val error by vm.error.collectAsState()
    val byteCount = draft.toByteArray(Charsets.UTF_8).size
    val peerLabel = peer.callsign.ifBlank { peer.shortName.ifBlank {
        "Node ${peer.nodeId.toString(16).uppercase().padStart(8, '0')}"
    }}

    Column(Modifier.fillMaxSize()) {
        // Header with back button
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeConversation) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to peer list",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                Text(peerLabel, style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSurface)
                TacticalText(
                    "Direct · unicast · ✓ = received by device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                HudCard(Modifier.fillMaxWidth()) {
                    Text(
                        "No messages yet. Your message will be routed through the mesh to $peerLabel.",
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
                    onValueChange = vm::setDraft,
                    placeholder = { Text("Message $peerLabel…", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                IconButton(onClick = vm::send, enabled = draft.isNotBlank()) {
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
                TacticalText(
                    "$byteCount / 237 B",
                    color = if (byteCount > 237) StatusHit else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Spacer(Modifier.size(12.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = StatusHit)
                }
            }
        }
    }
}
