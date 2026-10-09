package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompletedRideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRide(ride: CompletedRideEntity)

    @Query("SELECT * FROM completed_rides ORDER BY completedAtMs DESC")
    fun getAllCompletedRides(): Flow<List<CompletedRideEntity>>

    @Query("SELECT * FROM completed_rides WHERE id = :id LIMIT 1")
    suspend fun getRideById(id: String): CompletedRideEntity?

    @Query("SELECT COUNT(*) FROM completed_rides")
    suspend fun getRideCount(): Int
}
