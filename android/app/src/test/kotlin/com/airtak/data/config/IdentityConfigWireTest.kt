package com.airtak.data.config

import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceConfig
import com.airtak.proto.GameConfig
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentityConfigWireTest {
    private fun stringFields(bytes: ByteArray): Map<Int, String> {
        val input = CodedInputStream.newInstance(bytes)
        val values = mutableMapOf<Int, String>()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if ((tag and 7) == 2) values[tag ushr 3] = input.readString()
            else input.skipField(tag)
        }
        return values
    }

    @Test fun emptyDeviceConfigHasBothExplicitEmptyStrings() {
        val encoded = identityConfigWireBytes(ConfigGroup.DEVICE, byteArrayOf())
        assertArrayEquals(byteArrayOf(10, 0, 18, 0), encoded)
        assertEquals(mapOf(1 to "", 2 to ""), stringFields(encoded))
        assertEquals(DeviceConfig.getDefaultInstance(), DeviceConfig.parseFrom(encoded))
    }

    @Test fun emptyGameConfigHasAllFiveExplicitEmptyStrings() {
        val encoded = identityConfigWireBytes(ConfigGroup.GAME, byteArrayOf())
        assertEquals((1..5).associateWith { "" }, stringFields(encoded))
        assertEquals(GameConfig.getDefaultInstance(), GameConfig.parseFrom(encoded))
    }

    @Test fun gameReplacementKeepsNumericAndBooleanFieldsAndExistingStrings() {
        val config = GameConfig.newBuilder().setCallsign("CALL").setFireteamId("Alpha")
            .setPlayerColorRgb(0x2196F3).setHitButtonEnabled(true).build()
        val original = config.toByteArray()
        val encoded = identityConfigWireBytes(ConfigGroup.GAME, original)
        assertEquals(config, GameConfig.parseFrom(encoded))
        assertArrayEquals(original, encoded.copyOfRange(0, original.size))
        assertEquals(mapOf(1 to "CALL", 2 to "Alpha", 3 to "", 4 to "", 5 to ""), stringFields(encoded))
    }

    @Test fun nonemptyDeviceNamesRetainOriginalWireBytes() {
        val original = DeviceConfig.newBuilder().setLongName("Long").setShortName("SHRT").build().toByteArray()
        assertArrayEquals(original, identityConfigWireBytes(ConfigGroup.DEVICE, original))
    }

    @Test fun nonemptyGameStringsRetainOriginalWireBytes() {
        val original = GameConfig.newBuilder().setCallsign("CALL").setFireteamId("F")
            .setSquadId("S").setPlatoonId("P").setCompanyId("C").build().toByteArray()
        assertArrayEquals(original, identityConfigWireBytes(ConfigGroup.GAME, original))
    }

    @Test fun unknownWireFieldsSurviveVerbatim() {
        val buffer = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(buffer)
        output.writeString(100, "future setting")
        output.flush()
        val original = buffer.toByteArray()
        val encoded = identityConfigWireBytes(ConfigGroup.DEVICE, original)
        assertArrayEquals(original, encoded.copyOfRange(0, original.size))
        assertEquals("future setting", stringFields(encoded)[100])
        assertTrue(stringFields(encoded).keys.containsAll(listOf(1, 2)))
    }

    @Test fun nonIdentityGroupsRemainUntouchedIncludingEmptyData() {
        for (group in listOf(ConfigGroup.LORA, ConfigGroup.CHANNELS, ConfigGroup.POWER)) {
            assertArrayEquals(byteArrayOf(), identityConfigWireBytes(group, byteArrayOf()))
            val data = byteArrayOf(8, 1)
            assertArrayEquals(data, identityConfigWireBytes(group, data))
        }
    }

    @Test fun malformedIdentityDataIsLeftForDeviceValidation() {
        val invalid = byteArrayOf(0x80.toByte())
        assertArrayEquals(invalid, identityConfigWireBytes(ConfigGroup.DEVICE, invalid))
    }
}
