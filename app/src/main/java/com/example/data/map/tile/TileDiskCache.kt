package com.example.data.map.tile

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.db.RideDatabase

/**
 * L2 on-disk tile cache backed by RideDatabase/SQLite.
 *
 * Schema mirrors Google Maps' `SqliteTileDiskCache`:
 *   tiles(layer_id, hashed_tile_key, metadata_nonce, metadata,
 *         expiration_time, priority, data_nonce, data)
 *
 * Entries carry an absolute expiration (expiresAtMs). Expired entries are
 * purged lazily via deleteExpired() and on read. A default TTL of 7 days is
 * applied to newly cached tiles.
 */
class TileDiskCache(private val dao: TileDao) {

    private val DEFAULT_TTL_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(7)

    /** Reads a tile from disk, or null if absent/expired/empty. */
    suspend fun get(key: TileKey, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        val entry = dao.findTile(key.zoom, key.x, key.y) ?: return null
        if (entry.isEmpty) return null // confirmed missing tile — don't return data
        if (entry.expiresAtMs > 0 && nowMs > entry.expiresAtMs) {
            return null
        }
        return entry.data
    }

    /** True if this tile is cached as confirmed-missing (empty) and not yet expired. */
    suspend fun isEmpty(key: TileKey, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = dao.findEmptyTile(key.zoom, key.x, key.y) ?: return false
        return entry.expiresAtMs == 0L || nowMs <= entry.expiresAtMs
    }

    /** Writes a tile to disk with the default TTL. */
    suspend fun put(key: TileKey, data: ByteArray, etag: String? = null) {
        val nowMs = System.currentTimeMillis()
        dao.insert(
            TileEntity(
                zoom = key.zoom,
                x = key.x,
                y = key.y,
                data = data,
                etag = etag,
                cachedAtMs = nowMs,
                expiresAtMs = nowMs + DEFAULT_TTL_MS,
                priority = 0,
                isEmpty = false
            )
        )
    }

    /** Marks a tile as confirmed-missing (empty) so it is never re-fetched from network. */
    suspend fun putEmpty(key: TileKey, ttlMs: Long = DEFAULT_TTL_MS) {
        val nowMs = System.currentTimeMillis()
        dao.insert(
            TileEntity(
                zoom = key.zoom,
                x = key.x,
                y = key.y,
                data = ByteArray(0),
                etag = null,
                cachedAtMs = nowMs,
                expiresAtMs = nowMs + ttlMs,
                priority = 0,
                isEmpty = true
            )
        )
    }

    /** Removes the confirmed-missing (empty) marker for [key] so it can be re-fetched. */
    suspend fun removeEmpty(key: TileKey) {
        dao.deleteEmpty(key.zoom, key.x, key.y)
    }

    /** Purges all expired entries. Returns the number of rows deleted. */
    suspend fun expire(nowMs: Long = System.currentTimeMillis()): Int {
        return dao.deleteExpired(nowMs)
    }

    suspend fun count(): Int = dao.count()

    suspend fun totalBytes(): Long = dao.totalBytes() ?: 0
}

fun createTileDiskCache(context: Context): TileDiskCache {
    val db = RideDatabase.getInstance(context)
    return TileDiskCache(db.tileDao())
}
