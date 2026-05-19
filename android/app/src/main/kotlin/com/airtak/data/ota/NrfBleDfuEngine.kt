// AirTAK Android — data/ota/NrfBleDfuEngine.kt
// Planning: §3.2 / §6.1 | Roadmap: 6.1 (revised)
//
// nRF52 firmware updates over BLE using Nordic's Secure DFU. The wire protocol
// is implemented by `no.nordicsemi.android:dfu`; this engine only sequences
// the high-level flow:
//
//   1. Send an AirTAK ADMIN OTA_ENTER request, which the firmware translates
//      into a Bluefruit `enterOTADfu()` (writes GPREGRET + soft-resets into
//      the Adafruit/Nordic bootloader, which re-advertises with the SoftDevice
//      DFU service 0xFE59 on the same MAC).
//   2. Tell BleRepository to stop auto-reconnecting (the bootloader doesn't
//      expose our AirTAK service, so reconnect would thrash) and disconnect.
//   3. Persist the user-selected firmware bytes to a cache file (the library
//      accepts both file paths and content Uris; a cache file is more robust
//      against SAF permission expiry mid-transfer).
//   4. Hand off to `DfuServiceInitiator` — it handles the bootloader
//      handshake, init packet upload, packet-receipt-notification flow,
//      chunk transfer, CRC validation, and reconnect into the application.
//   5. Restore BleRepository auto-reconnect so the new application image
//      reconnects normally.
//
// The firmware bytes are required to be a `.zip` distribution produced by
// `adafruit-nrfutil pkg generate` (or equivalent). A raw `.bin`/`.hex` will
// be rejected by the Secure DFU bootloader because the signed init packet
// is missing.

package com.airtak.data.ota

import android.content.Context
import com.airtak.data.ble.BleRepository
import com.airtak.data.ota.FirmwareUpdateManager.DeviceInfo
import com.airtak.data.ota.FirmwareUpdateManager.UpdateProgress
import com.airtak.proto.AdminCommand
import com.airtak.proto.AdminRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import java.io.File
import java.io.FileOutputStream

class NrfBleDfuEngine(
    private val context: Context,
    private val ble: BleRepository,
    private val deviceAddress: String,
    private val initialDeviceInfo: DeviceInfo,
) : FirmwareUpdateManager.FirmwareUpdateEngine {

    companion object {
        /** Time the Adafruit nRF52 bootloader needs from reboot to advertising. */
        private const val BOOTLOADER_WAKE_MS = 2_500L
        /** Cap on the entire library-managed DFU run. */
        private const val DFU_TIMEOUT_MS = 5L * 60L * 1_000L
    }

    override suspend fun initialize(): Result<DeviceInfo> = Result.success(initialDeviceInfo)

    override suspend fun transfer(
        firmware: ByteArray,
        onProgress: (UpdateProgress) -> Unit
    ): Result<Unit> {
        // ---- 1. Trigger bootloader entry on the device ----------------------
        onProgress(UpdateProgress.Initializing("Requesting bootloader entry..."))
        // DFU_ENTER on nRF52 maps to Bluefruit `enterOTADfu()` (sets GPREGRET
        // to 0xA8 and soft-resets into the Nordic SoftDevice bootloader).
        // OTA_ENTER is reserved for the ESP32 in-app OTA path and would be
        // a no-op here.
        val adminReq = AdminRequest.newBuilder()
            .setCommand(AdminCommand.DFU_ENTER)
            .build()
            .toByteArray()
        ble.send(BleRepository.OutboundChannel.Admin, adminReq)

        // ---- 2. Take the radio away from BleRepository ----------------------
        // The device will reset and come back up advertising Nordic Secure
        // DFU (UUID 0xFE59). Our normal reconnect backoff would try to
        // rediscover the AirTAK service and fail in a loop, racing with the
        // library. Pause it; we re-enable in finalize/cleanup.
        ble.pauseAutoReconnect(true)
        // Give the device time to actually reboot into the bootloader and
        // start advertising. Skipping this race-conditions the library's scan.
        delay(BOOTLOADER_WAKE_MS)

        // ---- 3. Persist firmware bytes to a cache zip -----------------------
        onProgress(UpdateProgress.Initializing("Preparing DFU package..."))
        val pkgFile = try {
            writeCachePackage(firmware)
        } catch (e: Exception) {
            ble.pauseAutoReconnect(false)
            return Result.failure(Exception("Failed to stage DFU package: ${e.message}", e))
        }

        // ---- 4. Run the Nordic DFU library ---------------------------------
        onProgress(UpdateProgress.Initializing("Connecting to bootloader..."))
        val outcome = CompletableDeferred<Result<Unit>>()
        val listener = object : DfuProgressListenerAdapter() {
            override fun onDeviceConnecting(deviceAddress: String) {
                onProgress(UpdateProgress.Initializing("Connecting to bootloader..."))
            }
            override fun onEnablingDfuMode(deviceAddress: String) {
                onProgress(UpdateProgress.Initializing("Enabling DFU mode..."))
            }
            override fun onDfuProcessStarted(deviceAddress: String) {
                onProgress(UpdateProgress.Initializing("Uploading init packet..."))
            }
            override fun onProgressChanged(
                deviceAddress: String,
                percent: Int,
                speed: Float,
                avgSpeed: Float,
                currentPart: Int,
                partsTotal: Int,
            ) {
                // The library reports percent only; synthesize byte counts so
                // the existing progress UI keeps working.
                val total = firmware.size.toLong()
                val written = total * percent / 100
                val eta = if (avgSpeed > 0f) ((total - written) / avgSpeed).toInt() else 0
                onProgress(UpdateProgress.Transferring(written, total, eta))
            }
            override fun onFirmwareValidating(deviceAddress: String) {
                onProgress(UpdateProgress.Verifying(progress = 1f))
            }
            override fun onDfuCompleted(deviceAddress: String) {
                outcome.complete(Result.success(Unit))
            }
            override fun onDfuAborted(deviceAddress: String) {
                outcome.complete(Result.failure(Exception("DFU aborted by user")))
            }
            override fun onError(
                deviceAddress: String,
                error: Int,
                errorType: Int,
                message: String?,
            ) {
                outcome.complete(Result.failure(Exception(
                    "Nordic DFU error type=$errorType code=$error: ${message ?: "<no message>"}"
                )))
            }
        }

        DfuServiceListenerHelper.registerProgressListener(context, listener)
        try {
            DfuServiceInitiator(deviceAddress)
                .setDeviceName(initialDeviceInfo.boardId)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(10)
                .setForeground(true)
                .setKeepBond(true)
                // Restart-then-DFU mode is unnecessary because we triggered
                // bootloader entry ourselves via AdminCommand OTA_ENTER.
                .setForceDfu(false)
                .setZip(android.net.Uri.fromFile(pkgFile))
                .start(context, AirtakDfuService::class.java)

            val res = withTimeoutOrNull(DFU_TIMEOUT_MS) { outcome.await() }
                ?: Result.failure(Exception("DFU timed out after ${DFU_TIMEOUT_MS / 1_000}s"))

            return res
        } finally {
            DfuServiceListenerHelper.unregisterProgressListener(context, listener)
            // Always clean up the cache file — it can be tens of MB.
            runCatching { pkgFile.delete() }
        }
    }

    override suspend fun finalize(): Result<Unit> {
        // The library has already rebooted the device into the new
        // application by the time transfer() returns; just resume our
        // normal reconnect loop so the rest of the app comes back online.
        ble.pauseAutoReconnect(false)
        ble.reconnectBonded()
        return Result.success(Unit)
    }

    override suspend fun waitForReconnect(timeoutMs: Long): Result<String> {
        // We don't have a clean way to read the new firmware version until
        // the AirTAK service exposes it on DEVICE_STATUS again. The
        // FirmwareUpdateManager's reconnect step is best-effort; report
        // unknown so the UI moves to Done.
        return Result.success("unknown")
    }

    /** Stage a `.zip` distribution into the app cache so the library reads
     *  from a stable path with file:// scheme rather than a SAF content://
     *  URI whose permission lifetime is tied to the caller activity. */
    private fun writeCachePackage(firmware: ByteArray): File {
        val dir = File(context.cacheDir, "dfu").apply { mkdirs() }
        val out = File(dir, "airtak_dfu_${System.currentTimeMillis()}.zip")
        FileOutputStream(out).use { it.write(firmware) }
        return out
    }
}
