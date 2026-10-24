package com.example.data.map.tile

import android.graphics.Bitmap
import java.util.LinkedHashMap

/**
 * L1 in-memory tile cache: access-ordered LinkedHashMap LRU.
 *
 * Mirrors Google Maps' `lru_memory_cache.h` — entries are evicted from the
 * least-recently-accessed end when the cap is hit. Accessing an entry promotes
 * it to the most-recently-used position.
 *
 * When [stats] is provided, every eviction (LRU cap, manual remove, or clear)
 * is recorded via [TileStats.recordEviction].
 */
class TileMemoryCache(
    private val maxEntries: Int = 256,
    private val stats: TileStats? = null
) {

    private val cache = LinkedHashMap<TileKey, Bitmap>(0, 0.75f, true)

    /** Returns the cached bitmap, or null if absent. Access promotes the entry. */
    fun get(key: TileKey): Bitmap? = synchronized(cache) { cache[key] }

    /** Puts a bitmap into the cache, evicting LRU entries if over cap. */
    fun put(key: TileKey, bitmap: Bitmap) {
        synchronized(cache) {
            cache[key] = bitmap
            while (cache.size > maxEntries) {
                val oldest = cache.entries.iterator().next()
                cache.remove(oldest.key)
                oldest.value.recycle()
                stats?.recordEviction()
            }
        }
    }

    /** Removes an entry from the cache (e.g. on cancellation/invalidation). */
    fun remove(key: TileKey) {
        synchronized(cache) {
            cache.remove(key)?.let {
                it.recycle()
                stats?.recordEviction()
            }
        }
    }

    /** Clears the entire cache, recycling all bitmaps. */
    fun clear() {
        synchronized(cache) {
            val count = cache.size
            cache.values.forEach { it.recycle() }
            cache.clear()
            if (count > 0) {
                stats?.recordEviction()
            }
        }
    }

    val size: Int
        get() = synchronized(cache) { cache.size }

    val maxSize: Int
        get() = maxEntries
}
