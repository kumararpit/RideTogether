package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentPlaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: RecentPlaceEntity)

    @Query("SELECT * FROM recent_places WHERE id = :id")
    suspend fun get(id: String): RecentPlaceEntity?

    @Query("SELECT * FROM recent_places ORDER BY lastUsedAtMs DESC LIMIT :limit")
    suspend fun recentPlaces(limit: Int): List<RecentPlaceEntity>

    @Query(
        "DELETE FROM recent_places WHERE id NOT IN " +
            "(SELECT id FROM recent_places ORDER BY lastUsedAtMs DESC LIMIT :bound)"
    )
    suspend fun pruneToBound(bound: Int)
}
