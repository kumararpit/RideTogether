package com.example.data.map.tile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the multi-level tile cache:
 *
 *   Memory (LRU Bitmap) → Disk (SQLite PNG + TTL) → Network (retry + backoff)
 *
 * Mirrors Google Maps' tile fetch pipeline:
 *   - DedupingUrlLoader        → TileDispatcher (dedup pending requests + priority ordering)
 *   - lru_memory_cache.h       → TileMemoryCache
 *   - SqliteTileDiskCache      → TileDiskCache (expiration_time TTL + empty tiles)
 *   - CacheStatszRecorder      → TileStats (hit/miss metrics)
 *   - EXPONENTIAL backoff      → TileLoader
 *   - nativeCancelAllPending   → TileDispatcher.cancelAll()
 *   - InsertOrUpdateEmptyTile  → TileDiskCache.putEmpty() (don't re-fetch missing tiles)
 *   - AreaConnectivitySystem   → caller-provided isConnected() + onConnectivityRestored()
 */
class TileRepository(
    private val memoryCache: TileMemoryCache,
    private val diskCache: TileDiskCache,
    private val loader: TileLoader,
    private val stats: TileStats,
    private val scope: CoroutineScope,
    private val dispatcher: TileDispatcher,
    private val isConnected: () -> Boolean = { true },
    private val onConnectivityRestored: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "TileRepository"
    }

    private val DEFAULT_TTL_MS = TimeUnit.DAYS.toMillis(7)

    /** Keys whose last network load failed — retried when connectivity is restored. */
    private val failedKeys = java.util.concurrent.ConcurrentHashMap<TileKey, Unit>()

    /**
     * Loads a tile as a Bitmap, checking memory → disk → network in order.
     * Returns null if the tile is unavailable at all levels.
     *
     * All concurrent callers for the same TileKey share one in-flight load
     * (deduplication). Camera moves should call cancelPending() to abort
     * stale loads.
     *
     * Bitmap decoding runs on Dispatchers.Default; network I/O is handled by
     * the loader's own coroutine context.
     *
     * @param priority lower numbers load first (0 = viewport centre / most urgent)
     */
    suspend fun loadBitmap(key: TileKey, priority: Int = 0): Bitmap? {
        // 1. Memory cache (L1) — fastest, no decode needed.
        val mem = memoryCache.get(key)
        if (mem != null) {
            stats.recordHit(memory = true)
            return mem
        }

        // 2. Empty tile check — skip network for tiles confirmed missing.
        if (diskCache.isEmpty(key)) {
            stats.recordEmptyTileHit()
            return null
        }

        // 3. Disk cache (L2) with expiration — decode PNG → Bitmap.
        // A disk hit after a memory miss is a "stale texture reload": the
        // bitmap was evicted from memory but persists on disk, so we reload
        // from disk instead of re-downloading from the network.
        val diskBytes = diskCache.get(key)
        if (diskBytes != null) {
            stats.recordHit(memory = false)
            val bmp = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(diskBytes, 0, diskBytes.size)
            }
            if (bmp != null) {
                stats.recordStaleTextureReload()
                memoryCache.put(key, bmp)
            }
            return bmp
        }

        // 4. Network (L3) — dedup + retry + priority ordering.
        stats.recordMiss()
        stats.recordNetworkLoad()

        val bytes: ByteArray? = dispatcher.submit(
            key = key,
            load = { loader.load(key.url(), isConnected) },
            priority = priority
        ).await()

        if (bytes != null) {
            val bmp = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (bmp != null) {
                memoryCache.put(key, bmp)
                // Persist PNG bytes to disk (best-effort, fire-and-forget).
                scope.launch {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    diskCache.put(key, baos.toByteArray())
                }
            }
            failedKeys.remove(key)
            return bmp
        }

        // 5. Network failed after all retries — cache as empty tile so we
        // never re-fetch this known-missing tile again. Also track it for
        // connectivity-recovery retry.
        stats.recordNetworkFailure()
        failedKeys[key] = Unit
        scope.launch {
            diskCache.putEmpty(key)
        }
        return null
    }

    /** Cancels all in-flight tile loads. Call on camera move / zoom change. */
    fun cancelPending() {
        Log.d(TAG, "Cancelling ${dispatcher.pendingCount()} pending + ${dispatcher.waitingCount()} queued tile loads")
        dispatcher.cancelAll()
    }

    /** Cancels loads for keys not in [visibleKeys]. Call on pan to release out-of-range tiles. */
    fun cancelKeysNotIn(visibleKeys: Set<TileKey>) {
        val toCancel = dispatcher.pendingKeys - visibleKeys
        if (toCancel.isNotEmpty()) {
            Log.d(TAG, "Cancelling ${toCancel.size} out-of-range tile loads")
            toCancel.forEach { dispatcher.cancel(it) }
        }
    }

    /** Returns true if a load for [key] is in-flight or queued. */
    fun isLoading(key: TileKey): Boolean = dispatcher.isPending(key)

    /**
     * Retries all keys whose last network load failed. Call when connectivity
     * is restored (mirrors Google Maps' AreaConnectivitySystem recovery).
     */
    fun onConnectivityRestored() {
        val keys = failedKeys.keys.toList()
        if (keys.isEmpty()) return
        Log.d(TAG, "Connectivity restored, retrying ${keys.size} failed tile loads")
        failedKeys.clear()
        for (key in keys) {
            scope.launch {
                // Clear the empty-tile marker so the retry actually hits the network
                diskCache.removeEmpty(key)
                loadBitmap(key) // fire-and-forget retry with default priority
            }
        }
    }

    /** Cache statistics (readable for logging/diagnostics). */
    val tileStats: TileStats get() = stats
}
