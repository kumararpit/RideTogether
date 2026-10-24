package com.example.data.map.tile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the on-disk tile cache.
 *
 * Schema mirrors Google Maps' SqliteTileDiskCache:
 *   tiles(layer_id, hashed_tile_key, metadata_nonce, metadata,
 *         expiration_time, priority, data_nonce, data)
 *
 * RideTogether simplified schema:
 *   tile_cache(zoom, x, y, data, etag, cached_at_ms, expires_at_ms, priority)
 */
@Entity(
    tableName = "tile_cache",
    primaryKeys = ["zoom", "x", "y"]
)
data class TileEntity(
    @ColumnInfo(name = "zoom") val zoom: Int,
    @ColumnInfo(name = "x") val x: Int,
    @ColumnInfo(name = "y") val y: Int,
    @ColumnInfo(name = "data") val data: ByteArray,           // PNG bytes
    @ColumnInfo(name = "etag") val etag: String? = null,
    @ColumnInfo(name = "cached_at_ms") val cachedAtMs: Long = 0,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long = 0, // 0 = never expire
    @ColumnInfo(name = "priority") val priority: Long = 0,
    @ColumnInfo(name = "is_empty") val isEmpty: Boolean = false // true = confirmed missing, don't re-fetch
)
