package com.example.data.model

import com.google.firebase.Timestamp

enum class ConnectionStatus {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    LOCATION_UNAVAILABLE,
    LOCATION_STALE
}

/**
 * Versioned location update message payload adhering to real-time synchronization contract.
 */
data class LocationUpdateMessage(
    val version: Int = 1,
    val type: String = "LOCATION_UPDATE",
    val rideId: String,
    val userId: String,
    val userName: String,
    val motorcycleModel: String = "",
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val speedMps: Double = 0.0,
    val bearingDegrees: Float = 0f,
    val recordedAtMs: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean {
        if (latitude !in -90.0..90.0) return false
        if (longitude !in -180.0..180.0) return false
        if (rideId.isBlank() || userId.isBlank()) return false
        return true
    }
}
