package com.example.data.firebase

import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import java.util.Locale

/**
 * Route geometry persistence codec (Phase B, backlog B-03; audit F12-D1).
 *
 * The route geometry is stored in a dedicated document
 * (`rides/{rideId}/route/current`) so that a ride restored after a process
 * death can be re-armed with its real polyline instead of an empty navigation
 * state. Keeping it out of the ride document matters: that document is
 * re-read by every listener on every member/location update.
 *
 * The codec is deliberately **pure** (no Firestore types) so the round-trip and
 * the truncation cap are unit-testable without a backend.
 */
object RouteGeometryCodec {

    /**
     * Maximum number of vertices persisted per ride. A 2,000-vertex polyline is
     * ≈80 KB on the wire — far below Firestore's 1 MiB document limit. Longer
     * routes are stored truncated, which affects only the visual fidelity of the
     * restored line; distance, duration and steps stay exact.
     */
    const val MAX_PERSISTED_ROUTE_POINTS = 2_000

    /** Encodes a route into the persisted document's field map. */
    fun encode(
        points: List<LatLng>,
        steps: List<NavigationStep>,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double
    ): Map<String, Any?> {
        val stored = if (points.size > MAX_PERSISTED_ROUTE_POINTS) {
            points.take(MAX_PERSISTED_ROUTE_POINTS)
        } else {
            points
        }
        return mapOf(
            "points" to stored.map { listOf(it.latitude, it.longitude) },
            "steps" to steps.map {
                mapOf(
                    "instruction" to it.instruction,
                    "roadName" to it.roadName,
                    "distanceMeters" to it.distanceMeters,
                    "durationSeconds" to it.durationSeconds,
                    "maneuverType" to it.maneuverType,
                    "modifier" to it.modifier
                )
            },
            "totalDistanceMeters" to totalDistanceMeters,
            "totalDurationSeconds" to totalDurationSeconds,
            "pointCount" to points.size,
            "truncated" to (points.size > stored.size)
        )
    }

    /**
     * Decodes a persisted geometry document. Returns `null` when the document
     * carries no usable polyline (missing field, malformed entries or fewer
     * than two vertices) — the caller then treats the ride as geometry-less.
     */
    fun decode(
        rawPoints: Any?,
        rawSteps: Any?,
        totalDistanceMeters: Double,
        totalDurationSeconds: Double
    ): StoredRouteGeometry? {
        @Suppress("UNCHECKED_CAST")
        val pointList = rawPoints as? List<Any?> ?: return null
        val points = pointList.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val pair = entry as? List<Any?> ?: return@mapNotNull null
            val lat = (pair.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (pair.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
            if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                null
            } else {
                LatLng(lat, lng)
            }
        }
        if (points.size < 2) return null

        @Suppress("UNCHECKED_CAST")
        val stepList = rawSteps as? List<Any?> ?: emptyList()
        val steps = stepList.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val step = entry as? Map<String, Any?> ?: return@mapNotNull null
            val instruction = step["instruction"] as? String ?: return@mapNotNull null
            NavigationStep(
                instruction = instruction,
                roadName = step["roadName"] as? String ?: "",
                distanceMeters = (step["distanceMeters"] as? Number)?.toDouble() ?: 0.0,
                durationSeconds = (step["durationSeconds"] as? Number)?.toDouble() ?: 0.0,
                maneuverType = step["maneuverType"] as? String ?: "turn",
                modifier = step["modifier"] as? String ?: "straight"
            )
        }

        return StoredRouteGeometry(
            points = points,
            steps = steps,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds
        )
    }

    /** Compact, human-readable summary used in logs. */
    fun describe(geometry: StoredRouteGeometry): String =
        String.format(
            Locale.US,
            "%d points, %d steps, %.1f km",
            geometry.points.size,
            geometry.steps.size,
            geometry.totalDistanceMeters / 1000.0
        )
}

/**
 * Route geometry persisted for a ride (Phase B, backlog B-03).
 */
data class StoredRouteGeometry(
    val points: List<LatLng>,
    val steps: List<NavigationStep>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double
)
