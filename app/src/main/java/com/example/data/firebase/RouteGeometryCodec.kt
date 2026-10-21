package com.example.data.firebase

import com.example.data.model.LaneArrow
import com.example.data.model.LaneGuidance
import com.example.data.model.LaneMark
import com.example.data.model.LatLng
import com.example.data.model.ManeuverType
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
                    "modifier" to it.modifier,
                    // Richer maneuver model (additive: older documents simply
                    // lack these fields and decode to the defaults).
                    "maneuver" to it.maneuver.name,
                    "exitNumber" to it.exitNumber,
                    "turnAngleDegrees" to it.turnAngleDegrees,
                    "lanes" to it.lanes?.lanes?.map { lane ->
                        mapOf(
                            "arrows" to lane.arrows.map { a -> a.name },
                            "recommended" to lane.recommended
                        )
                    }
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
                modifier = step["modifier"] as? String ?: "straight",
                maneuver = decodeManeuver(step["maneuver"] as? String),
                exitNumber = (step["exitNumber"] as? Number)?.toInt(),
                turnAngleDegrees = (step["turnAngleDegrees"] as? Number)?.toInt(),
                lanes = decodeLanes(step["lanes"])
            )
        }

        return StoredRouteGeometry(
            points = points,
            steps = steps,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds
        )
    }

    /**
     * Decodes the maneuver name. Unknown names fall back to the raw
     * `(type, modifier)` pair, so a document written by a newer build still
     * decodes to a sensible maneuver.
     */
    private fun decodeManeuver(name: String?): ManeuverType {
        if (name.isNullOrBlank()) return ManeuverType.UNKNOWN
        return ManeuverType.values().firstOrNull { it.name == name } ?: ManeuverType.UNKNOWN
    }

    /**
     * Decodes the persisted lane guidance.
     *
     * A lane without any modelled arrow is dropped, and a guidance without a
     * usable lane is dropped as a whole — the same "never show a lane that
     * was not there" rule the parser applies to live responses.
     */
    private fun decodeLanes(raw: Any?): LaneGuidance? {
        @Suppress("UNCHECKED_CAST")
        val list = raw as? List<Any?> ?: return null
        val marks = list.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any?> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val arrowNames = map["arrows"] as? List<Any?> ?: return@mapNotNull null
            val arrows = arrowNames.mapNotNull { a ->
                val key = a as? String ?: return@mapNotNull null
                LaneArrow.values().firstOrNull { it.name == key }
            }.toSet()
            if (arrows.isEmpty()) return@mapNotNull null
            LaneMark(arrows = arrows, recommended = map["recommended"] as? Boolean ?: false)
        }
        if (marks.isEmpty()) return null
        val guidance = LaneGuidance(marks)
        return if (guidance.hasRecommendedLane) guidance else null
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
