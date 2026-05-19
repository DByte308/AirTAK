// AirTAK Android — data/ota/ProgressReporter.kt
// Planning: §6.1 | Roadmap: 6.1 (revised)
//
// Shared progress tracking for all firmware update engines.

package com.airtak.data.ota

import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress

class ProgressReporter(
    private val totalBytes: Long,
    private val onProgress: (UpdateProgress) -> Unit,
    private val statusEveryNChunks: Int = 50,
    private val minReportIntervalMs: Long = 500L
) {
    private var bytesWritten: Long = 0
    private var chunksSinceStatus: Int = 0
    private var startTimeMs: Long = 0L
    private var lastReportTimeMs: Long = 0L

    fun start() {
        startTimeMs = System.currentTimeMillis()
        lastReportTimeMs = startTimeMs
        onProgress(UpdateProgress.Transferring(0, totalBytes, estimateEta()))
    }

    fun update(chunkSize: Int) {
        bytesWritten += chunkSize
        chunksSinceStatus++
        val now = System.currentTimeMillis()
        if (chunksSinceStatus >= statusEveryNChunks ||
            now - lastReportTimeMs >= minReportIntervalMs ||
            bytesWritten >= totalBytes) {
            onProgress(UpdateProgress.Transferring(bytesWritten, totalBytes, estimateEta()))
            chunksSinceStatus = 0
            lastReportTimeMs = now
        }
    }

    fun verifying(progress: Float) {
        onProgress(UpdateProgress.Verifying(progress))
    }

    fun getBytesWritten(): Long = bytesWritten
    fun getElapsedMs(): Long = System.currentTimeMillis() - startTimeMs

    private fun estimateEta(): Int {
        if (bytesWritten <= 0 || startTimeMs <= 0L) return 0
        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed <= 0L) return 0
        val rate = bytesWritten.toDouble() / elapsed
        val remaining = totalBytes - bytesWritten
        if (rate <= 0.0) return 0
        return ((remaining / rate) / 1000.0).toInt()
    }
}
