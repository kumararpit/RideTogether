package com.example.data.location

import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NavigationProgress(
    val currentSegmentIndex: Int = 0,
    val currentManeuverIndex: Int = 0,
    val distanceToNextManeuverMeters: Double = 0.0,
    val remainingRouteDistanceMeters: Double = 0.0,
    val remainingDurationSeconds: Double = 0.0,
    val isOffRoute: Boolean = false,
    val routeVersion: Int = 1,
    val isArrived: Boolean = false
)

class NavEngine {

    private var activeRoute: RouteResult? = null
    private var routeVersion = 1

    private val _progress = MutableStateFlow(NavigationProgress())
    val progress: StateFlow<NavigationProgress> = _progress.asStateFlow()

    private val _lastAnnouncedManeuverIndex = MutableStateFlow(-1)
    val lastAnnouncedManeuverIndex: StateFlow<Int> = _lastAnnouncedManeuverIndex.asStateFlow()

    fun setRoute(route: RouteResult) {
        activeRoute = route
        routeVersion++
        _lastAnnouncedManeuverIndex.value = -1
        _progress.value = NavigationProgress(
            currentSegmentIndex = 0,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = route.steps.firstOrNull()?.distanceMeters ?: 0.0,
            remainingRouteDistanceMeters = route.totalDistanceMeters,
            remainingDurationSeconds = route.totalDurationSeconds,
            isOffRoute = false,
            routeVersion = routeVersion,
            isArrived = false
        )
    }

    fun processLocation(location: LocationUpdate): NavigationProgress {
        val route = activeRoute ?: return _progress.value
        val points = route.coordinates
        if (points.isEmpty()) return _progress.value

        val userLoc = location.latLng
        val closestIdx = RouteGeometry.findClosestPointIndex(userLoc, points)
        val distToPolylineM = RouteGeometry.distanceToPolylineMeters(userLoc, points)

        // Ignore fixes with poor accuracy (> 30m) for off-route evaluation
        val isAccuracyReliable = location.accuracyMeters <= 30f
        val isOffRoute = isAccuracyReliable && distToPolylineM > 120.0

        val remDistMeters = RouteGeometry.remainingDistanceMeters(closestIdx, points)

        // Calculate remaining duration
        val effectiveSpeedMps = if (location.speedKmh > 10.0) {
            (location.speedKmh * 1000.0) / 3600.0
        } else {
            15.0 // ~54 km/h default
        }
        val remDurationSec = if (effectiveSpeedMps > 0) remDistMeters / effectiveSpeedMps else 0.0

        // Determine step index based on accumulated traversed distance along route
        val steps = route.steps
        var currentManeuverIdx = _progress.value.currentManeuverIndex
        if (steps.isNotEmpty()) {
            val totalMeters = route.totalDistanceMeters
            val traversedMeters = (totalMeters - remDistMeters).coerceAtLeast(0.0)
            var accumulated = 0.0
            for (i in steps.indices) {
                accumulated += steps[i].distanceMeters
                if (accumulated >= traversedMeters) {
                    currentManeuverIdx = i
                    break
                }
            }
        }

        val nextStepMeters = if (steps.isNotEmpty() && currentManeuverIdx < steps.size) {
            val step = steps[currentManeuverIdx]
            val totalBefore = steps.take(currentManeuverIdx).sumOf { it.distanceMeters }
            val traversedInStep = ((route.totalDistanceMeters - remDistMeters) - totalBefore).coerceAtLeast(0.0)
            (step.distanceMeters - traversedInStep).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val isArrived = remDistMeters <= 30.0 || (steps.isNotEmpty() && currentManeuverIdx == steps.size - 1 && nextStepMeters <= 20.0)

        val newProgress = NavigationProgress(
            currentSegmentIndex = closestIdx,
            currentManeuverIndex = currentManeuverIdx.coerceIn(0, (steps.size - 1).coerceAtLeast(0)),
            distanceToNextManeuverMeters = nextStepMeters,
            remainingRouteDistanceMeters = remDistMeters,
            remainingDurationSeconds = remDurationSec,
            isOffRoute = isOffRoute,
            routeVersion = routeVersion,
            isArrived = isArrived
        )

        _progress.value = newProgress
        return newProgress
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
        _lastAnnouncedManeuverIndex.value = -1
        _progress.value = NavigationProgress()
    }
}
