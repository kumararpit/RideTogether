package com.example.data.location

import com.example.data.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, unit-testable course-over-ground estimation used by Qualified Bearing.
 *
 * Google Maps-quality reference: bearing is *qualified*, never fabricated. When a
 * fused fix carries no bearing, the course derived from two consecutive fixes is
 * only adopted when the displacement is large enough for the geometric bearing
 * to be meaningful, and it is still gated by speed downstream
 * ([MapCameraController.processLocation] applies the same speed gate to GPS and
 * derived bearing alike).
 */
object CourseOverGround {

    /** Minimum displacement between fixes for derived course to be trustworthy. */
    const val MIN_DERIVATION_DISTANCE_METERS = 3.0f

    /** Minimum elapsed time between fixes for derivation. */
    const val MIN_DERIVATION_INTERVAL_MS = 500L

    /**
     * Initial (great-circle) bearing from [from] to [to] in normalized 0..360,
     * or null when either coordinate is invalid/too close together to produce a
     * stable direction.
     */
    fun bearingBetween(from: LatLng, to: LatLng): Float? {
        fun valid(lat: Double, lng: Double): Boolean =
            lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

        if (!valid(from.latitude, from.longitude) || !valid(to.latitude, to.longitude)) return null

        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val x = sin(dLon) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        if (x == 0.0 && y == 0.0) return null // identical points: no direction

        val bearingDeg = Math.toDegrees(atan2(x, y))
        return ((bearingDeg + 360.0) % 360.0).toFloat()
    }

    /**
     * Derives a course heading from a consecutive pair of fixes, or null when the
     * pair is too close together (too little displacement, too little elapsed time,
     * or invalid coordinates) for direction to be meaningful.
     */
    fun derive(
        prev: LatLng?,
        prevTimestampMs: Long,
        current: LatLng,
        currentTimestampMs: Long
    ): Float? {
        if (prev == null) return null
        val dtMs = currentTimestampMs - prevTimestampMs
        if (dtMs < MIN_DERIVATION_INTERVAL_MS) return null
        val distanceMeters = prev.distanceTo(current) * 1000.0
        if (distanceMeters < MIN_DERIVATION_DISTANCE_METERS) return null
        return bearingBetween(prev, current)
    }
}
