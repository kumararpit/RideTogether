package com.example.data.amenities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Failure classification + bounded retry for Overpass queries (Phase 5).
 *
 * Measured classes against overpass-api.de (2026-10-19, host + device):
 *  - connect refusal / EOF / stream resets (IOException) — TRANSIENT (infrastructure
 *    sheds load cyclically; the same query succeeds minutes later).
 *  - HTTP 429 (rate limit) and 408 — TRANSIENT.
 *  - HTTP 500/502/503/504 gateway class — TRANSIENT (observed in alternating windows).
 *  - HTTP 4xx "permanent" (400 malformed QL, 414 oversized request, 406 UA/accept,
 *    410/501 embedded as 4xx) — PERMANENT for this exact request; retrying burns
 *    public-API quota and adds latency for nothing.
 *
 * Retries are bounded: exactly one cool-down retry per query, at most. No loop.
 * Offline detection (airplane mode) is honoured via [isProbablyOffline]: the
 * repository supplies a ConnectivityManager-backed check so a dead radio doesn't
 * burn the retry (or queue at all).
 */
object OverpassResilience {

    /** Cool-down before the single retry; short so viewport-driven fetches stay responsive. */
    const val RETRY_COOLDOWN_MS = 3000L

    /** Random jitter added to the cool-down (±25%) so retry storms don't march in step. */
    const val RETRY_JITTER_MAX_FRACTION = 0.25

    /** HTTP statuses worth exactly one bounded retry. */
    val transientStatusCodes = setOf(408, 429, 500, 502, 503, 504)

    @Throws(CancellationException::class, IOException::class)
    suspend fun <T> queryWithOneRetry(
        isProbablyOffline: Boolean = false,
        randomProvider: kotlin.random.Random = kotlin.random.Random.Default,
        block: suspend () -> T
    ): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e // never swallow or retry coroutine cancellation
        } catch (e: Exception) {
            if (isProbablyOffline || !isTransientFailure(e)) throw e
            delay(retryDelayMs(randomProvider))
            return block()
        }
    }

    fun isTransientFailure(e: Exception): Boolean = when (e) {
        is CancellationException -> throw e
        is IOException -> true // connect refused / reset / EOF / TLS hiccups
        is retrofit2.HttpException -> e.code() in transientStatusCodes
        else -> false // Moshi parse errors, domain errors: not fixed by waiting
    }

    fun retryDelayMs(randomProvider: kotlin.random.Random = kotlin.random.Random.Default): Long {
        val base = RETRY_COOLDOWN_MS.toDouble()
        val jitter = randomProvider.nextDouble(-RETRY_JITTER_MAX_FRACTION, RETRY_JITTER_MAX_FRACTION)
        return (base * (1.0 + jitter)).toLong()
    }
}
