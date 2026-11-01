package com.airtak.data.ota

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide update lifetime, including setup, transfer, verification and reconnect. */
@Singleton
class FirmwareUpdateActivity @Inject constructor() {
    private var activeUpdates = 0
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    suspend fun <T> duringUpdate(block: suspend () -> T): T {
        synchronized(this) {
            activeUpdates++
            _isActive.value = true
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                activeUpdates--
                _isActive.value = activeUpdates > 0
            }
        }
    }
}
