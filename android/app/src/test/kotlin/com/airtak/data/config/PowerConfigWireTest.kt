package com.airtak.data.config

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundFrame
import com.airtak.data.ble.LinkState
import com.airtak.proto.ConfigGroup
import com.airtak.proto.ConfigPayload
import com.airtak.proto.PowerConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PowerConfigWireTest {
    @Test fun allZeroPowerSnapshotIsNotConfusedWithCommitOnly() {
        val original = PowerConfig.getDefaultInstance().toByteArray()
        assertTrue(original.isEmpty())
        val wire = powerConfigWireBytes(ConfigGroup.POWER, original)
        assertArrayEquals(byteArrayOf(0x28, 0), wire)
        assertEquals(PowerConfig.getDefaultInstance(), PowerConfig.parseFrom(wire))
    }

    @Test fun regularPowerSnapshotIsUnchanged() {
        val original = PowerConfig.newBuilder().setBrightnessPct(75).setDisplayOffSecs(10).build().toByteArray()
        assertSame(original, powerConfigWireBytes(ConfigGroup.POWER, original))
    }

    @Test fun otherGroupsKeepTheirWireBytes() {
        val empty = byteArrayOf()
        ConfigGroup.entries.filter { it != ConfigGroup.POWER }.forEach {
            assertSame(empty, powerConfigWireBytes(it, empty))
        }
    }

    @Test fun unknownOrMalformedNonemptyFieldsAreNotStripped() {
        val original = byteArrayOf(0xFF.toByte(), 0x80.toByte())
        assertSame(original, powerConfigWireBytes(ConfigGroup.POWER, original))
    }

    @Test fun repositoryUsesZeroScalarForBothStageAndApply() = runTest {
        val sent = mutableListOf<ByteArray>()
        val ble = mockk<BleRepository>(relaxed = true)
        every { ble.linkState } returns MutableStateFlow<LinkState>(LinkState.Disconnected)
        every { ble.inbound } returns MutableSharedFlow<InboundFrame>()
        every { ble.send(any(), any()) } answers { sent += secondArg<ByteArray>(); Unit }
        val repository = ConfigRepository(ble, backgroundScope)
        repository.stage(ConfigGroup.POWER, byteArrayOf())
        repository.apply(ConfigGroup.POWER, byteArrayOf())
        assertEquals(2, sent.size)
        val staged = ConfigPayload.parseFrom(sent[0])
        val applied = ConfigPayload.parseFrom(sent[1])
        assertFalse(staged.commit)
        assertTrue(applied.commit)
        listOf(staged, applied).forEach {
            assertEquals(ConfigGroup.POWER, it.group)
            assertArrayEquals(byteArrayOf(0x28, 0), it.data.toByteArray())
        }
    }
}
