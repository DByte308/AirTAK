// AirTAK — android unit test: SettingsViewModel import regression for the #6 fix.
//
// Regression for the QR/ShareableConfig import bug found in review: importShareableConfig's
// doc says the device names and callsign are "left untouched," but it built the applied GAME
// message with `.setCallsign("")`, which (with the explicit-empty-wire encoding) would CLEAR the
// device's callsign on import. The fix builds the applied GAME with the LOCAL callsign instead:
//     sc.game.toBuilder().setCallsign(_game.value.callsign).build()
//
// These tests pin the contract WITHOUT an Android device or QR renderer:
//   - the shared config's callsign is IGNORED (local callsign wins);
//   - the local callsign is preserved across import (not overwritten, not cleared);
//   - the imported hierarchy (fireteam/squad/platoon/company) + player_color_rgb + hit_button_enabled
//     are NOT dropped (a common regression when the applied message is rebuilt);
//   - the DEVICE config group is never applied (names are unique per device);
//   - a freshly generated identity's callsign survives an import.
//
// Purely a ViewModel + MockK test: ConfigRepository.groupBytes and DeviceStatusRepository.status are
// stubbed to idle StateFlows; AppPreferences is relaxed with the construction-read methods pinned.

package com.airtak.ui.settings

import androidx.lifecycle.ViewModelStore
import com.airtak.config.constants.UNIT_CHANNEL_SLOT_DEFAULT
import com.airtak.data.config.ConfigRepository
import com.airtak.data.moderation.ModerationRepository
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ChannelConfig
import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceStatus
import com.airtak.proto.GameConfig
import com.airtak.proto.LoraConfig
import com.airtak.proto.ShareableConfig
import com.airtak.ui.i18n.AppLanguage
import com.airtak.ui.theme.ThemeMode
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.just
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIdentityImportTest {

    private lateinit var config: ConfigRepository
    private lateinit var prefs: AppPreferences
    private lateinit var moderation: ModerationRepository
    private lateinit var vm: SettingsViewModel
    private val store = ViewModelStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        config = mockk()
        prefs = mockk(relaxed = true)
        val statusRepo = mockk<DeviceStatusRepository>()
        moderation = mockk(relaxed = true)

        // Methods SettingsViewModel reads while its properties are being initialised.
        every { config.groupBytes } returns MutableStateFlow<Map<ConfigGroup, ByteArray>>(emptyMap())
        every { config.apply(any(), any()) } just Runs
        every { statusRepo.status } returns MutableStateFlow<DeviceStatus?>(null)
        every { prefs.adcMultiplier() } returns 0f
        // unitChannelSlot(level): Int — the signature is non-null Int (NOT nullable); default -1.
        every { prefs.unitChannelSlot(any()) } returns UNIT_CHANNEL_SLOT_DEFAULT
        every { prefs.themeMode() } returns ThemeMode.System
        every { prefs.language() } returns AppLanguage.System
        every { prefs.tileServerUrl() } returns null

        vm = SettingsViewModel(config, prefs, statusRepo, moderation)
        store.put("settings", vm)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun sharedGame(game: GameConfig): ShareableConfig =
        ShareableConfig.newBuilder().setGame(game).build()

    private fun appliedGame(): GameConfig {
        val captured = CapturingSlot<ByteArray>()
        verify { config.apply(ConfigGroup.GAME, capture(captured)) }
        return GameConfig.parseFrom(captured.captured)
    }

    @Test
    fun importedSharedCallsignIsIgnoredAndLocalCallsignIsUsed() {
        vm.updateGame { copy(callsign = "LOCAL") }
        vm.importShareableConfig(
            sharedGame(
                GameConfig.newBuilder()
                    .setCallsign("IMPORTED")
                    .setFireteamId("Alpha").setSquadId("1st").setPlatoonId("Red").setCompanyId("C")
                    .setPlayerColorRgb(0x2196F3).setHitButtonEnabled(true)
                    .build(),
            ),
        )
        assertEquals("LOCAL", appliedGame().callsign)
    }

    @Test
    fun localCallsignIsPreservedAndNotClearedByAnImport() {
        vm.updateGame { copy(callsign = "LOCAL") }
        vm.importShareableConfig(
            sharedGame(GameConfig.newBuilder().setCallsign("IMPORTED").build()),
        )
        assertEquals("LOCAL", vm.game.value.callsign)
    }

    @Test
    fun importedHierarchyColorAndHitButtonAreNotDropped() {
        vm.updateGame { copy(callsign = "LOCAL") }
        vm.importShareableConfig(
            sharedGame(
                GameConfig.newBuilder()
                    .setCallsign("IMPORTED")
                    .setFireteamId("Alpha").setSquadId("1st").setPlatoonId("Red").setCompanyId("C")
                    .setPlayerColorRgb(0x2196F3).setHitButtonEnabled(true)
                    .build(),
            ),
        )
        val g = appliedGame()
        assertEquals("Alpha", g.fireteamId)
        assertEquals("1st", g.squadId)
        assertEquals("Red", g.platoonId)
        assertEquals("C", g.companyId)
        assertEquals(0x2196F3, g.playerColorRgb)
        assertTrue(g.hitButtonEnabled)
    }

    @Test
    fun importDoesNotApplyTheDeviceConfigGroup() {
        vm.importShareableConfig(
            sharedGame(GameConfig.newBuilder().setCallsign("X").build()),
        )
        verify(exactly = 0) { config.apply(ConfigGroup.DEVICE, any()) }
    }

    @Test
    fun freshlyGeneratedDraftCallsignIsKeptOnImport() {
        vm.generateRandomIdentity()
        val generated = vm.game.value.callsign
        assertTrue(generated.isNotBlank(), "generateRandomIdentity must produce a callsign")
        vm.importShareableConfig(
            sharedGame(GameConfig.newBuilder().setCallsign("IMPORTED").build()),
        )
        assertEquals(generated, appliedGame().callsign)
        assertEquals(generated, vm.game.value.callsign)
        assertTrue(generated != "IMPORTED")
    }

    @Test
    fun importedLoraAndChannelsAreForwardedButGameCallsignStillLocal() {
        vm.updateGame { copy(callsign = "LOCAL") }
        val shared = ShareableConfig.newBuilder()
            .setLora(LoraConfig.newBuilder().build())
            .setChannels(ChannelConfig.newBuilder().build())
            .setGame(GameConfig.newBuilder().setCallsign("IMPORTED").build())
            .build()
        vm.importShareableConfig(shared)
        verify { config.apply(ConfigGroup.LORA, any()) }
        verify { config.apply(ConfigGroup.CHANNELS, any()) }
        assertEquals("LOCAL", appliedGame().callsign)
    }
}
