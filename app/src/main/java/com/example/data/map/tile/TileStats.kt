package com.example.data.map.tile

/**
 * Cache statistics — mirrors Google Maps' `CacheStatszRecorder` and
 * `DiskCacheStatsProto`. Tracks hit/miss/eviction across all cache levels so the
 * product team can tune prefetch radius, TTL, and memory cap.
 */
class TileStats {
    private var hits = 0L
    private var misses = 0L
    private var memoryHits = 0L
    private var diskHits = 0L
    private var networkLoads = 0L
    private var networkFailures = 0L
    private var evictions = 0L
    private var placeholderRenders = 0L
    private var duplicateRequestsSuppressed = 0L
    private var emptyTileHits = 0L
    private var staleTextureReloads = 0L

    fun recordHit(memory: Boolean) {
        hits++
        if (memory) memoryHits++ else diskHits++
    }

    fun recordMiss() {
        misses++
    }

    fun recordNetworkLoad() {
        networkLoads++
    }

    fun recordNetworkFailure() {
        networkFailures++
    }

    fun recordEviction() {
        evictions++
    }

    fun recordPlaceholderRender() {
        placeholderRenders++
    }

    fun recordDuplicateSuppressed() {
        duplicateRequestsSuppressed++
    }

    fun recordEmptyTileHit() {
        emptyTileHits++
    }

    fun recordStaleTextureReload() {
        staleTextureReloads++
    }

    val hitRate: Float
        get() = if (hits + misses == 0L) 1f else (hits / (hits + misses).toDouble()).toFloat()

    val memoryHitRate: Float
        get() = if (hits == 0L) 0f else (memoryHits / hits.toDouble()).toFloat()

    override fun toString(): String {
        return "TileStats{hits=$hits, misses=$misses, hitRate=%.3f, memoryHits=$memoryHits, " +
            "diskHits=$diskHits, networkLoads=$networkLoads, networkFailures=$networkFailures, " +
            "evictions=$evictions, placeholders=$placeholderRenders, dupSuppressed=$duplicateRequestsSuppressed, " +
            "emptyTileHits=$emptyTileHits, staleTextureReloads=$staleTextureReloads}".format(
            hitRate
        )
    }
}
