// MASH Android — domain/usecase/RequestPositionUseCase.kt
// Planning: §8.12, §12 | Roadmap: 2.12

package com.mash.domain.usecase

import com.mash.data.ble.BleRepository
import javax.inject.Inject

class RequestPositionUseCase @Inject constructor(
    private val bleRepository: BleRepository
) {
    suspend operator fun invoke(targetNodeId: Long): Boolean {
        val requestPb = buildPositionRequestProto(targetNodeId.toUInt32(), 0L)
        val dataPb = buildDataProto(portnum = 262L, payload = requestPb)
        return bleRepository.writeMeshTx(dataPb)
    }
}

private fun Long.toUInt32(): Long = this and 0xFFFF_FFFFL

private fun buildPositionRequestProto(targetNodeId: Long, requesterNodeId: Long): ByteArray {
    val out = ArrayList<Byte>()
    out.add(0x08); writeVarint(out, targetNodeId)
    out.add(0x10); writeVarint(out, requesterNodeId)
    return out.toByteArray()
}

private fun buildDataProto(portnum: Long, payload: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    out.add(0x08); writeVarint(out, portnum)
    out.add(0x12); writeVarint(out, payload.size.toLong()); out.addAll(payload.toList())
    return out.toByteArray()
}

private fun writeVarint(out: MutableList<Byte>, value: Long) {
    var v = value
    while (v >= 0x80L) {
        out.add(((v and 0x7FL) or 0x80L).toByte())
        v = v shr 7
    }
    out.add(v.toByte())
}
