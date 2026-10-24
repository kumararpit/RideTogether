package com.example.data.map.tile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tile: TileEntity)

    @Query("SELECT * FROM tile_cache WHERE zoom = :zoom AND x = :x AND y = :y")
    suspend fun findTile(zoom: Int, x: Int, y: Int): TileEntity?

    @Query("SELECT * FROM tile_cache WHERE is_empty = 1 AND zoom = :zoom AND x = :x AND y = :y")
    suspend fun findEmptyTile(zoom: Int, x: Int, y: Int): TileEntity?

    @Query("DELETE FROM tile_cache WHERE is_empty = 1 AND zoom = :zoom AND x = :x AND y = :y")
    suspend fun deleteEmpty(zoom: Int, x: Int, y: Int)

    @Query("SELECT * FROM tile_cache WHERE expires_at_ms = 0 OR expires_at_ms > :nowMs")
    suspend fun loadUnexpired(nowMs: Long): List<TileEntity>

    @Query("DELETE FROM tile_cache WHERE expires_at_ms > 0 AND expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM tile_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM tile_cache")
    suspend fun count(): Int

    @Query("SELECT SUM(LENGTH(data)) FROM tile_cache")
    suspend fun totalBytes(): Long?
}
