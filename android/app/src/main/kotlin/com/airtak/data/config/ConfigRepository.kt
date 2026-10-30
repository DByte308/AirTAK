// AirTAK Android — data/config/ConfigRepository.kt
// Planning: §10.3 (Config protocol), §11.9 (Settings sections)
// Roadmap: 3.4
//
// Implements the staged config protocol:
//
//   read     : write ConfigPayload(group=…, data=empty, commit=false) → expect a
//              notify carrying the latest serialized group proto
//   stage    : write ConfigPayload(group=…, data=<bytes>, commit=false)
//   apply    : write ConfigPayload(group=…, data=<final bytes>, commit=true)
//
// The device responds with a ConfigAck on each apply. The repository exposes a
// per-group cache (loaded after read) plus a stream of acks for UI feedback.

package com.airtak.data.config

import com.airtak.config.constants.MESH_CONFIG_BUFFER
import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundChannel
import com.airtak.data.ble.LinkState
import com.airtak.proto.ConfigAck
import com.airtak.proto.ConfigGroup
import com.airtak.proto.ConfigPayload
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository internal constructor(
    private val ble: BleRepository,
    private val scope: CoroutineScope,
) {
    @Inject constructor(ble: BleRepository) : this(
        ble, CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Raw most-recent serialized group bytes returned by the device. */
    private val _groupBytes = MutableStateFlow<Map<ConfigGroup, ByteArray>>(emptyMap())
    val groupBytes: StateFlow<Map<ConfigGroup, ByteArray>> = _groupBytes.asStateFlow()

    private val _acks = MutableSharedFlow<ConfigAck>(extraBufferCapacity = MESH_CONFIG_BUFFER)
    val acks: SharedFlow<ConfigAck> = _acks.asSharedFlow()

    init {
        // Subscribe before the connection observer can request a config read.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.inbound
                .filter { it.channel == InboundChannel.ConfigRw }
                .collect { frame ->
                    if (ble.linkState.value !is LinkState.Connected) return@collect
                    // The device emits one of two messages on CONFIG_RW: a
                    // ConfigPayload (in response to a read) or a ConfigAck
                    // (after a commit). Both are protobufs in the airtak
                    // namespace. We try ConfigAck first (smaller, less
                    // ambiguous) and fall back to ConfigPayload.
                    val ack = runCatching { ConfigAck.parseFrom(frame.payload) }.getOrNull()
                    if (ack != null && (ack.success || ack.error.isNotEmpty())) {
                        _acks.emit(ack)
                        if (ack.success && ack.group != ConfigGroup.UNRECOGNIZED) read(ack.group)
                        return@collect
                    }
                    val payload = runCatching { ConfigPayload.parseFrom(frame.payload) }.getOrNull()
                    // Empty serialized groups are valid (e.g. clearing both device names).
                    if (payload != null && payload.group != ConfigGroup.UNRECOGNIZED) {
                        _groupBytes.value = _groupBytes.value.toMutableMap().apply {
                            put(payload.group, payload.data.toByteArray())
                        }
                    } else if (ack != null) {
                        _acks.emit(ack)
                    }
                }
        }
        scope.launch {
            ble.linkState.collect { state ->
                if (state is LinkState.Connected) {
                    // Screen init can run while linking, when BLE drops writes.
                    // Refresh identity on every connection, not only on screen entry.
                    read(ConfigGroup.GAME)
                    read(ConfigGroup.DEVICE)
                } else {
                    // Never display or broadcast the previous device's config.
                    _groupBytes.value = emptyMap()
                }
            }
        }
    }

    fun read(group: ConfigGroup) {
        send(ConfigPayload.newBuilder().setGroup(group).setCommit(false).build())
    }

    fun stage(group: ConfigGroup, data: ByteArray) {
        send(ConfigPayload.newBuilder()
            .setGroup(group)
            .setData(ByteString.copyFrom(identityConfigWireBytes(group, data)))
            .setCommit(false)
            .build())
    }

    fun apply(group: ConfigGroup, data: ByteArray) {
        send(ConfigPayload.newBuilder()
            .setGroup(group)
            .setData(ByteString.copyFrom(identityConfigWireBytes(group, data)))
            .setCommit(true)
            .build())
    }

    private fun send(payload: ConfigPayload) {
        ble.send(BleRepository.OutboundChannel.ConfigRw, payload.toByteArray())
    }

    /** Send a raw AdminRequest proto on the ADMIN characteristic. */
    fun sendAdmin(requestBytes: ByteArray) {
        ble.send(BleRepository.OutboundChannel.Admin, requestBytes)
    }
}
