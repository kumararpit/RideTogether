package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ActiveRideReferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(reference: ActiveRideReferenceEntity)

    @Query("SELECT * FROM active_ride_reference ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getReference(): ActiveRideReferenceEntity?

    @Query("DELETE FROM active_ride_reference WHERE rideId = :rideId")
    suspend fun deleteReference(rideId: String)

    @Query("DELETE FROM active_ride_reference")
    suspend fun clearAll()
}
