package com.example.data.model

/**
 * The plan arithmetic of "add a place to the ride as a stop".
 *
 * The reference implementation turns a selected place into a waypoint of the
 * route and tells the rider what the stop costs ("+3 min"). The cost is
 * simply the difference between the ride that was planned and the ride that
 * includes the stop, so it can be derived from the two route results the app
 * already has — no extra service is needed.
 *
 * The derivation is pure so the stop ordering and the detour can be verified
 * without a device or a network.
 */
object AmenityStopPlan {

    /**
     * Inserts [amenity] into the ordered [stops] of the remaining plan.
     *
     * The place is added as the **last intermediate stop**, i.e. just before
     * the final destination: the rider asked for a place they can reach on the
     * way, and the detour of the last slot is the smallest one that does not
     * change the order of the stops that were already agreed.
     *
     * The origin is never duplicated and the destination stays last.
     */
    fun insert(stops: List<RouteStop>, amenity: RouteStop): List<RouteStop> {
        if (stops.isEmpty()) return listOf(amenity)
        val destinationIndex = stops.indexOfLast { it.role == RouteStopRole.DESTINATION }
        val insertAt = if (destinationIndex >= 0) destinationIndex else stops.size
        val withoutDuplicates = stops.filterNot {
            it.role != RouteStopRole.ORIGIN &&
                it.latitude == amenity.latitude && it.longitude == amenity.longitude
        }
        val destination = withoutDuplicates.getOrNull(
            withoutDuplicates.indexOfLast { it.role == RouteStopRole.DESTINATION }
        )
        val head = withoutDuplicates.filter { it.role != RouteStopRole.DESTINATION }
        val result = head.toMutableList()
        result.add(amenity)
        if (destination != null) result.add(destination)
        return result
    }

    /**
     * The extra time the stop adds to the ride, in seconds.
     *
     * Both numbers must be measured the same way — see the call site, which
     * converts the old remaining distance with the new route's average speed
     * before comparing. A stop that shortens the ride (possible on a
     * loop-shaped route) is reported as zero: a negative detour is noise, not
     * information.
     */
    fun detourSeconds(newRemainingSeconds: Double, oldRemainingSeconds: Double): Double =
        (newRemainingSeconds - oldRemainingSeconds).coerceAtLeast(0.0)

    /**
     * Formats a detour the way a rider reads it: "3 min", "40 s", or
     * "under a minute" for a detour too small to be worth a number.
     */
    fun formatDetour(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0)
        return when {
            s < 60 -> "under a minute"
            s < 3600 -> "${Math.round(s / 60.0)} min"
            else -> String.format(java.util.Locale.US, "%.1f h", s / 3600.0)
        }
    }

    /**
     * The label of the detour, e.g. "+3 min detour" — or null when the stop is
     * free, so the UI can leave the badge out entirely.
     */
    fun detourLabel(seconds: Double): String? {
        if (seconds < 30) return null
        return "+${formatDetour(seconds)} detour"
    }
}
