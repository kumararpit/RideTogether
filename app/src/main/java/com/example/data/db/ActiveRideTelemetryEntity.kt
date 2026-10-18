package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_ride_telemetry")
data class ActiveRideTelemetryEntity(
    @PrimaryKey
    val rideId: String,
    val startedAtMs: Long,
    val actualRecordedDistanceMeters: Double,
    val lastGpsLat: Double? = null,
    val lastGpsLng: Double? = null,
    val lastGpsTimestampMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)
