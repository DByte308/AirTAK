package com.airtak.net

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

/**
 * One capped streaming reader for every remote body AirTAK fetches.
 *
 * Extracted so tiles and geocoding cannot drift apart. Two copies of a bounded read is how one of
 * them ends up unbounded, and the caller should not have to remember the termination rules.
 */
object CappedBody {

    sealed interface Result {
        /** Body arrived complete and within [maxBytes]. */
        data class Ok(val bytes: ByteArray) : Result {
            override fun equals(other: Any?): Boolean =
                other is Ok && other.bytes.contentEquals(bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** Body exceeded the cap, or announced a length above it. Nothing is returned. */
        data object TooLarge : Result
    }

    /**
     * Read at most [maxBytes]. A declared Content-Length above the cap is rejected before any body
     * byte is read, so an obvious oversized response costs one header lookup.
     */
    suspend fun read(response: HttpResponse, maxBytes: Long): Result {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBytes) return Result.TooLarge
        return readChannel(response.bodyAsChannel(), maxBytes)
    }

    /**
     * Termination rules, each of which was wrong at some point in this file's history:
     *
     *  - `readAvailable` returns **-1** only at a clean end of stream.
     *  - it returns **0** when no bytes are available *yet* and the channel is not closed. Treating
     *    0 as end-of-stream truncates a body; treating it as "keep going" without suspending is a
     *    busy spin that pegs a core and looks like a hang.
     *  - `awaitContent()` suspends until bytes arrive or the channel closes. In Ktor 2.3.9 it does
     *    not return a Boolean, so the close has to be re-checked afterwards rather than inferred
     *    from a return value. An idle guard backs that up so the loop terminates even if the engine
     *    keeps reporting zero without ever closing.
     */
    private const val MAX_IDLE_WAITS = 64

    suspend fun readChannel(channel: ByteReadChannel, maxBytes: Long): Result {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream(16 * 1024)
        var total = 0L
        var idle = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) return Result.Ok(out.toByteArray())
            if (read == 0) {
                if (channel.isClosedForRead) return Result.Ok(out.toByteArray())
                if (++idle > MAX_IDLE_WAITS) {
                    // A stalled producer is not a body we should keep waiting on.
                    return Result.TooLarge
                }
                channel.awaitContent()
                continue
            }
            idle = 0
            total += read
            if (total > maxBytes) return Result.TooLarge
            out.write(buffer, 0, read)
        }
    }
}
