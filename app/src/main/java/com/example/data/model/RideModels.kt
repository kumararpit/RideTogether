package com.example.data.model

import java.util.UUID

enum class RideStatus {
    CREATED,
    LOBBY,
    ACTIVE,
    COMPLETED,
    CANCELLED
}

enum class MovementState {
    RIDING,
    STOPPED,
    UNKNOWN
}

enum class EmergencyState {
    NONE,
    ACTIVE
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
    STOPPED("Stopped", "🛑"),
    NEED_FUEL("Need fuel", "⛽"),
    NEED_HELP("Need help", "🆘"),
    REGROUP("Regroup", "🤝"),
    WAIT_FOR_ME("Wait for me", "⏳"),
    SLOW_DOWN("Slow down", "⚠️"),
    IM_OKAY("I'm okay", "👍"),
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
    val movementState: MovementState = MovementState.RIDING,
    val emergencyState: EmergencyState = EmergencyState.NONE,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTED,
    val location: LatLng,
    val speedKmh: Double = 0.0,
    /**
     * Qualified heading in degrees (0..360, compass convention), or null when the
     * fix provided no bearing and course-over-ground estimation was unavailable.
     * Null must never be rendered as "north" (0°) — that is the Qualified Bearing
     * contract shared by the camera, the marker animator, and Firestore sync.
     */
    val headingDeg: Float? = null,
    val accuracyMeters: Float = 10f,
    val sequenceNumber: Long = 0L,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val serverTimestampMs: Long = 0L,
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
            String.format(java.util.Locale.getDefault(), "%.1f km", distKm)
        }
    }

    fun getRelativePositionDescription(userLoc: LatLng, userHeading: Float): String {
        if (isCurrentUser) return "You"
        val distKm = location.distanceTo(userLoc)
        val formattedDist = if (distKm < 1.0) "${(distKm * 1000).toInt()} m" else String.format(java.util.Locale.getDefault(), "%.1f km", distKm)
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
    val isResolved: Boolean = false,
    val acknowledgedBy: List<String> = emptyList(),
    val accuracyMeters: Float = 10f,
    val locationSource: String = "FRESH_LOCATION_FIX"
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
            String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000.0)
        } else {
            "${kotlin.math.round(distanceMeters).toInt()} m"
        }
}

/**
 * Role of a stop in an ordered route plan (multi-waypoint navigation).
 * The origin and final destination are tracked explicitly so they can never
 * appear duplicated inside the intermediate-stop collection.
 */
enum class RouteStopRole {
    ORIGIN,
    INTERMEDIATE,
    DESTINATION
}

/**
 * One ordered stop in a route plan. Stable [id] survives reordering (drag/drop),
 * edits, leader re-publication, and Firestore round-trips; a stop's identity is
 * never inferred from its position.
 */
data class RouteStop(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val role: RouteStopRole = RouteStopRole.INTERMEDIATE
) {
    val latLng: LatLng get() = LatLng(latitude, longitude)

    /** Structural validity used before any routing request is assembled. */
    fun isValidCoordinate(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * One OSRM route leg: the road path between two consecutive waypoints.
 * [startVertexIndex] locates the leg inside the overall [RouteResult.coordinates]
 * polyline (the overview geometry is one continuous set of vertices), so the map
 * renderer keeps drawing a single road-following line - no synthetic straight
 * connection between leg endpoints is ever introduced.
 */
data class RouteLeg(
    val startVertexIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<NavigationStep> = emptyList()
)

data class RouteResult(
    val coordinates: List<LatLng>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val steps: List<NavigationStep>,
    val isFallback: Boolean = false,
    /**
     * Ordered request waypoints (origin, intermediates, destination) as they were
     * submitted to OSRM. Empty for legacy single-destination routes; once set it
     * never silently diverges from the rendered geometry.
     */
    val waypoints: List<LatLng> = emptyList(),
    /** Ordered structured stops for [waypoints] (>=2 entries when non-empty). */
    val stops: List<RouteStop> = emptyList(),
    /** Per-leg OSRM data aligned 1:1 with (waypoints.size - 1) intersections. */
    val legs: List<RouteLeg> = emptyList(),
    /** Monotonic route-plan identity; guards against stale overwrite in sync. */
    val planVersion: Long = 0L
) {
    val formattedDistance: String
        get() = if (totalDistanceMeters >= 1000) {
            String.format(java.util.Locale.getDefault(), "%.1f km", totalDistanceMeters / 1000.0)
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

/**
 * One candidate route for the same ordered waypoint plan.
 *
 * The routing engine can return several road paths for one ordered set of
 * stops (the reference implementation shows them as candidate routes with a
 * "best route" preset). Each candidate is a complete [RouteResult]: its own
 * geometry, legs and steps - a candidate is never a partial or re-associated
 * view of another candidate's route.
 */
data class RouteOption(
    val index: Int,
    val route: RouteResult
) {
    val distanceMeters: Double get() = route.totalDistanceMeters
    val durationSeconds: Double get() = route.totalDurationSeconds
    val formattedDistance: String get() = route.formattedDistance
    val formattedDuration: String get() = route.formattedDuration
    val isFallback: Boolean get() = route.isFallback
}

/**
 * Lightweight description of a candidate route - distance and duration only,
 * without the geometry.
 *
 * This is the part that is shared with the group: every member sees the same
 * list of candidate routes (so the leader's choice is meaningful), while the
 * geometry itself stays per-device because each member re-fetches the route
 * for the candidate that was selected.
 */
data class RouteOptionSummary(
    val index: Int,
    val distanceMeters: Double,
    val durationSeconds: Double
) {
    val formattedDistance: String
        get() = if (distanceMeters >= 1000) {
            String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000.0)
        } else {
            "${kotlin.math.round(distanceMeters).toInt()} m"
        }

    val formattedDuration: String
        get() {
            val mins = kotlin.math.round(durationSeconds / 60.0).toInt()
            return if (mins >= 60) {
                val hours = mins / 60
                val remMins = mins % 60
                if (remMins > 0) "${hours} hr ${remMins} min" else "${hours} hr"
            } else {
                "$mins min"
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
    val initialPlannedDistanceMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val totalDurationSeconds: Double = 0.0,
    val navigationSteps: List<NavigationStep> = emptyList(),
    /**
     * Leader-authoritative ordered route plan (origin, intermediates, destination).
     * Empty for every legacy ride; members never write it - only the leader's
     * route publication does (see RideRepository.updateRoute).
     */
    val routeStops: List<RouteStop> = emptyList(),
    /** Monotonic plan version; stale (older) snapshots never overwrite newer. */
    val routePlanVersion: Long = 0L,
    /**
     * Index of the candidate route the leader selected for this plan.
     * 0 is the engine's primary route; a larger index selects an alternative.
     * Always clamped to the available candidate list when the plan is applied.
     */
    val selectedRouteIndex: Int = 0,
    /**
     * Candidate routes published with the plan (summaries only). Empty for
     * legacy rides and for plans where the engine returned a single route.
     */
    val routeOptions: List<RouteOptionSummary> = emptyList(),
    val actualRecordedDistanceMeters: Double = 0.0,
    val elapsedDurationSeconds: Double = 0.0,
    val startedAtMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    /**
     * Best-effort trip distance for the ride summary.
     *
     * The GPS odometer is trusted only when it is plausible: it must have recorded a
     * meaningful distance (>= 50 m) AND at least 15% of the planned route distance.
     * This prevents glitched GPS sessions (e.g., a 7.5 km ride where GPS dropped out
     * and only 100 m got recorded) from overwriting the planned distance in Recent Rides.
     */
    val finalSummaryDistanceMeters: Double
        get() {
            val planned = if (initialPlannedDistanceMeters > 0.0) initialPlannedDistanceMeters else totalDistanceMeters
            return when {
                // If actual GPS odometer recorded meaningful distance (at least 50m and at least 15% of planned route)
                actualRecordedDistanceMeters >= 50.0 && (planned <= 0.0 || actualRecordedDistanceMeters >= planned * 0.15) ->
                    actualRecordedDistanceMeters
                planned > 0.0 -> planned
                else -> actualRecordedDistanceMeters
            }
        }
}
