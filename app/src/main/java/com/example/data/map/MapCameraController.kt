package com.example.data.map

import com.example.data.location.BearingGate
import com.example.data.location.CourseHeadingStabilizer
import com.example.data.location.LocationUpdate
import com.example.data.model.HeadingEstimate
import com.example.data.model.HeadingPolicy
import com.example.data.model.HeadingSource
import com.example.data.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapCameraMode {
    FOLLOWING,
    FREE_EXPLORE,
    OVERVIEW,
    /** Inspecting a specific route section — camera locked to that segment. */
    INSPECT_ROUTE_SECTION,
    /** Inspecting a single point on the route — camera centred on that point. */
    INSPECT_POINT_ON_ROUTE
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
    val bounds: Pair<LatLng, LatLng>? = null,
    /**
     * False when no heading has qualified within [HeadingPolicy.MAX_HEADING_AGE_MS].
     * The [heading] value is still frozen at the last qualified direction — it is
     * never reset to 0° — but the UI can use this to avoid claiming a live heading.
     */
    val headingValid: Boolean = false,
    /** Which estimator produced [heading]. */
    val headingSource: HeadingSource = HeadingSource.UNAVAILABLE,
    /** Expected angular error of [heading] in degrees, or null when unknown. */
    val headingAccuracyDeg: Float? = null,
    /**
     * Camera tilt in degrees: 0 = straight down (top-down), larger = more oblique.
     * Google Maps evidence (InteractiveTiltUpImpl): navigation follow mode uses a
     * modest tilt (~30–45°) for a 3D perspective, while overview/north-up uses 0.
     * The UI applies this as a vertical foreshortening of the tile layer.
     */
    val tiltDegrees: Float = 0f,
    /**
     * Reported angular error of the heading source, in degrees. Null when the
     * source cannot report one. Mirrors Google Maps' headingErrorDegrees.
     */
    val headingErrorDeg: Float? = null,
    /**
     * Conservative (upper-bound) angular error of the heading source, in degrees.
     * Mirrors Google Maps' conservativeHeadingErrorDegrees — always >= headingErrorDeg
     * when both are present. Used by the UI to decide whether to show a "heading
     * uncertain" indicator instead of rotating the map.
     */
    val conservativeHeadingErrorDeg: Float? = null
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

    // Target-level heading damping for the GNSS cadence (1–4 s between fixes).
    private val headingStabilizer = CourseHeadingStabilizer()
    private var lastQualifiedHeadingMs: Long = 0L
    private var hasQualifiedHeading: Boolean = false

    /** Last fix whose course qualified; used to hand over to the compass. */
    private var lastCourseQualifiedMs: Long = 0L

    /** Timestamp of the most recent fix, used to age out stale speed context. */
    private var lastLocationMs: Long = 0L

    /** Last reported heading error from the source, in degrees (mirrors Google Maps' headingErrorDegrees). */
    private var lastHeadingErrorDeg: Float? = null
    /** Conservative upper-bound heading error, in degrees (mirrors Google Maps' conservativeHeadingErrorDegrees). */
    private var lastConservativeHeadingErrorDeg: Float? = null

    /**
     * Consumes one location fix.
     *
     * Heading policy (evidence: Google Maps 26.42.01):
     *  - a heading rotates the camera only when it passes [BearingGate]
     *    (3.0 m/s floor, optional activity motion, accuracy + 5 s freshness);
     *  - a rejected heading FREEZES the last qualified direction — it never
     *    falls back to 0° (north), which is what caused the old "map spins to
     *    north while stopped" behaviour;
     *  - after 5 s without a qualified heading, [MapCameraState.headingValid]
     *    goes false but the displayed heading stays frozen.
     */
    fun processLocation(
        location: LocationUpdate,
        distanceToNextManeuverMeters: Double? = null,
        nowMs: Long = location.timestampMs
    ) {
        val currentState = _state.value
        lastSpeedKmh = location.speedKmh
        lastDistanceToNextManeuverMeters = distanceToNextManeuverMeters
        lastLocationMs = nowMs
        if (currentState.mode != MapCameraMode.FOLLOWING) return

        val speedKmh = location.speedKmh
        val speedMps = (speedKmh / 3.6).toFloat()
        val ageMs = if (nowMs >= location.timestampMs) nowMs - location.timestampMs else 0L

        val qualified = BearingGate.qualifiesCourse(
            headingDeg = location.headingDeg,
            speedMps = speedMps,
            bearingAccuracyDeg = location.headingAccuracyDeg,
            speedAccuracyMps = location.speedAccuracyMps,
            activityMoving = location.activityMoving,
            ageMs = ageMs
        )

        var targetHeading = currentState.heading
        var headingValid = currentState.headingValid
        var headingSource = currentState.headingSource
        var headingAccuracy = currentState.headingAccuracyDeg

        val rawHeading = location.headingDeg
        if (qualified && rawHeading != null) {
            // A new qualified heading after a long gap is adopted directly (the
            // 60 fps render loop animates it); otherwise it is damped at fix rate.
            val gapMs = if (hasQualifiedHeading) nowMs - lastQualifiedHeadingMs else Long.MAX_VALUE
            if (gapMs > HeadingPolicy.MAX_HEADING_AGE_MS) headingStabilizer.reset()

            targetHeading = headingStabilizer.update(rawHeading)
            headingValid = true
            headingSource = location.headingSource
            headingAccuracy = location.headingAccuracyDeg
            // Track heading error from the source (Google Maps: headingErrorDegrees).
            lastHeadingErrorDeg = location.headingAccuracyDeg
            lastConservativeHeadingErrorDeg = location.headingAccuracyDeg?.let { acc ->
                acc * 1.5f // conservative bound: 1.5x the reported 1-sigma error
            }
            lastQualifiedHeadingMs = nowMs
            lastCourseQualifiedMs = nowMs
            hasQualifiedHeading = true
        } else {
            val expired = hasQualifiedHeading &&
                (nowMs - lastQualifiedHeadingMs) > HeadingPolicy.MAX_HEADING_AGE_MS
            if (expired) {
                headingValid = false
                headingStabilizer.reset()
            }
            // Freeze: targetHeading stays at the last qualified direction.
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
            zoom = targetZoom,
            headingValid = headingValid,
            headingSource = headingSource,
            headingAccuracyDeg = headingAccuracy,
            headingErrorDeg = lastHeadingErrorDeg,
            conservativeHeadingErrorDeg = lastConservativeHeadingErrorDeg,
            tiltDegrees = DEFAULT_FOLLOW_TILT_DEGREES
        )
    }

    /**
     * Feeds a *device orientation* estimate (compass).
     *
     * Google Maps keeps this strictly separate from travel direction. It is used
     * here only as a slow fallback, and only when **all** hold:
     *
     *  - the camera is in FOLLOWING;
     *  - the estimate is a valid, fresh COMPASS reading within
     *    [HeadingPolicy.MAX_COMPASS_ACCURACY_DEG];
     *  - the rider is actually moving (>= [HeadingPolicy.MIN_ACTIVITY_MOVING_SPEED_MPS]),
     *    so the map can never spin while parked in traffic;
     *  - no course has qualified for [HeadingPolicy.COMPASS_TAKEOVER_DELAY_MS],
     *    so a momentary magnetic disturbance cannot outrank a live course.
     */
    fun setDeviceHeading(estimate: HeadingEstimate?, nowMs: Long = System.currentTimeMillis()) {
        if (estimate == null) return
        if (estimate.source != HeadingSource.COMPASS) return

        val currentState = _state.value
        if (currentState.mode != MapCameraMode.FOLLOWING) return
        if (!estimate.isValid) return
        if (!estimate.isFresh(nowMs, HeadingPolicy.MAX_COMPASS_AGE_MS)) return

        val accuracy = estimate.accuracyDeg
        if (accuracy != null &&
            (!accuracy.isFinite() || accuracy > HeadingPolicy.MAX_COMPASS_ACCURACY_DEG)
        ) {
            return
        }

        val speedMps = (lastSpeedKmh / 3.6).toFloat()
        if (!speedMps.isFinite() || speedMps < HeadingPolicy.MIN_ACTIVITY_MOVING_SPEED_MPS) return

        // The speed input must itself be recent: during a GPS outage a stale
        // "moving" speed must not let a parked bike spin the map.
        if (lastLocationMs <= 0L ||
            nowMs - lastLocationMs > HeadingPolicy.COMPASS_MAX_FIX_AGE_MS
        ) {
            return
        }

        if (hasQualifiedHeading &&
            nowMs - lastCourseQualifiedMs < HeadingPolicy.COMPASS_TAKEOVER_DELAY_MS
        ) {
            return
        }

        val targetHeading = headingStabilizer.update(estimate.degrees)
        lastQualifiedHeadingMs = nowMs
        hasQualifiedHeading = true
        // Track compass heading error (Google Maps: conservativeHeadingErrorDegrees).
        lastHeadingErrorDeg = accuracy
        lastConservativeHeadingErrorDeg = accuracy?.let { acc -> acc * 1.5f }

        _state.value = currentState.copy(
            heading = targetHeading,
            headingValid = true,
            headingSource = HeadingSource.COMPASS,
            headingAccuracyDeg = accuracy,
            headingErrorDeg = lastHeadingErrorDeg,
            conservativeHeadingErrorDeg = lastConservativeHeadingErrorDeg,
            tiltDegrees = DEFAULT_FOLLOW_TILT_DEGREES
        )
    }

    /** Sets the camera tilt (0 = straight down, up to [MAX_TILT_DEGREES]). */
    fun setTilt(degrees: Float) {
        val clamped = degrees.coerceIn(0f, MAX_TILT_DEGREES)
        _state.value = _state.value.copy(tiltDegrees = clamped)
    }

    /** Adjusts the camera tilt by [deltaDeg] degrees, clamped to [0, MAX_TILT_DEGREES]. */
    fun adjustTilt(deltaDeg: Float) {
        val current = _state.value.tiltDegrees
        setTilt(current + deltaDeg)
    }

    /**
     * Enters INSPECT_ROUTE_SECTION mode: the camera locks onto a route section,
     * with a reduced tilt so the section is viewed from a higher, flatter angle.
     * The rider can still pan/zoom within this mode; tapping recenter returns to
     * FOLLOWING.
     */
    fun enterInspectRouteSection(center: LatLng, zoom: Float = 16f) {
        _state.value = _state.value.copy(
            center = center,
            zoom = zoom.coerceIn(3f, 18f),
            heading = 0f,
            mode = MapCameraMode.INSPECT_ROUTE_SECTION,
            tiltDegrees = 15f,
            bounds = null
        )
    }

    /**
     * Enters INSPECT_POINT_ON_ROUTE mode: the camera centres on a single point
     * on the route, with minimal tilt for a clear top-down view of that point.
     */
    fun enterInspectPointOnRoute(point: LatLng, zoom: Float = 17f) {
        _state.value = _state.value.copy(
            center = point,
            zoom = zoom.coerceIn(3f, 18f),
            heading = 0f,
            mode = MapCameraMode.INSPECT_POINT_ON_ROUTE,
            tiltDegrees = 5f,
            bounds = null
        )
    }

    fun onUserGesture() {
        if (_state.value.mode != MapCameraMode.FREE_EXPLORE) {
            _state.value = _state.value.copy(mode = MapCameraMode.FREE_EXPLORE, tiltDegrees = 0f)
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
            mode = MapCameraMode.FREE_EXPLORE,
            tiltDegrees = 0f
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
        val acceptedHeading = headingDeg?.takeIf { it.isFinite() && it in 0f..360f }
        val newHeading = acceptedHeading ?: currentState.heading
        val targetZoom = requestedZoom
            ?: calculateAdaptiveZoom(lastSpeedKmh, lastDistanceToNextManeuverMeters)
        isUserZoomOverride = false

        if (acceptedHeading != null) {
            headingStabilizer.reset()
            headingStabilizer.update(acceptedHeading)
            lastQualifiedHeadingMs = System.currentTimeMillis()
            hasQualifiedHeading = true
        }

        _state.value = currentState.copy(
            center = newCenter,
            zoom = targetZoom,
            heading = newHeading,
            mode = MapCameraMode.FOLLOWING,
            tiltDegrees = DEFAULT_FOLLOW_TILT_DEGREES,
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
            tiltDegrees = 0f,
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
            tiltDegrees = 0f,
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
            tiltDegrees = 0f,
            bounds = null
        )
    }

    companion object {

        /** Maximum tilt the camera supports (degrees, 0 = straight down). */
        const val MAX_TILT_DEGREES: Float = 60f
        /** Default tilt for navigation follow mode (degrees) — Google Maps uses ~30–45°. */
        const val DEFAULT_FOLLOW_TILT_DEGREES: Float = 35f
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
