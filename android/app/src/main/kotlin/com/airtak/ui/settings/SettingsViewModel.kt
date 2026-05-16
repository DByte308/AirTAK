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
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.AdminCommand
import com.airtak.proto.AdminRequest
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ChannelSlot
import com.airtak.proto.ConfigGroup
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
    data class PositionState(
        val broadcastIntervalSecs: Int = 90,
        val minTxIntervalSecs: Int = 15,
        val smartBroadcast: Boolean = true,
    )
    data class PowerState(val gpsDutyCycle: Boolean = false, val displayOffSecs: Int = 0)
    data class GameState(val callsign: String = "", val teamId: Int = 0, val isAdmin: Boolean = false)
    data class SecurityState(val maxBonds: Int = 1)

    private val _device   = MutableStateFlow(DeviceState());   val device:   StateFlow<DeviceState>   = _device.asStateFlow()
    private val _lora     = MutableStateFlow(LoraState());     val lora:     StateFlow<LoraState>     = _lora.asStateFlow()
    private val _channels = MutableStateFlow(ChannelsState()); val channels: StateFlow<ChannelsState> = _channels.asStateFlow()
    private val _position = MutableStateFlow(PositionState()); val position: StateFlow<PositionState> = _position.asStateFlow()
    private val _power    = MutableStateFlow(PowerState());    val power:    StateFlow<PowerState>    = _power.asStateFlow()
    private val _game     = MutableStateFlow(GameState());     val game:     StateFlow<GameState>     = _game.asStateFlow()
    private val _security = MutableStateFlow(SecurityState()); val security: StateFlow<SecurityState> = _security.asStateFlow()

    init {
        viewModelScope.launch {
            config.groupBytes.collect { map ->
                map[ConfigGroup.DEVICE]?.let { b -> tryDecode { DeviceConfig.parseFrom(b) } }?.let {
                    _device.value = DeviceState(it.longName, it.shortName)
                }
                map[ConfigGroup.LORA]?.let { b -> tryDecode { LoraConfig.parseFrom(b) } }?.let {
                    _lora.value = LoraState(it.region, it.txPowerDbm, it.profilesList.toList())
                }
                map[ConfigGroup.CHANNELS]?.let { b -> tryDecode { ChannelConfig.parseFrom(b) } }?.let {
                    _channels.value = ChannelsState(it.slotsList.toList())
                }
                map[ConfigGroup.POSITION]?.let { b -> tryDecode { PositionConfig.parseFrom(b) } }?.let {
                    _position.value = PositionState(it.broadcastIntervalSecs, it.minTxIntervalSecs, it.smartBroadcast)
                }
                map[ConfigGroup.POWER]?.let { b -> tryDecode { PowerConfig.parseFrom(b) } }?.let {
                    _power.value = PowerState(it.gpsDutyCycle, it.displayOffSecs)
                }
                map[ConfigGroup.GAME]?.let { b -> tryDecode { GameConfig.parseFrom(b) } }?.let {
                    _game.value = GameState(it.callsign, it.teamId, it.isAdmin)
                }
                map[ConfigGroup.SECURITY]?.let { b -> tryDecode { SecurityConfig.parseFrom(b) } }?.let {
                    _security.value = SecurityState(it.maxBondedDevices)
                }
            }
        }
    }

    fun updateDevice(block: DeviceState.() -> DeviceState)     { _device.value   = _device.value.block() }
    fun updateLora(block: LoraState.() -> LoraState)           { _lora.value     = _lora.value.block() }
    fun updatePosition(block: PositionState.() -> PositionState){_position.value = _position.value.block() }
    fun updatePower(block: PowerState.() -> PowerState)        { _power.value    = _power.value.block() }
    fun updateGame(block: GameState.() -> GameState)           { _game.value     = _game.value.block() }
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
            .setCallsign(s.callsign).setTeamId(s.teamId).setIsAdmin(s.isAdmin).build()
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
}
