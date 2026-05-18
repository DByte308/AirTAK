// AirTAK Android — ui/settings/SettingsViewModel.kt
//
// One ViewModel orchestrates all per-group editor sub-screens. The repository
// exposes a reactive `groupBytes` map keyed by ConfigGroup; we observe that
// flow once and decode each group into its own view-state on update. Read /
// Apply are fire-and-forget against the BLE bridge.

package com.airtak.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airtak.data.config.ConfigRepository
import com.airtak.data.moderation.ModerationRepository
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.AdminCommand
import com.airtak.proto.AdminRequest
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ChannelRole
import com.airtak.proto.ChannelSlot
import com.airtak.proto.ConfigGroup
import com.airtak.proto.ModerationAction
import com.airtak.proto.Scope
import com.airtak.proto.DeviceConfig
import com.airtak.proto.DeviceStatus
import com.airtak.proto.GameConfig
import com.airtak.proto.LoraConfig
import com.airtak.proto.LoraRegion
import com.airtak.proto.PhyProfile
import com.airtak.proto.PositionConfig
import com.airtak.proto.PowerConfig
import com.airtak.proto.SecurityConfig
import com.airtak.ui.theme.ThemeMode
import com.google.protobuf.ByteString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val prefs: AppPreferences,
    statusRepo: DeviceStatusRepository,
    val moderation: ModerationRepository,
) : ViewModel() {

    val deviceStatus: StateFlow<DeviceStatus?> = statusRepo.status
    val busy: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    data class DeviceState(val longName: String = "", val shortName: String = "")
    data class LoraState(
        val region: LoraRegion = LoraRegion.EU_868,
        val txPower: Int = 22,
        val profiles: List<PhyProfile> = emptyList(),
    )
    data class ChannelsState(val slots: List<ChannelSlot> = emptyList())
    data class ChannelSlotEditor(
        val slot: ChannelSlot = ChannelSlot.getDefaultInstance(),
        val pskText: String = "",
        val pskRevealed: Boolean = false,
        val pskError: String? = null,
    )
    data class EditableChannelsState(
        val slots: List<ChannelSlotEditor> = emptyList(),
        val validationError: String? = null,
    )
    data class PositionState(
        val broadcastIntervalSecs: Int = 90,
        val minTxIntervalSecs: Int = 15,
        val smartBroadcast: Boolean = true,
    )
    data class PowerState(val gpsDutyCycle: Boolean = false, val displayOffSecs: Int = 0)
    data class GameState(
        val callsign: String = "",
        val fireteamId: String = "",
        val squadId: String = "",
        val platoonId: String = "",
        val companyId: String = "",
        val playerColorRgb: Int = 0xFFFFFF,
        val hitButtonEnabled: Boolean = false,
        val fireteamChannelSlot: Int = -1,
        val squadChannelSlot: Int = -1,
        val platoonChannelSlot: Int = -1,
        val companyChannelSlot: Int = -1,
    )
    data class SecurityState(val maxBonds: Int = 1)

    private val _device   = MutableStateFlow(DeviceState());   val device:   StateFlow<DeviceState>   = _device.asStateFlow()
    private val _lora     = MutableStateFlow(LoraState());     val lora:     StateFlow<LoraState>     = _lora.asStateFlow()
    private val _channels = MutableStateFlow(EditableChannelsState()); val channels: StateFlow<EditableChannelsState> = _channels.asStateFlow()
    private val _position = MutableStateFlow(PositionState()); val position: StateFlow<PositionState> = _position.asStateFlow()
    private val _power    = MutableStateFlow(PowerState());    val power:    StateFlow<PowerState>    = _power.asStateFlow()
    private val _game     = MutableStateFlow(GameState());     val game:     StateFlow<GameState>     = _game.asStateFlow()
    private val _security = MutableStateFlow(SecurityState()); val security: StateFlow<SecurityState> = _security.asStateFlow()

    init {
        _game.value = _game.value.copy(
            fireteamChannelSlot = prefs.unitChannelSlot("fireteam"),
            squadChannelSlot = prefs.unitChannelSlot("squad"),
            platoonChannelSlot = prefs.unitChannelSlot("platoon"),
            companyChannelSlot = prefs.unitChannelSlot("company"),
        )
        viewModelScope.launch {
            config.groupBytes.collect { map ->
                map[ConfigGroup.DEVICE]?.let { b -> tryDecode { DeviceConfig.parseFrom(b) } }?.let {
                    _device.value = DeviceState(it.longName, it.shortName)
                }
                map[ConfigGroup.LORA]?.let { b -> tryDecode { LoraConfig.parseFrom(b) } }?.let {
                    _lora.value = LoraState(it.region, it.txPowerDbm, it.profilesList.toList())
                }
                map[ConfigGroup.CHANNELS]?.let { b -> tryDecode { ChannelConfig.parseFrom(b) } }?.let {
                    _channels.value = EditableChannelsState(
                        slots = it.slotsList.map { slot ->
                            ChannelSlotEditor(slot = slot, pskText = slot.psk.toPskText())
                        },
                    )
                }
                map[ConfigGroup.POSITION]?.let { b -> tryDecode { PositionConfig.parseFrom(b) } }?.let {
                    _position.value = PositionState(it.broadcastIntervalSecs, it.minTxIntervalSecs, it.smartBroadcast)
                }
                map[ConfigGroup.POWER]?.let { b -> tryDecode { PowerConfig.parseFrom(b) } }?.let {
                    _power.value = PowerState(it.gpsDutyCycle, it.displayOffSecs)
                }
                map[ConfigGroup.GAME]?.let { b -> tryDecode { GameConfig.parseFrom(b) } }?.let {
                    _game.value = GameState(
                        it.callsign,
                        it.fireteamId,
                        it.squadId,
                        it.platoonId,
                        it.companyId,
                        it.playerColorRgb,
                        it.hitButtonEnabled,
                        fireteamChannelSlot = prefs.unitChannelSlot("fireteam"),
                        squadChannelSlot = prefs.unitChannelSlot("squad"),
                        platoonChannelSlot = prefs.unitChannelSlot("platoon"),
                        companyChannelSlot = prefs.unitChannelSlot("company"),
                    )
                }
                map[ConfigGroup.SECURITY]?.let { b -> tryDecode { SecurityConfig.parseFrom(b) } }?.let {
                    _security.value = SecurityState(it.maxBondedDevices)
                }
            }
        }
    }

    fun updateDevice(block: DeviceState.() -> DeviceState)     { _device.value   = _device.value.block() }
    fun updateLora(block: LoraState.() -> LoraState)           { _lora.value     = _lora.value.block() }
    fun updateProfile(index: Int, block: PhyProfile.Builder.() -> Unit) {
        _lora.value = _lora.value.let { state ->
            state.copy(
                profiles = state.profiles.mapIndexed { i, profile ->
                    if (i == index) profile.toBuilder().apply(block).build() else profile
                },
            )
        }
    }
    fun updateChannelSlot(index: Int, block: ChannelSlot.Builder.() -> Unit) {
        _channels.value = _channels.value.let { state ->
            state.copy(
                slots = state.slots.mapIndexed { i, editor ->
                    if (i == index) editor.copy(slot = editor.slot.toBuilder().apply(block).build(), pskError = null)
                    else editor
                },
                validationError = null,
            )
        }
    }
    fun updateChannelPsk(index: Int, text: String) {
        val parsed = parsePsk(text)
        _channels.value = _channels.value.let { state ->
            state.copy(
                slots = state.slots.mapIndexed { i, editor ->
                    if (i != index) return@mapIndexed editor
                    if (parsed.error == null) {
                        editor.copy(
                            slot = editor.slot.toBuilder().setPsk(ByteString.copyFrom(parsed.bytes)).build(),
                            pskText = text,
                            pskError = null,
                        )
                    } else {
                        editor.copy(pskText = text, pskError = parsed.error)
                    }
                },
                validationError = null,
            )
        }
    }
    fun setChannelPskRevealed(index: Int, revealed: Boolean) {
        _channels.value = _channels.value.let { state ->
            state.copy(slots = state.slots.mapIndexed { i, editor ->
                if (i == index) editor.copy(pskRevealed = revealed) else editor
            })
        }
    }
    fun updatePosition(block: PositionState.() -> PositionState){_position.value = _position.value.block() }
    fun updatePower(block: PowerState.() -> PowerState)        { _power.value    = _power.value.block() }
    fun updateGame(block: GameState.() -> GameState) {
        val newState = _game.value.block()
        _game.value = newState
        prefs.setUnitChannelSlot("fireteam", newState.fireteamChannelSlot)
        prefs.setUnitChannelSlot("squad", newState.squadChannelSlot)
        prefs.setUnitChannelSlot("platoon", newState.platoonChannelSlot)
        prefs.setUnitChannelSlot("company", newState.companyChannelSlot)
    }
    fun updateSecurity(block: SecurityState.() -> SecurityState){_security.value = _security.value.block() }

    fun readDevice()   = config.read(ConfigGroup.DEVICE)
    fun readLora()     = config.read(ConfigGroup.LORA)
    fun readChannels() = config.read(ConfigGroup.CHANNELS)
    fun readPosition() = config.read(ConfigGroup.POSITION)
    fun readPower()    = config.read(ConfigGroup.POWER)
    fun readGame()     = config.read(ConfigGroup.GAME)
    fun readSecurity() = config.read(ConfigGroup.SECURITY)

    fun applyDevice() {
        val s = _device.value
        val msg = DeviceConfig.newBuilder().setLongName(s.longName).setShortName(s.shortName).build()
        config.apply(ConfigGroup.DEVICE, msg.toByteArray())
    }
    fun applyLora() {
        val s = _lora.value
        val msg = LoraConfig.newBuilder()
            .setRegion(s.region).setTxPowerDbm(s.txPower).addAllProfiles(s.profiles).build()
        config.apply(ConfigGroup.LORA, msg.toByteArray())
    }
    fun applyChannels() {
        val s = _channels.value
        val firstError = s.slots.firstOrNull { it.pskError != null }?.pskError
        if (firstError != null) {
            _channels.value = s.copy(validationError = firstError)
            return
        }
        val msg = ChannelConfig.newBuilder().addAllSlots(s.slots.map { editor ->
            editor.slot.toBuilder()
                .setIndex(editor.slot.index.coerceIn(0, 7))
                .setName(editor.slot.name.take(16))
                .setRole(editor.slot.role.takeUnless { it == ChannelRole.UNRECOGNIZED } ?: ChannelRole.DISABLED)
                .setPositionPrecision(editor.slot.positionPrecision.coerceIn(0, 32))
                .setPhyProfileIndex(editor.slot.phyProfileIndex.coerceIn(0, 4))
                .build()
        }).build()
        config.apply(ConfigGroup.CHANNELS, msg.toByteArray())
    }
    fun applyPosition() {
        val s = _position.value
        val msg = PositionConfig.newBuilder()
            .setBroadcastIntervalSecs(s.broadcastIntervalSecs)
            .setMinTxIntervalSecs(s.minTxIntervalSecs)
            .setSmartBroadcast(s.smartBroadcast).build()
        config.apply(ConfigGroup.POSITION, msg.toByteArray())
    }
    fun applyPower() {
        val s = _power.value
        val msg = PowerConfig.newBuilder()
            .setGpsDutyCycle(s.gpsDutyCycle).setDisplayOffSecs(s.displayOffSecs).build()
        config.apply(ConfigGroup.POWER, msg.toByteArray())
    }
    fun applyGame() {
        val s = _game.value
        val msg = GameConfig.newBuilder()
            .setCallsign(s.callsign)
            .setFireteamId(s.fireteamId)
            .setSquadId(s.squadId)
            .setPlatoonId(s.platoonId)
            .setCompanyId(s.companyId)
            .setPlayerColorRgb(s.playerColorRgb)
            .setHitButtonEnabled(s.hitButtonEnabled)
            .build()
        config.apply(ConfigGroup.GAME, msg.toByteArray())
    }
    fun applySecurity() {
        val s = _security.value
        val msg = SecurityConfig.newBuilder().setMaxBondedDevices(s.maxBonds).build()
        config.apply(ConfigGroup.SECURITY, msg.toByteArray())
    }

    fun sendAdmin(cmd: AdminCommand) {
        val req = AdminRequest.newBuilder().setCommand(cmd).build()
        config.sendAdmin(req.toByteArray())
    }

    /**
     * Prepare a new unit-scoped channel slot for the given unit level
     * ("fireteam" / "squad" / "platoon" / "company"). Picks the first
     * DISABLED slot with index ≥ 3 (slots 0/1 are global, slot 2 is reserved
     * for audio), enables it as SECONDARY with [suggestedName], binds it to
     * this unit level, and returns the slot index. Returns null if all
     * tactical slots are already in use.
     *
     * The change is staged in the editor state only — the user must press
     * Apply on the Channels section to push it to the device, and provide a
     * PSK (the slot is created without one so it is visible as "open" until
     * the user enters a shared password).
     */
    fun prepareUnitChannel(level: String, suggestedName: String): Int? {
        val current = _channels.value
        val slots = current.slots
        val target = slots.firstOrNull { editor ->
            editor.slot.index >= MIN_UNIT_SLOT_INDEX &&
            editor.slot.role == ChannelRole.DISABLED
        } ?: return null
        val targetIdx = target.slot.index
        val name = suggestedName.take(16).ifBlank { "Slot ${target.slot.index}" }
        _channels.value = current.copy(
            slots = slots.map { editor ->
                if (editor.slot.index == targetIdx) {
                    editor.copy(
                        slot = editor.slot.toBuilder()
                            .setName(name)
                            .setRole(ChannelRole.SECONDARY)
                            .setUplinkEnabled(true)
                            .setDownlinkEnabled(true)
                            .build(),
                    )
                } else editor
            },
        )
        val newGame = _game.value.let { s ->
            when (level) {
                "fireteam" -> s.copy(fireteamChannelSlot = targetIdx)
                "squad"    -> s.copy(squadChannelSlot = targetIdx)
                "platoon"  -> s.copy(platoonChannelSlot = targetIdx)
                "company"  -> s.copy(companyChannelSlot = targetIdx)
                else -> s
            }
        }
        _game.value = newGame
        prefs.setUnitChannelSlot(level, targetIdx)
        return targetIdx
    }

    private companion object {
        /** Slots 0 and 1 are global (mesh/AirTAK-wide). Slot 2 is reserved for PTT audio. */
        const val MIN_UNIT_SLOT_INDEX = 3
    }

    private val _themeMode = MutableStateFlow(prefs.themeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    fun setThemeMode(mode: ThemeMode) { prefs.setThemeMode(mode); _themeMode.value = mode }

    private val _tileUrl = MutableStateFlow(prefs.tileServerUrl().orEmpty())
    val tileUrl: StateFlow<String> = _tileUrl.asStateFlow()
    fun setTileUrl(url: String) {
        prefs.setTileServerUrl(url.ifBlank { null })
        _tileUrl.value = url
    }

    private inline fun <T> tryDecode(block: () -> T): T? = runCatching(block).getOrNull()

    private fun ByteString.toPskText(): String {
        if (isEmpty) return ""
        return "0x" + toByteArray().joinToString("") { "%02X".format(it) }
    }

    fun unbanNode(targetNodeId: Long, scope: com.airtak.proto.Scope) {
        viewModelScope.launch {
            moderation.submitVote(targetNodeId, scope, com.airtak.proto.ModerationAction.MODERATION_ACTION_UNBAN)
        }
    }

    private data class PskParseResult(val bytes: ByteArray, val error: String?)

    private fun parsePsk(text: String): PskParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PskParseResult(ByteArray(0), null)
        val bytes = if (trimmed.startsWith("0x", ignoreCase = true)) {
            val hex = trimmed.drop(2).filterNot { it.isWhitespace() || it == ':' || it == '-' }
            if (hex.length % 2 != 0) return PskParseResult(ByteArray(0), "Hex PSK must have an even number of digits.")
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return PskParseResult(ByteArray(0), "Hex PSK contains a non-hex character.")
            }
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            trimmed.toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > 32) return PskParseResult(bytes, "PSK must be 32 bytes or fewer.")
        return PskParseResult(bytes, null)
    }
}
