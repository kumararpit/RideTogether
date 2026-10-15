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

    @Query("SELECT timestampMs FROM amenity_queries WHERE cellKey = :cellKey")
    suspend fun getQueryTimestamp(cellKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueryCache(cache: AmenityQueryCacheEntity)
    
    @Query("DELETE FROM amenities")
    suspend fun clearAll()
}
