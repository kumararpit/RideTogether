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

enum class OrientationMode {
    HEADING_UP,
    NORTH_UP
}

data class MapCameraState(
    val center: LatLng = LatLng(18.5204, 73.8567),
    val zoom: Float = 16f,
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
        // Gate low-speed heading updates (< 5.4 km/h == 1.5 m/s)
        val targetHeading = if (speedKmh >= 5.4 && location.headingDeg in 0f..360f) {
            location.headingDeg
        } else {
            currentState.heading
        }

        // We don't interpolate heading here anymore. We set the raw target heading.
        // The display layer's frame loop will handle interpolation.
        val targetZoom = calculateAdaptiveZoom(speedKmh, distanceToNextManeuverMeters)

        _state.value = currentState.copy(
            center = location.latLng,
            heading = targetHeading,
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
            zoom = 16f,
            heading = newHeading,
            mode = MapCameraMode.FOLLOWING,
            bounds = null
        )
    }

    fun showOverview(
        points: List<LatLng>, 
        widthPx: Float = 1080f, 
        heightPx: Float = 1920f,
        paddingPx: Float = 100f
    ) {
        if (points.isEmpty()) return

        val usableWidth = (widthPx - paddingPx * 2).coerceAtLeast(100f)
        val usableHeight = (heightPx - paddingPx * 2).coerceAtLeast(100f)

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }

        // Use zoom 0 to get the tile coordinates (which range from 0 to 1)
        val minX = points.minOf { TileMath.lonToTileX(it.longitude, 0f) }
        val maxX = points.maxOf { TileMath.lonToTileX(it.longitude, 0f) }
        val minY = points.minOf { TileMath.latToTileY(it.latitude, 0f) }
        val maxY = points.maxOf { TileMath.latToTileY(it.latitude, 0f) }

        val dx = (maxX - minX).coerceAtLeast(0.000001)
        val dy = (maxY - minY).coerceAtLeast(0.000001)

        val zoomX = kotlin.math.log2(usableWidth / (dx * 256.0))
        val zoomY = kotlin.math.log2(usableHeight / (dy * 256.0))
        
        val calculatedZoom = minOf(zoomX, zoomY).toFloat().coerceIn(3f, 18f)

        val centerLat = TileMath.tileYToLat((minY + maxY) / 2.0, 0f)
        val centerLng = TileMath.tileXToLon((minX + maxX) / 2.0, 0f)

        _state.value = _state.value.copy(
            center = LatLng(centerLat, centerLng),
            zoom = calculatedZoom,
            mode = MapCameraMode.OVERVIEW,
            bounds = Pair(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
        )
    }

    fun setZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(3f, 18f)
        _state.value = _state.value.copy(zoom = clampedZoom, mode = MapCameraMode.FREE_EXPLORE)
    }

    companion object {
        fun calculateAdaptiveZoom(speedKmh: Double, distanceToNextManeuverMeters: Double? = null): Float {
            if (distanceToNextManeuverMeters != null && distanceToNextManeuverMeters in 0.0..150.0) {
                return 17.5f
            }
            return when {
                speedKmh >= 70.0 -> 15.5f
                speedKmh >= 30.0 -> 16.5f
                else -> 17.5f
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
