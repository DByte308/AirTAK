// MASH Android — data/audio/AudioRepository.kt
// Planning: §12.7, §8.11 | Roadmap: 7.1 (Phase 7 only)
//
// Manages PTT TX and RX audio pipelines:
//   TX: AudioRecord → Codec2 JNI encode → BLE MESH_TX (8 frames per packet, §8.11)
//   RX: MESH_RX MASH_AUDIO packet → Codec2 JNI decode → 3-packet jitter buffer → AudioTrack
//
// Codec2 JNI wrapper is in android/app/src/main/cpp/ (Phase 7).
// RECORD_AUDIO permission must be granted before startPttTx().

package com.mash.data.audio

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepository @Inject constructor() {

    // TODO Phase 7.1: fun startPttTx(sessionId: Long) — begin encoding AudioRecord stream
    // TODO Phase 7.1: fun stopPttTx()
    // TODO Phase 7.1: fun onAudioPacket(pb: ByteArray) — decode + buffer + play
}
