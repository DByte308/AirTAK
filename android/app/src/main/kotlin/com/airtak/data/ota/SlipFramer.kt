// AirTAK Android — data/ota/SlipFramer.kt
// Planning: §3.3 | Roadmap: 6.1 (revised)
//
// SLIP (Serial Line Internet Protocol) frame encoder/decoder for the
// esptool protocol used by ESP32 ROM bootloader over USB CDC-ACM.

package com.airtak.data.ota

import com.airtak.config.constants.SLIP_END
import com.airtak.config.constants.SLIP_ESC
import com.airtak.config.constants.SLIP_ESC_END
import com.airtak.config.constants.SLIP_ESC_ESC

object SlipFramer {

    fun encode(payload: ByteArray): ByteArray {
        val output = mutableListOf<Byte>()
        output.add(SLIP_END.toByte())
        for (b in payload) {
            when (b) {
                SLIP_END.toByte() -> {
                    output.add(SLIP_ESC.toByte())
                    output.add(SLIP_ESC_END.toByte())
                }
                SLIP_ESC.toByte() -> {
                    output.add(SLIP_ESC.toByte())
                    output.add(SLIP_ESC_ESC.toByte())
                }
                else -> output.add(b)
            }
        }
        output.add(SLIP_END.toByte())
        return output.toByteArray()
    }

    fun decode(buffer: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val current = mutableListOf<Byte>()
        var escaped = false

        for (b in buffer) {
            when {
                escaped -> {
                    when (b) {
                        SLIP_ESC_END.toByte() -> current.add(SLIP_END.toByte())
                        SLIP_ESC_ESC.toByte() -> current.add(SLIP_ESC.toByte())
                        else -> current.add(b)
                    }
                    escaped = false
                }
                b == SLIP_ESC.toByte() -> escaped = true
                b == SLIP_END.toByte() -> {
                    if (current.isNotEmpty()) {
                        packets.add(current.toByteArray())
                        current.clear()
                    }
                }
                else -> current.add(b)
            }
        }
        // Only return COMPLETE frames; partial frame stays in the buffer.
        return packets
    }
}
