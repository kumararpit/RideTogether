package com.example.data.location

import com.example.data.model.LatLng
import kotlin.math.cos
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
     * Calculates the minimum distance in meters from [point] to the polyline [route],
     * checking perpendicular distance to each polyline segment for precision.
     */
    fun distanceToPolylineMeters(point: LatLng, route: List<LatLng>): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        if (route.size == 1) return point.distanceTo(route[0]) * 1000.0

        var minDistanceMeters = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val dist = distanceToSegmentMeters(point, route[i], route[i + 1])
            if (dist < minDistanceMeters) {
                minDistanceMeters = dist
            }
        }
        return minDistanceMeters
    }

    /**
     * Calculates the perpendicular distance in meters from [p] to segment [a]-[b].
     */
    fun distanceToSegmentMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        // Approximate flat projection locally
        val meanLatRad = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val kx = cos(meanLatRad) * 111320.0 // meters per degree lon
        val ky = 110540.0 // meters per degree lat

        val ax = a.longitude * kx
        val ay = a.latitude * ky
        val bx = b.longitude * kx
        val by = b.latitude * ky
        val px = p.longitude * kx
        val py = p.latitude * ky

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy

        if (lenSq == 0.0) {
            val diffX = px - ax
            val diffY = py - ay
            return sqrt(diffX * diffX + diffY * diffY)
        }

        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val clampedT = t.coerceIn(0.0, 1.0)
        val projX = ax + clampedT * dx
        val projY = ay + clampedT * dy

        val finalDiffX = px - projX
        val finalDiffY = py - projY
        return sqrt(finalDiffX * finalDiffX + finalDiffY * finalDiffY)
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
