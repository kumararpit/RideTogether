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
    val currentManeuverIndex: Int = 0,
    val distanceToNextManeuverMeters: Double = 0.0,
    val remainingRouteDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val isOffRoute: Boolean = false,
    val routeVersion: Int = 1,
    val isArrived: Boolean = false,
    val isStaleGps: Boolean = false,
    val emaSpeedKmh: Double = 0.0
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

        val initialNextStepDist = if (steps.isNotEmpty() && stepVertexIndices.size > 1) {
            val nextVertex = stepVertexIndices.getOrElse(1) { points.size - 1 }
            RouteGeometry.remainingDistanceMeters(0, points.take(nextVertex + 1))
        } else {
            adjustedRoute.totalDistanceMeters
        }

        _progress.value = NavigationProgress(
            currentSegmentIndex = 0,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = initialNextStepDist,
            remainingRouteDistanceMeters = adjustedRoute.totalDistanceMeters,
            remainingDurationSeconds = adjustedRoute.totalDurationSeconds,
            isOffRoute = false,
            routeVersion = routeVersion,
            isArrived = false,
            isStaleGps = false,
            emaSpeedKmh = 0.0
        )
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

        // 3. Maneuver Advancement & Dynamic Distance to Next Maneuver
        val steps = route.steps
        var currentManeuverIdx = prevProgress.currentManeuverIndex

        if (steps.isNotEmpty() && stepVertexIndices.size == steps.size) {
            // Advance maneuver index when rider reaches or passes the vertex threshold for the next step
            while (currentManeuverIdx < steps.size - 1) {
                val nextManeuverVertex = stepVertexIndices[currentManeuverIdx + 1]
                val distToNextManeuver = userLoc.distanceTo(points[nextManeuverVertex]) * 1000.0
                if (closestIdx >= nextManeuverVertex || distToNextManeuver <= 25.0) {
                    currentManeuverIdx++
                } else {
                    break
                }
            }
        }

        // Calculate dynamic distance along polyline to the next maneuver vertex
        val targetVertexIdx = if (stepVertexIndices.isNotEmpty() && currentManeuverIdx + 1 < stepVertexIndices.size) {
            stepVertexIndices[currentManeuverIdx + 1]
        } else {
            points.size - 1
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
            distanceToNextManeuverMeters = nextStepMeters,
            remainingRouteDistanceMeters = remDistMeters,
            remainingDurationSeconds = remDurationSec,
            isOffRoute = false,
            routeVersion = routeVersion,
            isArrived = isArrived,
            isStaleGps = !isAccuracyReliable,
            emaSpeedKmh = (emaSpeedMps * 3600.0) / 1000.0
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

    fun getCurrentStep(): NavigationStep? {
        val steps = activeRoute?.steps ?: return null
        val idx = _progress.value.currentManeuverIndex
        return if (idx in steps.indices) steps[idx] else null
    }

    fun getNextStep(): NavigationStep? {
        val steps = activeRoute?.steps ?: return null
        val idx = _progress.value.currentManeuverIndex + 1
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
