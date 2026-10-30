package com.airtak.data.config

import com.airtak.proto.ConfigGroup
import com.airtak.proto.DeviceConfig
import com.airtak.proto.GameConfig
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * Explicit empty identity strings distinguish replacement from the firmware's
 * zero-byte "commit staged data" operation, and absence from factory defaults.
 * Append to the original wire bytes: numeric/bool and unknown fields are kept
 * verbatim. Other config groups retain their existing wire representation.
 */
internal fun identityConfigWireBytes(group: ConfigGroup, data: ByteArray): ByteArray {
    val strings = runCatching {
        when (group) {
            ConfigGroup.DEVICE -> DeviceConfig.parseFrom(data).let {
                listOf(it.longName, it.shortName)
            }
            ConfigGroup.GAME -> GameConfig.parseFrom(data).let {
                listOf(it.callsign, it.fireteamId, it.squadId, it.platoonId, it.companyId)
            }
            else -> return data
        }
    }.getOrElse { return data } // Invalid input is still rejected by the device.
    if (strings.none { it.isEmpty() }) return data
    val buffer = ByteArrayOutputStream()
    buffer.write(data)
    val out = CodedOutputStream.newInstance(buffer)
    strings.forEachIndexed { index, value ->
        if (value.isEmpty()) out.writeString(index + 1, "")
    }
    out.flush()
    return buffer.toByteArray()
}
