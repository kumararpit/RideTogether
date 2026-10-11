package com.example.data.map

import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

enum class MapCameraMode {
    FOLLOWING,
    FREE_EXPLORE,
    OVERVIEW
}

enum class OrientationMode {
    HEADING_UP,
    NORTH_UP
}

data class MapCameraState(
    val center: LatLng = LatLng(18.5204, 73.8567),
    val zoom: Int = 16,
    val heading: Float = 0f,
    val mode: MapCameraMode = MapCameraMode.FOLLOWING,
    val orientationMode: OrientationMode = OrientationMode.HEADING_UP,
    val bounds: Pair<LatLng, LatLng>? = null
) {
    val effectiveHeading: Float
        get() = if (orientationMode == OrientationMode.NORTH_UP) 0f else heading
}

class MapCameraController {

    private val _state = MutableStateFlow(MapCameraState())
    val state: StateFlow<MapCameraState> = _state.asStateFlow()

    fun setOrientationMode(mode: OrientationMode) {
        _state.value = _state.value.copy(orientationMode = mode)
    }

    fun toggleOrientationMode() {
        val newMode = if (_state.value.orientationMode == OrientationMode.HEADING_UP) {
            OrientationMode.NORTH_UP
        } else {
            OrientationMode.HEADING_UP
        }
        _state.value = _state.value.copy(orientationMode = newMode)
    }

    fun processLocation(location: LocationUpdate, distanceToNextManeuverMeters: Double? = null) {
        val currentState = _state.value
        if (currentState.mode != MapCameraMode.FOLLOWING) return

        val speedKmh = location.speedKmh
        val targetHeading = if (speedKmh >= 3.0 && location.headingDeg in 0f..360f) {
            location.headingDeg
        } else {
            currentState.heading
        }

        val smoothedHeading = interpolateHeading(currentState.heading, targetHeading, 0.25f)
        val targetZoom = calculateAdaptiveZoom(speedKmh, distanceToNextManeuverMeters)

        _state.value = currentState.copy(
            center = location.latLng,
            heading = smoothedHeading,
            zoom = targetZoom
        )
    }

    fun onUserGesture() {
        if (_state.value.mode != MapCameraMode.FREE_EXPLORE) {
            _state.value = _state.value.copy(mode = MapCameraMode.FREE_EXPLORE)
        }
    }

    fun recenter(riderLocation: LatLng?, headingDeg: Float? = null) {
        val newCenter = riderLocation ?: _state.value.center
        val newHeading = headingDeg?.takeIf { it in 0f..360f } ?: _state.value.heading
        _state.value = _state.value.copy(
            center = newCenter,
            zoom = 16,
            heading = newHeading,
            mode = MapCameraMode.FOLLOWING,
            bounds = null
        )
    }

    fun showOverview(points: List<LatLng>) {
        if (points.isEmpty()) return
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }

        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0

        val latDelta = maxLat - minLat
        val lngDelta = maxLng - minLng
        val maxDelta = max(latDelta, lngDelta)

        val calculatedZoom = when {
            maxDelta > 2.0 -> 8
            maxDelta > 1.0 -> 9
            maxDelta > 0.5 -> 10
            maxDelta > 0.2 -> 11
            maxDelta > 0.1 -> 12
            maxDelta > 0.05 -> 13
            maxDelta > 0.02 -> 14
            else -> 15
        }

        _state.value = _state.value.copy(
            center = LatLng(centerLat, centerLng),
            zoom = calculatedZoom,
            mode = MapCameraMode.OVERVIEW,
            bounds = Pair(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
        )
    }

    fun setZoom(newZoom: Int) {
        val clampedZoom = newZoom.coerceIn(3, 18)
        _state.value = _state.value.copy(zoom = clampedZoom, mode = MapCameraMode.FREE_EXPLORE)
    }

    companion object {
        fun calculateAdaptiveZoom(speedKmh: Double, distanceToNextManeuverMeters: Double? = null): Int {
            if (distanceToNextManeuverMeters != null && distanceToNextManeuverMeters in 0.0..150.0) {
                return 17
            }
            return when {
                speedKmh >= 70.0 -> 15
                speedKmh >= 30.0 -> 16
                else -> 17
            }
        }

        fun shortestPathAngleDelta(fromAngle: Float, toAngle: Float): Float {
            var diff = (toAngle - fromAngle) % 360f
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            return diff
        }

        fun interpolateHeading(current: Float, target: Float, factor: Float = 0.25f): Float {
            val delta = shortestPathAngleDelta(current, target)
            var newHeading = (current + delta * factor) % 360f
            if (newHeading < 0f) newHeading += 360f
            return newHeading
        }
    }
}
