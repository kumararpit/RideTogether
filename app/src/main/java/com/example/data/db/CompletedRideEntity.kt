package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "completed_rides")
data class CompletedRideEntity(
    @PrimaryKey
    val id: String,
    val rideName: String,
    val startLocationName: String,
    val destinationName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val avgSpeedKmh: Double,
    val riderCount: Int,
    val completedAtMs: Long = System.currentTimeMillis(),
    val hadEmergency: Boolean = false
) {
    val formattedDistance: String
        get() = String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000.0)

    val formattedDuration: String
        get() {
            val mins = (durationSeconds / 60.0).toInt()
            return if (mins >= 60) {
                val hrs = mins / 60
                val rem = mins % 60
                if (rem > 0) "${hrs}h ${rem}m" else "${hrs}h"
            } else {
                "${mins}m"
            }
        }
}
