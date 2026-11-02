package com.airtak.data.config

import com.airtak.proto.ConfigGroup
import com.airtak.proto.PowerConfig
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/** Empty POWER means all settings zero in proto3, but is a commit-only command on the device. */
internal fun powerConfigWireBytes(group: ConfigGroup, data: ByteArray): ByteArray {
    if (group != ConfigGroup.POWER || data.isNotEmpty()) return data
    val buffer = ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(buffer)
    out.writeUInt32(PowerConfig.BRIGHTNESS_PCT_FIELD_NUMBER, 0)
    out.flush()
    return buffer.toByteArray()
}
