package com.example.data.map

import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapCameraMode {
    FOLLOWING,
    FREE_EXPLORE,
    OVERVIEW
}

data class MapCameraState(
    val center: LatLng = LatLng(18.5204, 73.8567),
    val zoom: Int = 16,
    val heading: Float = 0f,
    val mode: MapCameraMode = MapCameraMode.FOLLOWING
)

class MapCameraController {

    private val _state = MutableStateFlow(MapCameraState())
    val state: StateFlow<MapCameraState> = _state.asStateFlow()

    fun processLocation(location: LocationUpdate) {
        val currentState = _state.value
        if (currentState.mode != MapCameraMode.FOLLOWING) return

        val speedKmh = location.speedKmh
        val targetHeading = if (speedKmh >= 3.0 && location.headingDeg in 0f..360f) {
            location.headingDeg
        } else {
            currentState.heading
        }

        val smoothedHeading = interpolateHeading(currentState.heading, targetHeading, 0.25f)

        _state.value = currentState.copy(
            center = location.latLng,
            heading = smoothedHeading
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
        _state.value = MapCameraState(
            center = newCenter,
            zoom = 16,
            heading = newHeading,
            mode = MapCameraMode.FOLLOWING
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

        _state.value = MapCameraState(
            center = LatLng(centerLat, centerLng),
            zoom = 12,
            heading = 0f,
            mode = MapCameraMode.OVERVIEW
        )
    }

    fun setZoom(newZoom: Int) {
        val clampedZoom = newZoom.coerceIn(3, 18)
        _state.value = _state.value.copy(zoom = clampedZoom)
    }

    companion object {
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
