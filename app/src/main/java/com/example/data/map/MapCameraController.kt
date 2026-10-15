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
    
    fun pan(unrotatedDx: Float, unrotatedDy: Float, displayZoom: Float, displayHeading: Float, tileSizePx: Float) {
        val currentState = _state.value
        
        val rad = Math.toRadians(-displayHeading.toDouble())
        val cosH = kotlin.math.cos(rad).toFloat()
        val sinH = kotlin.math.sin(rad).toFloat()

        val dx = unrotatedDx * cosH - unrotatedDy * sinH
        val dy = unrotatedDx * sinH + unrotatedDy * cosH

        val tileXDelta = dx / tileSizePx
        val tileYDelta = dy / tileSizePx
        
        val currentTileX = TileMath.lonToTileX(currentState.center.longitude, displayZoom)
        val currentTileY = TileMath.latToTileY(currentState.center.latitude, displayZoom)
        
        val newLng = TileMath.tileXToLon(currentTileX - tileXDelta, displayZoom).toFloat()
        val newLat = TileMath.tileYToLat(currentTileY - tileYDelta, displayZoom).toFloat()
        
        _state.value = currentState.copy(
            center = LatLng(newLat.toDouble(), newLng.toDouble()),
            mode = MapCameraMode.FREE_EXPLORE
        )
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

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }

        val usableWidthPx = (widthPx - paddingPx * 2).coerceAtLeast(100f) * 0.75f
        val usableHeightPx = (heightPx - paddingPx * 2).coerceAtLeast(100f) * 0.75f

        val lonSpanTile = kotlin.math.abs(TileMath.lonToTileX(maxLng, 0f) - TileMath.lonToTileX(minLng, 0f))
        val latSpanTile = kotlin.math.abs(TileMath.latToTileY(minLat, 0f) - TileMath.latToTileY(maxLat, 0f))

        val zoomX = kotlin.math.log2(usableWidthPx / (lonSpanTile * 256.0)).toFloat()
        val zoomY = kotlin.math.log2(usableHeightPx / (latSpanTile * 256.0)).toFloat()
        
        val calculatedZoom = minOf(zoomX, zoomY).coerceIn(3f, 16.5f)

        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0

        _state.value = _state.value.copy(
            center = LatLng(centerLat, centerLng),
            zoom = calculatedZoom,
            heading = 0f,
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
