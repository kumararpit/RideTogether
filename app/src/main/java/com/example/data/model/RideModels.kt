package com.example.data.model

import java.util.UUID

enum class RideStatus {
    CREATED,
    LOBBY,
    ACTIVE,
    COMPLETED,
    CANCELLED
}

enum class MemberStatus {
    RIDING,
    STOPPED,
    EMERGENCY,
    OFFLINE
}

enum class RideRole {
    LEADER,
    MEMBER
}

enum class QuickMessageType(val label: String, val icon: String) {
    IM_STOPPING("I'm stopping", "🛑"),
    WAIT_FOR_ME("Wait for me", "⏳"),
    SLOW_DOWN("Slow down", "⚠️"),
    IM_OKAY("I'm okay", "👍"),
    FUEL_STOP("Fuel stop", "⛽"),
    FOOD_STOP("Food stop", "🍔"),
    EMERGENCY("Emergency", "🚨")
}

data class RiderMember(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val motorcycleModel: String = "",
    val avatarColorHex: Long = 0xFFFF9800,
    val role: RideRole = RideRole.MEMBER,
    val status: MemberStatus = MemberStatus.RIDING,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    val location: LatLng,
    val speedKmh: Double = 0.0,
    val headingDeg: Float = 0f,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val stoppedDurationSec: Long = 0,
    val isCurrentUser: Boolean = false,
    val batteryPct: Int = 85
) {
    fun isStale(currentMs: Long = System.currentTimeMillis(), staleThresholdMs: Long = 25000L): Boolean {
        return (currentMs - lastUpdatedMs) > staleThresholdMs
    }

    fun getFormattedDistanceTo(other: LatLng): String {
        val distKm = location.distanceTo(other)
        return if (distKm < 1.0) {
            "${(distKm * 1000).toInt()} m"
        } else {
            String.format("%.1f km", distKm)
        }
    }

    fun getRelativePositionDescription(userLoc: LatLng, userHeading: Float): String {
        if (isCurrentUser) return "You"
        val distKm = location.distanceTo(userLoc)
        val formattedDist = if (distKm < 1.0) "${(distKm * 1000).toInt()} m" else String.format("%.1f km", distKm)
        val bearing = userLoc.bearingTo(location)
        val diff = (bearing - userHeading + 360) % 360
        return when {
            diff in 315.0..360.0 || diff in 0.0..45.0 -> "$formattedDist ahead"
            diff in 135.0..225.0 -> "$formattedDist behind"
            diff in 45.0..135.0 -> "$formattedDist to right"
            else -> "$formattedDist to left"
        }
    }
}

data class QuickMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val type: QuickMessageType,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SosEvent(
    val id: String = UUID.randomUUID().toString(),
    val riderId: String,
    val riderName: String,
    val location: LatLng,
    val timestampMs: Long = System.currentTimeMillis(),
    val isResolved: Boolean = false
)

data class NavigationStep(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: String = "turn",
    val modifier: String = "straight"
) {
    val formattedDistance: String
        get() = if (distanceMeters >= 1000) {
            String.format("%.1f km", distanceMeters / 1000.0)
        } else {
            "${kotlin.math.round(distanceMeters).toInt()} m"
        }
}

data class RouteResult(
    val coordinates: List<LatLng>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val steps: List<NavigationStep>
) {
    val formattedDistance: String
        get() = if (totalDistanceMeters >= 1000) {
            String.format("%.1f km", totalDistanceMeters / 1000.0)
        } else {
            "${kotlin.math.round(totalDistanceMeters).toInt()} m"
        }

    val formattedDuration: String
        get() {
            val mins = kotlin.math.round(totalDurationSeconds / 60.0).toInt()
            return if (mins >= 60) {
                val hours = mins / 60
                val remMins = mins % 60
                if (remMins > 0) "${hours} hr ${remMins} min" else "${hours} hr"
            } else {
                "${mins} min"
            }
        }
}

data class Ride(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startLocationName: String,
    val destinationName: String,
    val startLocation: LatLng,
    val destinationLocation: LatLng,
    val leaderId: String,
    val inviteCode: String,
    val status: RideStatus = RideStatus.LOBBY,
    val members: List<RiderMember> = emptyList(),
    val routePoints: List<LatLng> = emptyList(),
    val totalDistanceMeters: Double = 0.0,
    val totalDurationSeconds: Double = 0.0,
    val navigationSteps: List<NavigationStep> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
)
