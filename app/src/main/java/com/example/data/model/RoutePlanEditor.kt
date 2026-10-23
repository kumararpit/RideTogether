package com.example.data.model

/**
 * Pure edits to an ordered route plan.
 *
 * The ride's stops are a list with exactly one origin, zero or more
 * intermediate stops, and exactly one destination. Removing a stop must never
 * delete an endpoint, and a stop that has already been ridden past must not
 * reappear in the remaining sequence.
 */
object RoutePlanEditor {

    /**
     * Returns [stops] without the intermediate stop whose id is [stopId].
     *
     * Origin and destination are never removed, even if their ids match: the
     * rider can drop a stop, not the endpoints of the ride.
     */
    fun withoutIntermediate(stops: List<RouteStop>, stopId: String): List<RouteStop> =
        stops.filterNot { it.id == stopId && it.role == RouteStopRole.INTERMEDIATE }

    /**
     * The part of [stops] that is still ahead while the rider is on
     * [currentLegIndex] (0-based leg from origin to the first stop).
     *
     * The completed legs and the origin are left out; the destination stays
     * last. This mirrors what the router is asked for when re-planning
     * mid-ride.
     */
    fun remainingAfterLeg(stops: List<RouteStop>, currentLegIndex: Int): List<RouteStop> =
        stops.drop((currentLegIndex + 1).coerceAtLeast(1))
            .filter { it.role != RouteStopRole.ORIGIN }

    /** The intermediate stops of [stops], in plan order. */
    fun intermediates(stops: List<RouteStop>): List<RouteStop> =
        stops.filter { it.role == RouteStopRole.INTERMEDIATE }
}
