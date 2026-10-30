package com.airtak.data.config

import com.airtak.data.ble.BleRepository
import com.airtak.data.ble.InboundChannel
import com.airtak.data.ble.InboundFrame
import com.airtak.data.ble.LinkState
import com.airtak.proto.ConfigAck
import com.airtak.proto.ConfigGroup
import com.airtak.proto.ConfigPayload
import com.airtak.proto.DeviceConfig
import com.google.protobuf.ByteString
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigRepositoryIdentityTest {
    private class Fixture(scope: TestScope, initial: LinkState = LinkState.Connecting("A")) {
        val link = MutableStateFlow(initial)
        val inbound = MutableSharedFlow<InboundFrame>()
        val sent = mutableListOf<ConfigPayload>()
        val ble = mockk<BleRepository>(relaxed = true)
        val repo: ConfigRepository
        init {
            every { ble.linkState } returns link
            every { ble.inbound } returns inbound
            every { ble.send(BleRepository.OutboundChannel.ConfigRw, any()) } answers {
                sent += ConfigPayload.parseFrom(secondArg<ByteArray>())
            }
            repo = ConfigRepository(ble, scope.backgroundScope)
        }
        suspend fun receive(payload: ByteArray) {
            inbound.emit(InboundFrame(InboundChannel.ConfigRw, payload))
        }
        suspend fun device(name: String) = receive(ConfigPayload.newBuilder()
            .setGroup(ConfigGroup.DEVICE)
            .setData(ByteString.copyFrom(DeviceConfig.newBuilder().setLongName(name).build().toByteArray()))
            .build().toByteArray())
    }

    @Test fun identityReadsWaitForConnectionAndRepeatAfterReconnect() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertTrue(f.sent.isEmpty())
        f.link.value = LinkState.Connected("A", 517)
        runCurrent()
        assertEquals(listOf(ConfigGroup.GAME, ConfigGroup.DEVICE), f.sent.map { it.group })
        assertTrue(f.sent.all { it.data.isEmpty && !it.commit })
        f.link.value = LinkState.Disconnected
        runCurrent()
        f.link.value = LinkState.Connected("A", 517)
        runCurrent()
        assertEquals(4, f.sent.size)
    }

    @Test fun repositoryCreatedWhileAlreadyConnectedAlsoReadsIdentity() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        assertEquals(listOf(ConfigGroup.GAME, ConfigGroup.DEVICE), f.sent.map { it.group })
    }

    @Test fun disconnectClearsPreviousDeviceIdentityAndIgnoresLateFrames() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        f.device("Old board")
        runCurrent()
        assertTrue(f.repo.groupBytes.value.containsKey(ConfigGroup.DEVICE))
        f.link.value = LinkState.Disconnected
        runCurrent()
        assertTrue(f.repo.groupBytes.value.isEmpty())
        f.device("Late old board")
        runCurrent()
        assertTrue(f.repo.groupBytes.value.isEmpty())
        f.link.value = LinkState.Connected("B", 517)
        runCurrent()
        assertTrue(f.repo.groupBytes.value.isEmpty())
    }

    @Test fun emptyDeviceConfigResponseClearsCachedNames() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        f.device("Old name")
        runCurrent()
        f.device("")
        runCurrent()
        val bytes = f.repo.groupBytes.value.getValue(ConfigGroup.DEVICE)
        assertTrue(bytes.isEmpty())
        assertEquals("", DeviceConfig.parseFrom(bytes).longName)
    }

    @Test fun successfulApplyAckRequestsAuthoritativeUpdatedConfig() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        f.sent.clear()
        f.receive(ConfigAck.newBuilder().setGroup(ConfigGroup.GAME).setSuccess(true).build().toByteArray())
        runCurrent()
        assertEquals(listOf(ConfigGroup.GAME), f.sent.map { it.group })
        assertTrue(f.repo.groupBytes.value.isEmpty())
    }

    @Test fun stageAndApplyKeepEmptyIdentityDistinctFromCommitOnly() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        f.sent.clear()
        f.repo.stage(ConfigGroup.DEVICE, byteArrayOf())
        f.repo.apply(ConfigGroup.GAME, byteArrayOf())
        f.repo.apply(ConfigGroup.LORA, byteArrayOf())
        assertEquals(listOf(4, 10, 0), f.sent.map { it.data.size() })
        assertEquals(listOf(false, true, true), f.sent.map { it.commit })
    }

    @Test fun rejectedApplyDoesNotReadOrOverwriteIdentity() = runTest {
        val f = Fixture(this, LinkState.Connected("A", 517))
        runCurrent()
        f.device("Keep me")
        runCurrent()
        f.sent.clear()
        f.receive(ConfigAck.newBuilder().setGroup(ConfigGroup.DEVICE)
            .setSuccess(false).setError("Rejected").build().toByteArray())
        runCurrent()
        assertTrue(f.sent.isEmpty())
        assertEquals("Keep me", DeviceConfig.parseFrom(f.repo.groupBytes.value.getValue(ConfigGroup.DEVICE)).longName)
    }
}
