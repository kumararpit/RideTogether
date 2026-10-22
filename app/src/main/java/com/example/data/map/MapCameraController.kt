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

    private var isUserZoomOverride: Boolean = false

    // Latest motion context for the adaptive recenter framing (D5 fix, INV-A).
    // Stored on EVERY location update — including non-FOLLOWING ticks that are
    // otherwise skipped — so recenter() can compute the current follow framing
    // even after the user has panned away and stopped feeding the camera.
    private var lastSpeedKmh: Double = 0.0
    private var lastDistanceToNextManeuverMeters: Double? = null

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
        lastSpeedKmh = location.speedKmh
        lastDistanceToNextManeuverMeters = distanceToNextManeuverMeters
        if (currentState.mode != MapCameraMode.FOLLOWING) return

        val speedKmh = location.speedKmh
        // Gate low-speed heading updates (< 5.4 km/h == 1.5 m/s)
        // Qualified Bearing: null heading means "no reliable bearing". It is
        // TREATED AS MISSING — the previous heading is preserved. Never fabricate
        // 0° (north) and never let non-finite/negative garbage rotate the map.
        val targetHeading = if (speedKmh >= 5.4) {
            location.headingDeg?.takeIf { it.isFinite() && it >= 0f }
                ?: currentState.heading
        } else {
            currentState.heading
        }

        // Target zoom preserves user zoom override or calculates adaptive zoom
        val targetZoom = if (isUserZoomOverride) {
            currentState.zoom
        } else {
            calculateAdaptiveZoom(speedKmh, distanceToNextManeuverMeters)
        }

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

    /**
     * Recenters the camera into FOLLOWING at the rider location.
     *
     * D5 fix (INV-A, Maps-consistent rule — see Phase 4 report, evidence E5):
     * entering FOLLOWING replaces the user zoom override with the follow
     * system's requested framing — [calculateAdaptiveZoom] over the LATEST
     * motion context — never a hardcoded zoom constant. The old
     * `zoom = 16f` write was the root cause of the recenter zoom-pop (the
     * target jumped instantly while zoom/center/anchor glided on mismatched
     * clocks). Boot/init may pin the framing exactly with [requestedZoom];
     * that stays a single controller write (INV-E).
     */
    fun recenter(riderLocation: LatLng?, headingDeg: Float? = null, requestedZoom: Float? = null) {
        val currentState = _state.value
        val newCenter = riderLocation ?: currentState.center
        val newHeading = headingDeg?.takeIf { it in 0f..360f } ?: currentState.heading
        val targetZoom = requestedZoom
            ?: calculateAdaptiveZoom(lastSpeedKmh, lastDistanceToNextManeuverMeters)
        isUserZoomOverride = false
        _state.value = currentState.copy(
            center = newCenter,
            zoom = targetZoom,
            heading = newHeading,
            mode = MapCameraMode.FOLLOWING,
            bounds = null
        )
    }

    /**
     * Fits the given points into the viewport.
     *
     * Phase B (backlog B-06): the padding is **chrome-aware** — the caller
     * passes the insets occupied by the maneuver banner (top) and the trip HUD
     * (bottom), so a fitted route is not hidden underneath them. The previous
     * uniform 100 px padding ignored the chrome entirely and could place the
     * riders or the route under the control column and the telemetry card.
     */
    fun showOverview(
        points: List<LatLng>,
        widthPx: Float = 1080f,
        heightPx: Float = 1920f,
        paddingPx: Float = 100f,
        topInsetPx: Float = 0f,
        bottomInsetPx: Float = 0f
    ) {
        if (points.isEmpty()) return

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }

        val usableWidthPx = (widthPx - paddingPx * 2).coerceAtLeast(100f) * 0.75f
        // Chrome-aware usable height: the space between the banner and the trip
        // HUD, minus the base padding on both sides.
        val usableHeightPx =
            (heightPx - paddingPx * 2 - topInsetPx - bottomInsetPx).coerceAtLeast(100f) * 0.75f

        val lonSpanTile = kotlin.math.abs(TileMath.lonToTileX(maxLng, 0f) - TileMath.lonToTileX(minLng, 0f))
        val latSpanTile = kotlin.math.abs(TileMath.latToTileY(minLat, 0f) - TileMath.latToTileY(maxLat, 0f))

        val zoomX = kotlin.math.log2(usableWidthPx / (lonSpanTile * 256.0)).toFloat()
        val zoomY = kotlin.math.log2(usableHeightPx / (latSpanTile * 256.0)).toFloat()
        
        val calculatedZoom = minOf(zoomX, zoomY).coerceIn(3f, 16.5f)

        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0

        isUserZoomOverride = false
        _state.value = _state.value.copy(
            center = LatLng(centerLat, centerLng),
            zoom = calculatedZoom,
            heading = 0f,
            mode = MapCameraMode.OVERVIEW,
            bounds = Pair(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
        )
    }

    /**
     * Centres the camera on a point the rider asked to see (a place they
     * tapped and want to look at).
     *
     * The camera leaves the follow mode: the rider is looking at a place,
     * not at the road ahead, so the follow camera must not pull the view
     * back to the rider.
     */
    fun centerOn(point: LatLng, zoom: Float = 16f) {
        isUserZoomOverride = true
        _state.value = _state.value.copy(
            center = point,
            zoom = zoom.coerceIn(3f, 18f),
            mode = MapCameraMode.FREE_EXPLORE,
            bounds = null
        )
    }

    fun setZoom(newZoom: Float) {
        val clampedZoom = newZoom.coerceIn(3f, 18f)
        isUserZoomOverride = true
        _state.value = _state.value.copy(zoom = clampedZoom)
    }

    /**
     * Pinch zoom outside FOLLOWING (FREE_EXPLORE / OVERVIEW): applies the new zoom
     * AND moves the center so the map point under the gesture centroid stays there
     * (center pre-computed from [ZoomAnchorMath.anchoredCenter] by the gesture layer).
     * Explicitly switches to FREE_EXPLORE. FOLLOWING-mode pinch must use [setZoom],
     * which never leaves follow mode.
     */
    fun setZoomFree(newZoom: Float, center: LatLng) {
        isUserZoomOverride = true
        _state.value = _state.value.copy(
            zoom = newZoom.coerceIn(3f, 18f),
            center = center,
            mode = MapCameraMode.FREE_EXPLORE,
            bounds = null
        )
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
