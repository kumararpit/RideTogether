package com.example.data.location

import com.example.data.model.LatLng

/**
 * Exact leg-boundary geometry for a multi-waypoint route.
 *
 * A route is one continuous overview polyline; each leg (origin -> stop 1,
 * stop 1 -> stop 2, ...) starts at a vertex index inside that polyline. The
 * renderer pins those indices so simplification can never draw a chord across
 * a stop, and the navigation engine uses them to advance from one leg to the
 * next.
 *
 * The previous approach located a leg's start with a nearest-point search over
 * the remaining polyline. That is ambiguous whenever a waypoint sits near a
 * hairpin, a service road or a duplicated vertex: the nearest vertex may belong
 * to a different pass of the road, which puts the leg boundary - and therefore
 * the arrival test - in the wrong place.
 *
 * This class derives the boundary from the **cumulative leg distance** that the
 * engine reports, which is exact for the geometry the engine itself produced:
 * the vertex at cumulative distance `sum(leg distances before this leg)` is the
 * leg's start. A tight window around that distance is then used to snap to the
 * vertex closest to the actual waypoint, so floating-point drift and small
 * off-polyline waypoint snapping cannot move the boundary.
 */
object LegBoundaryMath {

    /**
     * Window around the cumulative-distance target, as a fraction of the leg
     * length (and an absolute floor). Wide enough to absorb rounding, tight
     * enough to exclude a hairpin on another pass of the same road.
     */
    private const val WINDOW_FRACTION = 0.15
    private const val WINDOW_FLOOR_METERS = 150.0

    /**
     * Returns the start-vertex index of every leg, or an empty list when the
     * inputs are inconsistent (not enough vertices, no legs, or a leg count
     * that cannot be reconciled with the geometry).
     *
     * @param points the merged overview polyline
     * @param legDistancesMeters distance of each leg, in order
     * @param waypoints the ordered request waypoints (origin first); used to
     *        snap the boundary onto the exact vertex, when available
     */
    fun legStartVertexIndices(
        points: List<LatLng>,
        legDistancesMeters: List<Double>,
        waypoints: List<LatLng> = emptyList()
    ): List<Int> {
        if (points.size < 2 || legDistancesMeters.isEmpty()) return emptyList()

        val cumulative = cumulativeDistances(points)
        val totalLength = cumulative.last()
        if (totalLength <= 0.0) return emptyList()

        val boundaries = mutableListOf<Int>()
        var distanceBefore = 0.0
        var previousIndex = 0

        for (legIndex in legDistancesMeters.indices) {
            val legDistance = legDistancesMeters[legIndex]
            val target = distanceBefore
            val window = maxOf(WINDOW_FLOOR_METERS, legDistance * WINDOW_FRACTION)

            // The first leg always starts at the first vertex.
            val chosen = if (legIndex == 0) {
                0
            } else {
                val candidates = cumulative.indices.filter { index ->
                    index in (previousIndex + 1)..cumulative.lastIndex &&
                        abs(cumulative[index] - target) <= window
                }

                when {
                    candidates.isNotEmpty() -> {
                        // Prefer the vertex that is closest to the actual waypoint
                        // (the engine may have snapped the waypoint onto the road).
                        val waypoint = waypoints.getOrNull(legIndex)
                        if (waypoint != null) {
                            candidates.minByOrNull { index ->
                                RouteGeometry.distanceToPolylineMeters(waypoint, listOf(points[index]))
                            } ?: candidates.first()
                        } else {
                            candidates.minByOrNull { index -> abs(cumulative[index] - target) } ?: candidates.first()
                        }
                    }
                    else -> {
                        // No vertex inside the window: fall back to the vertex whose
                        // cumulative distance is closest to the target, still
                        // constrained to be at or after the previous boundary.
                        (previousIndex + 1..cumulative.lastIndex)
                            .minByOrNull { index -> abs(cumulative[index] - target) }
                            ?: previousIndex
                    }
                }
            }

            val safeIndex = chosen.coerceIn(previousIndex, cumulative.lastIndex)
            boundaries.add(safeIndex)
            previousIndex = safeIndex
            distanceBefore += legDistance
        }
        return boundaries
    }

    /** Cumulative distance (meters) at every vertex; index i holds the distance to point i. */
    fun cumulativeDistances(points: List<LatLng>): DoubleArray {
        val out = DoubleArray(points.size)
        if (points.isEmpty()) return out
        var running = 0.0
        for (i in 1 until points.size) {
            running += points[i - 1].distanceTo(points[i]) * 1000.0
            out[i] = running
        }
        return out
    }

    private fun abs(value: Double): Double = if (value < 0.0) -value else value
}
