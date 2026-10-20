package com.example.data.amenities

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AmenityDao {
    @Query("SELECT * FROM amenities WHERE lat >= :minLat AND lat <= :maxLat AND lon >= :minLon AND lon <= :maxLon")
    suspend fun getAmenitiesInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<AmenityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAmenities(amenities: List<AmenityEntity>)

    /**
     * Batch cache-mark read: one SELECT for a whole fetch batch instead of one
     * round-trip per cell (Phases 1–2: up to 16 sequential queries eliminated).
     */
    @Query("SELECT * FROM amenity_queries WHERE cellKey IN (:cellKeys)")
    suspend fun getQueryTimestamps(cellKeys: List<String>): List<AmenityQueryCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueryCaches(caches: List<AmenityQueryCacheEntity>)

    /**
     * One-time cleanup of pre-versioning marks. Pre-2026-10-19 keys look like
     * "14_x_y"; versioned keys embed the query version ("14_v2_x_y"), so every
     * row lacking that marker predates the current QL semantics.
     */
    @Query("DELETE FROM amenity_queries WHERE instr(cellKey, '_v') = 0")
    suspend fun deletePreV2QueryTimestamps()

    @Query("DELETE FROM amenities")
    suspend fun clearAll()
}
