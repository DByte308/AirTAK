package com.airtak.ui.settings

import androidx.lifecycle.ViewModelStore
import com.airtak.data.config.ConfigRepository
import com.airtak.data.moderation.ModerationRepository
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.prefs.ExitMode
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceStatus
import com.airtak.proto.GameConfig
import com.airtak.proto.PositionConfig
import com.airtak.proto.PowerConfig
import com.airtak.ui.i18n.AppLanguage
import com.airtak.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsNumericDraftTest {
    private lateinit var vm: SettingsViewModel
    private lateinit var prefs: AppPreferences
    private lateinit var config: ConfigRepository
    private val groups = MutableStateFlow<Map<ConfigGroup, ByteArray>>(emptyMap())
    private val store = ViewModelStore()

    @BeforeEach fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = mockk(relaxed = true)
        config = mockk(relaxed = true)
        every { prefs.adcMultiplier() } returns 0f
        every { prefs.themeMode() } returns ThemeMode.System
        every { prefs.language() } returns AppLanguage.System
        every { prefs.exitMode() } returns ExitMode.DoubleBack
        every { prefs.tileServerUrl() } returns null
        every { config.groupBytes } returns groups
        val status = mockk<DeviceStatusRepository>()
        every { status.status } returns MutableStateFlow<DeviceStatus?>(null)
        vm = SettingsViewModel(config, prefs, status, mockk<ModerationRepository>(relaxed = true))
        store.put("settings", vm)
    }

    @AfterEach fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun allNumericDraftsPreserveEmptyAndPartialTextWithoutSending() {
        SettingsNumericField.entries.forEach { field ->
            listOf("", "0", "007").forEach { text ->
                vm.setNumberText(field, text)
                assertEquals(text, vm.numberInputs.value.getValue(field).text)
            }
        }
        verify(exactly = 0) { config.apply(any(), any()) }
        verify(exactly = 0) { prefs.setAdcMultiplier(any()) }
    }

    @Test fun invalidPositionBlocksBothPowerAndPositionWrites() {
        vm.setNumberText(SettingsNumericField.BroadcastInterval, "")
        assertFalse(vm.positionPowerInputValid)
        assertFalse(vm.applyPositionAndPower())
        assertFalse(vm.applyPosition())
        verify(exactly = 0) { config.apply(any(), any()) }
        verify(exactly = 0) { prefs.setAdcMultiplier(any()) }
    }

    @Test fun invalidGapCannotSendAnyPartOfTheForm() {
        vm.setNumberText(SettingsNumericField.MinimumTxInterval, "2147483648")
        assertFalse(vm.applyPositionAndPower())
        verify(exactly = 0) { config.apply(any(), any()) }
    }

    @Test fun invalidTimeoutBlocksBothWritesAndPreferenceChanges() {
        vm.setNumberText(SettingsNumericField.DisplayTimeout, "-1")
        assertFalse(vm.applyPositionAndPower())
        assertFalse(vm.applyPower())
        verify(exactly = 0) { config.apply(any(), any()) }
        verify(exactly = 0) { prefs.setAdcMultiplier(any()) }
    }

    @Test fun invalidAdcStillBlocksTheEntirePositionPowerForm() {
        vm.setAdcMultiplierText("")
        assertFalse(vm.applyPositionAndPower())
        verify(exactly = 0) { config.apply(any(), any()) }
    }

    @Test fun validSecondsAreConvertedOnceOnApply() {
        vm.setNumberText(SettingsNumericField.BroadcastInterval, "0075")
        vm.setNumberText(SettingsNumericField.MinimumTxInterval, "0015")
        vm.setNumberText(SettingsNumericField.DisplayTimeout, "0")
        assertTrue(vm.applyPositionAndPower())
        val position = slot<ByteArray>()
        val power = slot<ByteArray>()
        verify(exactly = 1) { config.apply(ConfigGroup.POSITION, capture(position)) }
        verify(exactly = 1) { config.apply(ConfigGroup.POWER, capture(power)) }
        assertEquals(75, PositionConfig.parseFrom(position.captured).broadcastIntervalSecs)
        assertEquals(15, PositionConfig.parseFrom(position.captured).minTxIntervalSecs)
        assertEquals(0, PowerConfig.parseFrom(power.captured).displayOffSecs)
    }

    @Test fun remoteReadsDoNotOverwriteAnyEditedNumber() {
        SettingsNumericField.entries.forEach { vm.setNumberText(it, "") }
        groups.value = mapOf(
            ConfigGroup.POSITION to PositionConfig.newBuilder().setBroadcastIntervalSecs(120).setMinTxIntervalSecs(30).build().toByteArray(),
            ConfigGroup.POWER to PowerConfig.newBuilder().setDisplayOffSecs(60).build().toByteArray(),
            ConfigGroup.GAME to GameConfig.newBuilder().setPlayerColorRgb(0xABCDEF).build().toByteArray(),
        )
        SettingsNumericField.entries.forEach {
            assertEquals("", vm.numberInputs.value.getValue(it).text)
        }
    }

    @Test fun explicitReadDiscardsOnlyTheRequestedGroupsDrafts() {
        SettingsNumericField.entries.forEach { vm.setNumberText(it, "") }
        vm.readPosition()
        assertEquals("90", vm.numberInputs.value.getValue(SettingsNumericField.BroadcastInterval).text)
        assertEquals("15", vm.numberInputs.value.getValue(SettingsNumericField.MinimumTxInterval).text)
        assertEquals("", vm.numberInputs.value.getValue(SettingsNumericField.DisplayTimeout).text)
        assertEquals("", vm.numberInputs.value.getValue(SettingsNumericField.PlayerColor).text)
        vm.readPower()
        assertEquals("0", vm.numberInputs.value.getValue(SettingsNumericField.DisplayTimeout).text)
        vm.readGame()
        assertTrue(vm.numberInputs.value.getValue(SettingsNumericField.PlayerColor).isValid)
    }

    @Test fun invalidColorAlsoBlocksDeviceNameWrite() {
        vm.setNumberText(SettingsNumericField.PlayerColor, "FF")
        assertFalse(vm.playerInputValid)
        assertFalse(vm.applyPlayerAndDevice())
        assertFalse(vm.applyGame())
        verify(exactly = 0) { config.apply(any(), any()) }
    }

    @Test fun completeHexColorIsAppliedWithoutLosingLeadingZeros() {
        vm.setNumberText(SettingsNumericField.PlayerColor, "00ab0f")
        assertTrue(vm.applyPlayerAndDevice())
        val bytes = slot<ByteArray>()
        verify(exactly = 1) { config.apply(ConfigGroup.DEVICE, any()) }
        verify(exactly = 1) { config.apply(ConfigGroup.GAME, capture(bytes)) }
        assertEquals(0x00AB0F, GameConfig.parseFrom(bytes.captured).playerColorRgb)
    }
}
