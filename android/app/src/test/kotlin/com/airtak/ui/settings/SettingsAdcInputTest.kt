package com.airtak.ui.settings

import androidx.lifecycle.ViewModelStore
import com.airtak.data.config.ConfigRepository
import com.airtak.data.moderation.ModerationRepository
import com.airtak.data.prefs.AppPreferences
import com.airtak.data.prefs.ExitMode
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceStatus
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
class SettingsAdcInputTest {
    private lateinit var vm: SettingsViewModel
    private lateinit var prefs: AppPreferences
    private lateinit var config: ConfigRepository
    private val groups = MutableStateFlow<Map<ConfigGroup, ByteArray>>(emptyMap())
    private val store = ViewModelStore()

    @BeforeEach fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = mockk(relaxed = true)
        config = mockk(relaxed = true)
        every { prefs.adcMultiplier() } returns 2.56f
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

    @Test fun typingNeverPersistsOrSendsCalibration() {
        listOf("", "2", "2,", "2,0", "2,005").forEach { text ->
            vm.setAdcMultiplierText(text)
            assertEquals(text, vm.adcMultiplierInput.value.text)
        }
        verify(exactly = 0) { prefs.setAdcMultiplier(any()) }
        verify(exactly = 0) { config.apply(any(), any()) }
    }

    @Test fun applyUsesTheCurrentTextValue() {
        vm.setAdcMultiplierText("2,005")
        val bytes = slot<ByteArray>()
        assertTrue(vm.applyPower())
        verify { config.apply(ConfigGroup.POWER, capture(bytes)) }
        assertEquals(2.005f, PowerConfig.parseFrom(bytes.captured).adcMultiplier)
        verify(exactly = 1) { prefs.setAdcMultiplier(2.005f) }
    }

    @Test fun invalidInputCannotSendThePreviousValidNumber() {
        vm.setAdcMultiplierText("3.25")
        vm.setAdcMultiplierText("")
        assertFalse(vm.applyPower())
        vm.setAdcMultiplierText("101")
        assertFalse(vm.applyPower())
        vm.setAdcMultiplierText("NaN")
        assertFalse(vm.applyPower())
        verify(exactly = 0) { config.apply(any(), any()) }
        verify(exactly = 0) { prefs.setAdcMultiplier(any()) }
    }

    @Test fun incomingConfigDoesNotOverwriteAnEditInProgress() {
        vm.setAdcMultiplierText("2,")
        groups.value = mapOf(ConfigGroup.POWER to PowerConfig.newBuilder().setAdcMultiplier(3.5f).build().toByteArray())
        assertEquals("2,", vm.adcMultiplierInput.value.text)
    }

    @Test fun explicitReadDiscardsTheDraftAndShowsNewDeviceValue() {
        vm.setAdcMultiplierText("2,")
        vm.readPower()
        groups.value = mapOf(ConfigGroup.POWER to PowerConfig.newBuilder().setAdcMultiplier(3.5f).build().toByteArray())
        assertEquals(3.5f, vm.adcMultiplierInput.value.value)
        verify { config.read(ConfigGroup.POWER) }
    }

    @Test fun deviceZeroReallyRestoresTheBoardDefaultDisplay() {
        groups.value = mapOf(ConfigGroup.POWER to PowerConfig.getDefaultInstance().toByteArray())
        assertEquals("0", vm.adcMultiplierInput.value.text)
        assertEquals(0f, vm.power.value.adcMultiplier)
    }
}
