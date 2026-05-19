// AirTAK Android — data/ota/SlipFramer.kt
// Planning: §3.3 | Roadmap: 6.1 (revised)
//
// SLIP (Serial Line Internet Protocol) frame encoder/decoder for the
// esptool protocol used by ESP32 ROM bootloader over USB CDC-ACM.

package com.airtak.data.ota

object SlipFramer {
    const val END: Byte = 0xC0.toByte()
    const val ESC: Byte = 0xDB.toByte()
    const val ESC_END: Byte = 0xDC.toByte()
    const val ESC_ESC: Byte = 0xDD.toByte()

    fun encode(payload: ByteArray): ByteArray {
        val output = mutableListOf<Byte>()
        output.add(END)
        for (b in payload) {
            when (b) {
                END -> {
                    output.add(ESC)
                    output.add(ESC_END)
                }
                ESC -> {
                    output.add(ESC)
                    output.add(ESC_ESC)
                }
                else -> output.add(b)
            }
        }
        output.add(END)
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
                        ESC_END -> current.add(END)
                        ESC_ESC -> current.add(ESC)
                        else -> current.add(b)
                    }
                    escaped = false
                }
                b == ESC -> escaped = true
                b == END -> {
                    if (current.isNotEmpty()) {
                        packets.add(current.toByteArray())
                        current.clear()
                    }
                }
                else -> current.add(b)
            }
        }
        if (current.isNotEmpty()) {
            packets.add(current.toByteArray())
        }
        return packets
    }
}
