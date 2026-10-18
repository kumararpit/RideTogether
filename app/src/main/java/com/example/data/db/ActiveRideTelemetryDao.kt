package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ActiveRideTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(telemetry: ActiveRideTelemetryEntity)

    @Query("SELECT * FROM active_ride_telemetry WHERE rideId = :rideId LIMIT 1")
    suspend fun getTelemetry(rideId: String): ActiveRideTelemetryEntity?

    @Query("DELETE FROM active_ride_telemetry WHERE rideId = :rideId")
    suspend fun deleteTelemetry(rideId: String)

    @Query("DELETE FROM active_ride_telemetry")
    suspend fun clearAll()
}
