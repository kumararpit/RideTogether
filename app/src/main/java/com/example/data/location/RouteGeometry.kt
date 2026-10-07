package com.example.data.location

import com.example.data.model.LatLng
import kotlin.math.sqrt

object RouteGeometry {

    /**
     * Finds the index of the vertex in [route] that is closest to [point].
     */
    fun findClosestPointIndex(point: LatLng, route: List<LatLng>): Int {
        if (route.isEmpty()) return -1
        var minDistanceKm = Double.MAX_VALUE
        var closestIndex = 0

        for (i in route.indices) {
            val dist = point.distanceTo(route[i])
            if (dist < minDistanceKm) {
                minDistanceKm = dist
                closestIndex = i
            }
        }
        return closestIndex
    }

    /**
     * Calculates the minimum distance in meters from [point] to the polyline [route].
     */
    fun distanceToPolylineMeters(point: LatLng, route: List<LatLng>): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) return point.distanceTo(route[0]) * 1000.0

        val closestIdx = findClosestPointIndex(point, route)
        if (closestIdx < 0) return Double.MAX_VALUE
        return point.distanceTo(route[closestIdx]) * 1000.0
    }

    /**
     * Calculates the remaining distance in meters along the polyline starting from [fromIndex] to the end.
     */
    fun remainingDistanceMeters(fromIndex: Int, route: List<LatLng>): Double {
        if (route.isEmpty() || fromIndex >= route.size - 1) return 0.0
        var totalDistKm = 0.0
        val start = fromIndex.coerceAtLeast(0)
        for (i in start until route.size - 1) {
            totalDistKm += route[i].distanceTo(route[i + 1])
        }
        return totalDistKm * 1000.0
    }
}
