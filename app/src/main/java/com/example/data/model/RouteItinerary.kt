package com.example.data.model

/**
 * One leg of the stop-by-stop itinerary: the road between two consecutive
 * stops of the plan.
 */
data class ItineraryLeg(
    val index: Int,
    val fromName: String,
    val toName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<NavigationStep>
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
                if (remMins > 0) "${hours}h ${remMins}m" else "${hours}h"
            } else {
                "$mins min"
            }
        }
}

/**
 * Builds the stop-by-stop itinerary of a route.
 *
 * A multi-waypoint route is a sequence of legs: origin -> stop 1 -> ... ->
 * destination. Each leg carries its own distance, duration and maneuvers, and
 * the leg boundaries are the exact vertices of the route geometry.
 *
 * The derivation is pure so the itinerary can be verified without a UI.
 */
fun buildItinerary(route: RouteResult?): List<ItineraryLeg> {
    if (route == null || route.legs.isEmpty()) return emptyList()
    return route.legs.mapIndexed { index, leg ->
        val from = route.stops.getOrNull(index)?.name ?: "Start"
        val to = route.stops.getOrNull(index + 1)?.name ?: "Destination"
        ItineraryLeg(
            index = index,
            fromName = from,
            toName = to,
            distanceMeters = leg.distanceMeters,
            durationSeconds = leg.durationSeconds,
            steps = leg.steps
        )
    }
}
