package com.example.data.map.tile

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

/**
 * Network tile loader with retry + exponential backoff.
 *
 * Mirrors Google Maps' retry infrastructure:
 *   - `download_next_retry` — scheduled retry timestamp
 *   - `EXPONENTIAL` backoff pattern
 *   - `DoRetry` entry point
 *   - `"retries. Will not retry."` — max retry enforcement
 *   - Connectivity gating (caller passes isConnected)
 *
 * Uses OkHttp (already a project dependency) with a suspending coroutine bridge.
 */
open class TileLoader(
    private val client: okhttp3.OkHttpClient,
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30000
) {

    companion object {
        private const val TAG = "TileLoader"
    }

    /**
     * Loads tile bytes from the network with exponential backoff.
     *
     * Retries on IOException or non-2xx HTTP response. Each retry doubles the
     * delay (baseDelay * 2^attempt), clamped to maxDelayMs.
     *
     * Returns null when all retries are exhausted.
     */
    open suspend fun load(url: String, isConnected: () -> Boolean): ByteArray? {
        var attempt = 0
        var delayMs = baseDelayMs

        while (true) {
            // Connectivity gate: if offline, wait briefly and re-check before
            // burning a retry.
            if (!isConnected()) {
                Log.d(TAG, "Offline, waiting for connectivity before tile load (attempt $attempt)")
                val waited = waitForConnectivity(isConnected, delayMs.coerceAtMost(5000))
                if (!waited) {
                    Log.w(TAG, "Offline timeout, giving up on $url")
                    return null
                }
            }

            try {
                return fetchOnce(url)
            } catch (e: IOException) {
                Log.w(TAG, "Tile load IO error on $url (attempt $attempt): ${e.message}")
                if (!retry(attempt++, delayMs)) return null
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            } catch (e: HttpException) {
                Log.w(TAG, "Tile load HTTP ${e.code} on $url (attempt $attempt)")
                if (!retry(attempt++, delayMs)) return null
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
        }
    }

    private suspend fun fetchOnce(url: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RideTogether-Android-Personal-App/1.0")
                .build()

            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body
                    if (response.isSuccessful && body != null) {
                        val bytes = body.bytes()
                        body.close()
                        if (cont.isActive) cont.resume(bytes, null)
                    } else {
                        body?.close()
                        if (cont.isActive) cont.resumeWithException(HttpException(response.code))
                    }
                }
            })
        }

    /** Returns true if a retry should be attempted (sleeps backoff first). */
    private suspend fun retry(attempt: Int, delayMs: Long): Boolean {
        if (attempt >= maxRetries) {
            Log.w(TAG, "Max retries ($maxRetries) reached, will not retry")
            return false
        }
        Log.d(TAG, "Retrying tile load in ${delayMs}ms (attempt $attempt / $maxRetries)")
        withContext(Dispatchers.Default) {
            kotlinx.coroutines.delay(delayMs)
        }
        return true
    }

    /** Suspends until connectivity returns or timeoutMs elapses. Returns true if connected. */
    private suspend fun waitForConnectivity(
        isConnected: () -> Boolean,
        timeoutMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isConnected()) return true
            kotlinx.coroutines.delay(500)
        }
        return isConnected()
    }
}

class HttpException(val code: Int) : Exception("HTTP $code")
