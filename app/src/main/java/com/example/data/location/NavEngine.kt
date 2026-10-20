package com.example.data.location

import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

data class NavigationProgress(
    val currentSegmentIndex: Int = 0,
    /**
     * Index of the **last maneuver the rider has passed** (bookkeeping only).
     * The guidance shown to the rider is driven by [upcomingManeuverIndex].
     */
    val currentManeuverIndex: Int = 0,
    /**
     * Index of the **upcoming maneuver** — the first step whose maneuver vertex
     * has not yet been passed by the rider's projected position.
     *
     * This is the step the banner instruction, the maneuver icon, the spoken
     * prompt AND [distanceToNextManeuverMeters] all refer to. Keeping one index
     * for all four is what guarantees the displayed distance always belongs to
     * the displayed instruction (audit F1-D1: the previous implementation showed
     * the *previous* step's text next to the *next* step's distance).
     */
    val upcomingManeuverIndex: Int = 0,
    val distanceToNextManeuverMeters: Double = 0.0,
    val remainingRouteDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val isOffRoute: Boolean = false,
    val routeVersion: Int = 1,
    val isArrived: Boolean = false,
    val isStaleGps: Boolean = false,
    val emaSpeedKmh: Double = 0.0,
    /**
     * Multi-waypoint progression: index of the leg the rider is currently on
     * (0 = origin→first stop). 0 for legacy single-destination routes, and
     * capped so no-stop navigation stays regression-free.
     */
    val currentLegIndex: Int = 0,
    /** Total number of legs on the active route (1 for legacy routes). */
    val legCount: Int = 1
)

class NavEngine {

    private var activeRoute: RouteResult? = null
    private var routeVersion = 1

    /**
     * Session latched arrival flag. Once the rider reaches the destination this stays true
     * for the whole ride, so GPS overshoot around the finish pin can never re-trigger
     * off-route recalculation loops. Cleared only on [reset] (new ride session).
     */
    private var hasArrivedAtDestination = false

    /**
     * Tracks how many intermediate legs have been completed (rider confirmed at
     * a stop with an accurate fix). Two invariants:
     *  - advancement happens at most ONE leg per [processLocation] call, so a
     *    single stale/jumpy fix can never skip over closely spaced stops;
     *  - advancement requires accuracy-reliable fixes AND the rider still being
     *    on-route (off-route fixes return early).
     */
    private var completedLegs = 0

    private var stepVertexIndices: List<Int> = emptyList()
    private var emaSpeedMps: Double = 0.0

    private val _progress = MutableStateFlow(NavigationProgress())
    val progress: StateFlow<NavigationProgress> = _progress.asStateFlow()

    private val _lastAnnouncedManeuverIndex = MutableStateFlow(-1)
    val lastAnnouncedManeuverIndex: StateFlow<Int> = _lastAnnouncedManeuverIndex.asStateFlow()

    fun setRoute(route: RouteResult) {
        val realisticDurationSec = calculateRealisticPlannedDuration(route.totalDistanceMeters, route.totalDurationSeconds)
        val adjustedRoute = route.copy(totalDurationSeconds = realisticDurationSec)
        activeRoute = adjustedRoute
        routeVersion++
        _lastAnnouncedManeuverIndex.value = -1
        emaSpeedMps = 0.0

        val points = adjustedRoute.coordinates
        val steps = adjustedRoute.steps

        // Map each navigation step to its corresponding vertex index in coordinates
        stepVertexIndices = computeStepVertexIndices(points, steps)

        // The first maneuver the rider has not yet passed. OSRM's step 0 is the
        // "depart" maneuver at the origin, so the first *upcoming* maneuver is
        // step 1 whenever the route has more than one step.
        val initialUpcoming = if (steps.size > 1 && stepVertexIndices.size > 1) 1 else 0
        val initialNextStepDist = if (steps.isNotEmpty() && stepVertexIndices.size > 1) {
            val nextVertex = stepVertexIndices.getOrElse(initialUpcoming) { points.size - 1 }
            RouteGeometry.remainingDistanceMeters(0, points.take(nextVertex + 1))
        } else {
            adjustedRoute.totalDistanceMeters
        }

        _progress.value = NavigationProgress(
            currentSegmentIndex = 0,
            currentManeuverIndex = 0,
            upcomingManeuverIndex = initialUpcoming.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
            distanceToNextManeuverMeters = initialNextStepDist,
            remainingRouteDistanceMeters = adjustedRoute.totalDistanceMeters,
            remainingDurationSeconds = adjustedRoute.totalDurationSeconds,
            isOffRoute = false,
            routeVersion = routeVersion,
            isArrived = false,
            isStaleGps = false,
            emaSpeedKmh = 0.0,
            currentLegIndex = 0,
            legCount = max(1, adjustedRoute.legs.size)
        )
        completedLegs = 0
    }

    fun processLocation(location: LocationUpdate): NavigationProgress {
        val route = activeRoute ?: return _progress.value
        val points = route.coordinates
        if (points.isEmpty()) return _progress.value

        val userLoc = location.latLng
        val prevProgress = _progress.value
        val isAccuracyReliable = location.accuracyMeters <= 30f

        // 0. Arrival latch check — within 35 m of the final destination coordinate or
        //    with <= 25 m of route remaining, permanently latch arrival.
        val destination = points.last()
        val distToDestinationMeters = userLoc.distanceTo(destination) * 1000.0
        if (!hasArrivedAtDestination &&
            (distToDestinationMeters <= 35.0 || prevProgress.remainingRouteDistanceMeters in 0.0..25.0)
        ) {
            hasArrivedAtDestination = true
        }

        if (hasArrivedAtDestination) {
            // Hard latch: force terminal arrival state, suppress any off-route detection,
            // and zero out all remaining distance/duration readouts.
            val arrivedProgress = prevProgress.copy(
                isArrived = true,
                isOffRoute = false,
                remainingRouteDistanceMeters = 0.0,
                remainingDurationSeconds = 0.0,
                distanceToNextManeuverMeters = 0.0,
                isStaleGps = !isAccuracyReliable,
                routeVersion = routeVersion
            )
            _progress.value = arrivedProgress
            return arrivedProgress
        }

        // 1. Off-route detection
        val distToPolylineM = RouteGeometry.distanceToPolylineMeters(userLoc, points)
        val isOffRoute = isAccuracyReliable && distToPolylineM > 120.0

        if (isOffRoute) {
            val updated = prevProgress.copy(
                isOffRoute = true,
                isStaleGps = !isAccuracyReliable,
                routeVersion = routeVersion
            )
            _progress.value = updated
            return updated
        }

        // 2. Monotonic Segment Index Progress per Route Revision
        val globalClosest = RouteGeometry.findClosestPointIndex(userLoc, points)
        val currentSeg = prevProgress.currentSegmentIndex

        val closestIdx = if (prevProgress.routeVersion == routeVersion && currentSeg in points.indices) {
            // Search in forward window [currentSeg, currentSeg + 60] to ensure monotonic progress
            val searchMax = (currentSeg + 60).coerceAtMost(points.size - 1)
            var bestIdx = currentSeg
            var minDistance = Double.MAX_VALUE
            for (i in currentSeg..searchMax) {
                val d = userLoc.distanceTo(points[i])
                if (d < minDistance) {
                    minDistance = d
                    bestIdx = i
                }
            }
            // Fall back to global closest if user made a U-turn or jumped significantly
            if (minDistance * 1000.0 > 100.0 && globalClosest > currentSeg) {
                globalClosest
            } else {
                max(currentSeg, bestIdx)
            }
        } else {
            globalClosest
        }

        val remDistMeters = RouteGeometry.remainingDistanceMeters(closestIdx, points)

        // 2b. Intermediate-stop leg advancement (multi-waypoint routes).
        // At most ONE leg per fix: a single stale or jumpy fix (even a big GPS
        // overshoot merged into the on-route search window) can never skip over
        // two closely spaced waypoints in one call, because the next one is only
        // evaluated from the NEXT fix. Arrival at a stop mirrors the documented
        // final-arrival rules (35 m radius, accuracy-gated, not off-route —
        // off-route fixes returned above). Passing credit uses a crossing test
        // (closer to the next vertex than to the stop vertex), so sparse
        // vertex spacing can never prematurely complete a leg. Remaining
        // distance/ETA need no special transition handling: the merged
        // waypoint-to-waypoint polyline means remDistMeters already counts
        // through all future legs.
        val routeLegs = route.legs
        if (routeLegs.size >= 2 && completedLegs < routeLegs.size - 1 && isAccuracyReliable) {
            val nextStopVertex = routeLegs[completedLegs + 1].startVertexIndex
                .coerceIn(0, points.size - 1)
            val stopPoint = points[nextStopVertex]
            val distToStop = userLoc.distanceTo(stopPoint) * 1000.0
            val nextVertexAfterStop = (nextStopVertex + 1).coerceAtMost(points.size - 1)
            val distToNextVertex = userLoc.distanceTo(points[nextVertexAfterStop]) * 1000.0
            val crossedStop = closestIdx > nextStopVertex ||
                (closestIdx == nextStopVertex && nextVertexAfterStop != nextStopVertex && distToNextVertex < distToStop)
            if (distToStop <= 35.0 || crossedStop) {
                completedLegs++
            }
        }

        // 3. Maneuver Advancement & Dynamic Distance to Next Maneuver
        val steps = route.steps
        var currentManeuverIdx = prevProgress.currentManeuverIndex

        if (steps.isNotEmpty() && stepVertexIndices.size == steps.size) {
            // Advance the *performed* maneuver index when the rider reaches or
            // passes the vertex of the next step.
            //
            // B-04 (audit F1-D2): the advance is now GUARDED —
            //  * at most ONE maneuver per location fix, so a single stale or
            //    jumpy fix can never skip several maneuvers at once;
            //  * only an accuracy-reliable fix (≤30 m) may advance the index,
            //    matching the discipline already applied to leg advancement.
            // The guidance the rider sees does NOT depend on this counter — it is
            // derived from the projected position (see [upcomingManeuverIndex]) —
            // so a conservative advance can never hide a turn.
            if (isAccuracyReliable && currentManeuverIdx < steps.size - 1) {
                val nextManeuverVertex = stepVertexIndices[currentManeuverIdx + 1]
                val distToNextManeuver = userLoc.distanceTo(points[nextManeuverVertex]) * 1000.0
                if (closestIdx >= nextManeuverVertex || distToNextManeuver <= 25.0) {
                    currentManeuverIdx++
                }
            }
        }

        // The upcoming maneuver: the first step whose maneuver vertex has NOT
        // been passed by the rider's projected position. Everything the rider is
        // told (banner text, maneuver icon, spoken prompt) and the distance that
        // is counted down all refer to this single step.
        val upcomingManeuverIdx = computeUpcomingManeuverIndex(closestIdx, steps.size)

        // Distance along the polyline to the upcoming maneuver's vertex. When every
        // maneuver vertex has been passed, the distance is to the destination.
        val targetVertexIdx = when {
            steps.isEmpty() -> points.size - 1
            upcomingManeuverIdx >= stepVertexIndices.size -> points.size - 1
            stepVertexIndices[upcomingManeuverIdx] > closestIdx -> stepVertexIndices[upcomingManeuverIdx]
            else -> points.size - 1
        }

        val nextStepMeters = if (targetVertexIdx > closestIdx) {
            val subList = points.subList(closestIdx, (targetVertexIdx + 1).coerceAtMost(points.size))
            RouteGeometry.remainingDistanceMeters(0, subList)
        } else {
            userLoc.distanceTo(points[targetVertexIdx]) * 1000.0
        }

        // 4. Coherent & Deterministic ETA Policy
        val rawSpeedMps = (location.speedKmh * 1000.0) / 3600.0
        if (isAccuracyReliable && location.speedKmh >= 8.0) {
            emaSpeedMps = if (emaSpeedMps <= 0.0) rawSpeedMps else (0.25 * rawSpeedMps + 0.75 * emaSpeedMps)
        }

        val routeAvgSpeedMps = if (route.totalDurationSeconds > 0) {
            route.totalDistanceMeters / route.totalDurationSeconds
        } else {
            12.5 // ~45 km/h fallback default
        }

        val effectiveSpeedMps = if (emaSpeedMps >= 3.0) {
            emaSpeedMps.coerceIn(3.0, 25.0)
        } else {
            routeAvgSpeedMps
        }

        var remDurationSec = if (remDistMeters <= 10.0) {
            0.0
        } else if (effectiveSpeedMps > 0) {
            remDistMeters / effectiveSpeedMps
        } else {
            route.totalDurationSeconds
        }

        val isArrived = remDistMeters <= 30.0 || (closestIdx >= points.size - 2 && remDistMeters <= 40.0)
        if (isArrived) {
            hasArrivedAtDestination = true
            remDurationSec = 0.0
        }

        // Precise ETA Monotonicity Constraint:
        // Apply monotonicity only while rider is confirmed stationary (speedKmh < 3.0),
        // not arrived, and active route version is unchanged.
        if (!isArrived && location.speedKmh < 3.0 && routeVersion == prevProgress.routeVersion && prevProgress.remainingDurationSeconds > 0.0) {
            remDurationSec = maxOf(remDurationSec, prevProgress.remainingDurationSeconds)
        }

        val newProgress = NavigationProgress(
            currentSegmentIndex = closestIdx,
            currentManeuverIndex = currentManeuverIdx.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
            upcomingManeuverIndex = upcomingManeuverIdx.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
            distanceToNextManeuverMeters = nextStepMeters,
            remainingRouteDistanceMeters = remDistMeters,
            remainingDurationSeconds = remDurationSec,
            isOffRoute = false,
            routeVersion = routeVersion,
            isArrived = isArrived,
            isStaleGps = !isAccuracyReliable,
            emaSpeedKmh = (emaSpeedMps * 3600.0) / 1000.0,
            currentLegIndex = completedLegs.coerceIn(0, max(0, route.legs.size - 1)),
            legCount = max(1, route.legs.size)
        )

        _progress.value = newProgress
        return newProgress
    }

    private fun computeStepVertexIndices(points: List<LatLng>, steps: List<NavigationStep>): List<Int> {
        if (points.isEmpty()) return emptyList()
        if (steps.isEmpty()) return listOf(0)

        val pointCumDist = DoubleArray(points.size)
        pointCumDist[0] = 0.0
        for (i in 0 until points.size - 1) {
            pointCumDist[i + 1] = pointCumDist[i] + (points[i].distanceTo(points[i + 1]) * 1000.0)
        }

        val indices = mutableListOf<Int>()
        indices.add(0) // Step 0 starts at vertex 0

        var stepAccum = 0.0
        for (i in 0 until steps.size - 1) {
            stepAccum += steps[i].distanceMeters
            var bestIdx = 0
            var minDiff = Double.MAX_VALUE
            for (p in pointCumDist.indices) {
                val diff = abs(pointCumDist[p] - stepAccum)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIdx = p
                }
            }
            indices.add(bestIdx)
        }

        return indices
    }

    /**
     * Resolves the **upcoming maneuver**: the first step whose maneuver vertex
     * has not been passed by the rider's projected position [closestIdx].
     *
     * Because the projection is monotonic along the route, this index never moves
     * backwards, and it is independent of the (conservatively advanced) performed
     * maneuver counter — so the guidance the rider sees always describes the
     * maneuver the displayed distance belongs to.
     *
     * When every maneuver vertex has been passed, the last step is returned and
     * the distance falls back to the destination.
     */
    private fun computeUpcomingManeuverIndex(closestIdx: Int, stepCount: Int): Int {
        if (stepCount <= 0) return 0
        val vertices = stepVertexIndices
        for (i in vertices.indices) {
            if (i >= stepCount) break
            if (vertices[i] > closestIdx) return i
        }
        return (stepCount - 1).coerceAtLeast(0)
    }

    /**
     * The step the rider is currently being guided towards — the same step the
     * maneuver icon, the banner text and [NavigationProgress.distanceToNextManeuverMeters]
     * refer to.
     */
    fun getCurrentStep(): NavigationStep? {
        val steps = activeRoute?.steps ?: return null
        val idx = _progress.value.upcomingManeuverIndex
        return if (idx in steps.indices) steps[idx] else null
    }

    /**
     * Next required stop on the active route (origin→…→destination ordering):
     * the stop the rider must reach after completing [NavigationProgress.currentLegIndex]
     * legs. Null once the rider is on the final leg (guidance is arrival-bound).
     */
    fun getNextStop(): com.example.data.model.RouteStop? {
        val route = activeRoute ?: return null
        val nextIdx = _progress.value.currentLegIndex + 1
        return if (nextIdx <= route.stops.lastIndex && route.legs.size >= 2) {
            route.stops[nextIdx]
        } else {
            null
        }
    }

    /** The step AFTER the upcoming one (the banner's secondary "Then: …" line). */
    fun getNextStep(): NavigationStep? {
        val steps = activeRoute?.steps ?: return null
        val idx = _progress.value.upcomingManeuverIndex + 1
        return if (idx in steps.indices) steps[idx] else null
    }

    fun markManeuverAnnounced(maneuverIndex: Int) {
        if (maneuverIndex > _lastAnnouncedManeuverIndex.value) {
            _lastAnnouncedManeuverIndex.value = maneuverIndex
        }
    }

    fun reset() {
        activeRoute = null
        routeVersion++
        emaSpeedMps = 0.0
        hasArrivedAtDestination = false
        completedLegs = 0
        stepVertexIndices = emptyList()
        _lastAnnouncedManeuverIndex.value = -1
        _progress.value = NavigationProgress()
    }

    companion object {
        /**
         * Calculates a realistic motorcycle touring planned duration for a route.
         * Raw OSRM car routing assumes ideal highway conditions (80-100 km/h) without traffic/intersections.
         * For motorcycle touring on mixed urban/rural roads, realistic average speeds range 42--48 km/h (~11.6--13.3 m/s).
         */
        fun calculateRealisticPlannedDuration(distanceMeters: Double, rawDurationSeconds: Double): Double {
            if (distanceMeters <= 0.0) return 0.0
            val rawAvgSpeedMps = if (rawDurationSeconds > 0) distanceMeters / rawDurationSeconds else 12.5
            // Clamp baseline motorcycle speed profile between 30 km/h (8.33 m/s) and 48 km/h (13.33 m/s)
            val realisticSpeedMps = rawAvgSpeedMps.coerceIn(8.33, 13.33)
            val calculatedSec = distanceMeters / realisticSpeedMps
            return maxOf(rawDurationSeconds, calculatedSec)
        }
    }
}
