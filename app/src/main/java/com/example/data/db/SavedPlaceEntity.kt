package com.example.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A place the rider saved for later.
 *
 * The saved places are **local to the device**: they are the rider's own
 * shortlist (a good café, a reliable workshop) and are not shared with the
 * group, so they do not belong in the ride document.
 */
@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val osmId: Long,
    val name: String?,
    val type: String,
    val lat: Double,
    val lon: Double,
    val savedAtMs: Long
)

@Dao
interface SavedPlaceDao {

    @Query("SELECT * FROM saved_places ORDER BY savedAtMs DESC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_places WHERE osmId = :osmId)")
    fun isSaved(osmId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE osmId = :osmId")
    suspend fun delete(osmId: Long)
}
