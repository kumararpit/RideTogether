package com.example.data.map

import com.example.data.model.HeadingPolicy
import com.example.data.model.LatLng

/**
 * Pure math helpers for frame-paced rider marker smoothing. All units follow the
 * project conventions: geographic coordinates in degrees (latitude/longitude),
 * bearing in degrees (0..360 compass convention), distances in meters.
 *
 * Time constants are exponential-damping (1st order low-pass) time constants in
 * seconds. The smoothing factor is derived from the *elapsed* frame time so the
 * rendered glide behaves identically at 60 Hz and 120 Hz displays.
 */
object MarkerSmoothingMath {

    /** Position damping time constant in seconds (spec: 150 ms). */
    const val POSITION_TAU_SECONDS = 0.15f

    /** Bearing damping time constant in seconds (spec: 100 ms). */
    const val BEARING_TAU_SECONDS = 0.10f

    /**
     * Geographic discontinuity guard: a rendered-target distance larger than this
     * value is treated as a fresh GPS lock / large correction / deliberate recenter
     * and snaps directly instead of gliding across the map.
     */
    const val TELEPORT_THRESHOLD_METERS = 150.0

    /**
     * Exponential smoothing factor for [dtSeconds] elapsed with a [tauSeconds] time
     * constant. Returns 0 for dt <= 0 (no movement), approaches 1 as dt approaches
     * tau/10+. Frame-rate independent: identical elapsed time yields an identical
     * factor regardless of how many frames were rendered.
     */
    fun smoothingAlpha(dtSeconds: Double, tauSeconds: Float): Float {
        if (dtSeconds <= 0.0) return 0f
        require(tauSeconds > 0f) { "tauSeconds must be positive" }
        val alpha = 1.0 - kotlin.math.exp(-dtSeconds / tauSeconds.toDouble())
        return alpha.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Shortest signed angular difference from [currentDeg] to [targetDeg] in degrees,
     * normalized to the interval (-180..180]. Handles north-wrapping transitions such
     * as 359° -> 1° (result +2°) and 1° -> 359° (result -2°).
     */
    fun shortestAngleDeltaDeg(currentDeg: Float, targetDeg: Float): Float {
        return ((targetDeg - currentDeg + 540f) % 360f) - 180f
    }

    /**
     * Normalizes any bearing-like scalar into the compass convention 0..360.
     * Also safely normalizes negative and > 360 degree inputs.
     */
    fun normalizeBearingDeg(rawDeg: Double): Double {
        if (!rawDeg.isFinite()) return 0.0
        val normalized = rawDeg % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    /**
     * One exponential-damping interpolation step for a scalar value toward a target.
     */
    fun stepScalar(current: Double, target: Double, alpha: Float): Double {
        return current + alpha * (target - current)
    }

    /**
     * One shortest-arc exponential-damping step for a bearing, wrapped back into 0..360.
     */
    fun stepBearingDeg(currentDeg: Double, targetDeg: Double, alpha: Float): Double {
        val delta = shortestAngleDeltaDeg(
            currentDeg = currentDeg.toFloat(),
            targetDeg = targetDeg.toFloat()
        )
        val next = currentDeg + alpha * delta
        return normalizeBearingDeg(next)
    }

    /**
     * True when the coordinate pair is finite and within valid geographic bounds
     * (used by the animator to reject invalid / corrupted GPS updates so they can
     * never move the rendered marker).
     */
    fun isValidGeoCoordinate(lat: Double, lng: Double): Boolean {
        return lat.isFinite() && lng.isFinite() &&
            lat >= -90.0 && lat <= 90.0 &&
            lng >= -180.0 && lng <= 180.0
    }

    /** Mean Earth radius in meters (IUGG mean radius, spherical great-circle model). */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Great-circle projection of a start point along a constant bearing for a
     * straight distance (the dead-reckoning predictor). Returns null for
     * degenerate inputs (invalid coordinates, non-finite/zero-wide bearing,
     * negative distance) so callers can keep the previous state instead of
     * producing a corrupt target.
     */
    fun positionAlongBearing(
        startLat: Double,
        startLng: Double,
        bearingDeg: Double,
        distanceMeters: Double
    ): LatLng? {
        if (!isValidGeoCoordinate(startLat, startLng)) return null
        if (!bearingDeg.isFinite() || bearingDeg < 0.0 || bearingDeg >= 360.0) return null
        if (!distanceMeters.isFinite() || distanceMeters < 0.0) return null

        val delta = distanceMeters / EARTH_RADIUS_METERS
        val theta = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(startLat)
        val lng1 = Math.toRadians(startLng)

        val lat2 = kotlin.math.asin(
            kotlin.math.sin(lat1) * kotlin.math.cos(delta) +
                kotlin.math.cos(lat1) * kotlin.math.sin(delta) * kotlin.math.cos(theta)
        )
        val lng2 = lng1 + kotlin.math.atan2(
            kotlin.math.sin(theta) * kotlin.math.sin(delta) * kotlin.math.cos(lat1),
            kotlin.math.cos(delta) - kotlin.math.sin(lat1) * kotlin.math.sin(lat2)
        )

        val latDeg = Math.toDegrees(lat2)
        // Normalize longitude into (-180..180] using the bearing normalizer.
        val lngDeg = normalizeBearingDeg(Math.toDegrees(lng2) + 180.0) - 180.0
        return LatLng(latDeg, lngDeg)
    }
}

/**
 * Per-rider smoothed marker state. Targets are updated independently of frame
 * timing (each validated GPS/members flow emission); rendered values are advanced
 * exclusively by [advance] from the single frame-paced animation loop.
 */
class RiderMarkerAnimator {

    data class Target(
        val lat: Double,
        val lng: Double,
        val bearingDeg: Double
    )

    private class RiderAnim {
        var hasRendered: Boolean = false
        var renderedLat: Double = 0.0
        var renderedLng: Double = 0.0
        var renderedBearingDeg: Double = 0.0
        var target: Target? = null
        var deadReckoning: DeadReckoning? = null
    }

    /**
     * Dead-reckoning prediction state anchored to the last qualified fix
     * (visual-only); null when the fix did not meet the eligibility bounds.
     */
    private class DeadReckoning(
        val anchorLat: Double,
        val anchorLng: Double,
        val bearingDeg: Double,
        val speedKmh: Double,
        var ageSec: Double
    )

    private val riders = HashMap<String, RiderAnim>()

    companion object {
        /**
         * Bounded dead reckoning (visual-only marker prediction between fixes):
         * the anchor point moves along the qualified bearing/speed for at most
         * this duration past the last fix, then the prediction HOLDS (never
         * keeps sailing). With a 4 s fix cadence this covers ~3/4 of the gap.
         */
        const val DEAD_RECKONING_MAX_DURATION_SEC = 3.0

        /** Speed gate: below this the fix is too noisy to drive prediction. */
        const val DEAD_RECKONING_MIN_SPEED_KMH = 5.4

        /** Accuracy gate: fixes worse than this never drive prediction. */
        const val DEAD_RECKONING_MAX_ACCURACY_METERS = 20.0f

        /**
         * Teleport-accept threshold: when a new fix lands farther than this from
         * the predicted position, accept it instantly (rendered marker snaps,
         * no glide, prediction canceled) — GPS jump / route-change protection.
         */
        const val DEAD_RECKONING_TELEPORT_METERS = 50.0
    }

    /** Monotonically increasing frame counter; bumped once per [advance] frame. */
    var frameVersion: Int = 0
        private set

    /**
     * Publishes a new validated target for a rider. Called from composition/GPS flow
     * emissions (main thread); does not animate by itself.
     * The first valid target snaps the rendered state directly onto the target —
     * no origin-to-target glide, no animation from (0,0).
     *
     * Bounded dead reckoning (visual-only): when [speedKmh] and [accuracyMeters]
     * accompany the fix and all gates pass (speed >= 5.4 km/h, accuracy <= 20 m,
     * qualified bearing available), the target is extrapolated forward frame-by-
     * frame from this fix between [advance] calls, bounded to 3.0 s. A new fix
     * farther than 50 m from the prediction is teleported-accepted instantly.
     * Prediction NEVER leaves the render layer — camera, NavEngine, and telemetry
     * always consume measured fixes only.
     */
    fun updateTarget(
        riderId: String,
        lat: Double,
        lng: Double,
        bearingDeg: Double?,
        speedKmh: Double? = null,
        accuracyMeters: Float? = null,
        headingAccuracyDeg: Float? = null
    ) {
        if (!MarkerSmoothingMath.isValidGeoCoordinate(lat, lng)) return
        val anim = riders.getOrPut(riderId) { RiderAnim() }
        // Qualified Bearing: null or explicitly inaccurate bearing means "unknown
        // this fix" — keep the previous target bearing instead of gliding to a
        // fabricated value.
        val qualifiedBearing = bearingDeg?.takeIf {
            headingAccuracyDeg == null ||
                (headingAccuracyDeg.isFinite() &&
                    headingAccuracyDeg <= HeadingPolicy.MAX_BEARING_ACCURACY_DEG)
        }
        val resolvedBearing = qualifiedBearing
            ?.let { MarkerSmoothingMath.normalizeBearingDeg(it) }
            ?: anim.target?.bearingDeg
            ?: 0.0

        // Teleport-accept: compare the NEW fix against the PREDICTED position of the
        // still-active DR run before overwriting anything. A big jump means the
        // prediction (or the fix) diverged — accept the fix instantly, no glide.
        if (anim.deadReckoning != null && anim.target != null) {
            val jumpMeters = LatLng(anim.target!!.lat, anim.target!!.lng)
                .distanceTo(LatLng(lat, lng)) * 1000.0
            if (jumpMeters > DEAD_RECKONING_TELEPORT_METERS) {
                anim.renderedLat = lat
                anim.renderedLng = lng
                anim.renderedBearingDeg = resolvedBearing
                anim.hasRendered = true
            }
        }

        // DR eligibility per the bounds spec: moving fast enough, fine enough fix,
        // and a qualified bearing. Missing bearing/speed or coarse accuracy -> no
        // prediction (target stays put between fixes, like the legacy behavior).
        anim.deadReckoning = if (
            qualifiedBearing != null &&
            speedKmh != null && speedKmh >= DEAD_RECKONING_MIN_SPEED_KMH &&
            (accuracyMeters == null || accuracyMeters <= DEAD_RECKONING_MAX_ACCURACY_METERS)
        ) {
            DeadReckoning(
                anchorLat = lat,
                anchorLng = lng,
                bearingDeg = resolvedBearing,
                speedKmh = speedKmh,
                ageSec = 0.0
            )
        } else {
            null
        }

        val target = Target(
            lat = lat,
            lng = lng,
            bearingDeg = resolvedBearing
        )
        anim.target = target
        if (!anim.hasRendered) {
            // First valid location: initialize rendered state directly on the target.
            anim.renderedLat = target.lat
            anim.renderedLng = target.lng
            anim.renderedBearingDeg = target.bearingDeg
            anim.hasRendered = true
        }
    }

    /**
     * Immediately aligns the rendered marker with its current target. Used on
     * deliberate recenter so the rider/camera alignment is restored instantly
     * (bypassing the normal 150 ms glide). Returns true when a target existed.
     */
    fun snap(riderId: String): Boolean {
        val anim = riders[riderId] ?: return false
        val target = anim.target ?: return false
        anim.renderedLat = target.lat
        anim.renderedLng = target.lng
        anim.renderedBearingDeg = target.bearingDeg
        if (!anim.hasRendered) anim.hasRendered = true
        return true
    }

    /** Drops animation state for riders no longer present in the members list. */
    fun retain(retainedRiderIds: Collection<String>) {
        riders.keys.retainAll(retainedRiderIds.toHashSet())
    }

    fun targetCount(): Int = riders.size

    /**
     * Advance the rendered marker state by [dtSeconds]. Must be called once per
     * animation frame from the single frame-paced loop. Zero/negative elapsed time
     * is a no-op. Renderer reads the values via [renderedLat]/[renderedLng]/
     * [renderedBearing]; a null position means "no valid target yet — render raw".
     */
    fun advance(dtSeconds: Double) {
        if (dtSeconds <= 0.0) return

        for (anim in riders.values) {
            // Bounded dead-reckoning prediction: move the TARGET forward from the
            // last qualified fix along the qualified bearing/speed. Age accumulates
            // per frame past the fix; beyond the bound the prediction holds (t is
            // capped, so the target stops advancing — it never keeps sailing).
            anim.deadReckoning?.let { dr ->
                dr.ageSec += dtSeconds
                val t = dr.ageSec.coerceAtMost(DEAD_RECKONING_MAX_DURATION_SEC)
                val distanceMeters = dr.speedKmh / 3.6 * t
                MarkerSmoothingMath.positionAlongBearing(
                    startLat = dr.anchorLat,
                    startLng = dr.anchorLng,
                    bearingDeg = dr.bearingDeg,
                    distanceMeters = distanceMeters
                )?.let { predicted ->
                    anim.target = Target(
                        lat = predicted.latitude,
                        lng = predicted.longitude,
                        bearingDeg = dr.bearingDeg
                    )
                }
            }

            val target = anim.target ?: continue
            if (!anim.hasRendered) {
                // Defensive: updateTarget already initializes on first valid target.
                anim.renderedLat = target.lat
                anim.renderedLng = target.lng
                anim.renderedBearingDeg = target.bearingDeg
                anim.hasRendered = true
                continue
            }

            // Longitude discontinuity handling: interpolate toward the target longitude
            // that lies within +/-180 degrees of the rendered longitude (antimeridian safe).
            val wrappedTargetLng = wrappedTargetLongitude(anim.renderedLng, target.lng)

            // Teleport / recenter guard: large geographic discontinuity snaps directly.
            val distanceMeters = LatLng(anim.renderedLat, anim.renderedLng)
                .distanceTo(LatLng(target.lat, wrappedTargetLng)) * 1000.0

            if (distanceMeters > MarkerSmoothingMath.TELEPORT_THRESHOLD_METERS) {
                anim.renderedLat = target.lat
                anim.renderedLng = target.lng
                anim.renderedBearingDeg = target.bearingDeg
                continue
            }

            val alphaPos = MarkerSmoothingMath.smoothingAlpha(
                dtSeconds, MarkerSmoothingMath.POSITION_TAU_SECONDS
            )
            if (alphaPos > 0f) {
                anim.renderedLat = MarkerSmoothingMath.stepScalar(anim.renderedLat, target.lat, alphaPos)
                anim.renderedLng = MarkerSmoothingMath.stepScalar(anim.renderedLng, wrappedTargetLng, alphaPos)
                anim.renderedLng = MarkerSmoothingMath.normalizeBearingDeg(anim.renderedLng + 180.0) - 180.0
            }

            val alphaBearing = MarkerSmoothingMath.smoothingAlpha(
                dtSeconds, MarkerSmoothingMath.BEARING_TAU_SECONDS
            )
            if (alphaBearing > 0f) {
                anim.renderedBearingDeg = MarkerSmoothingMath.stepBearingDeg(
                    anim.renderedBearingDeg, target.bearingDeg, alphaBearing
                )
            }
        }

        frameVersion++
    }

    /** Rendered latitude for a rider, or null when no valid target was ever received. */
    fun renderedLat(riderId: String): Double? = riders[riderId]?.takeIf { it.hasRendered }?.renderedLat

    /** Rendered longitude for a rider, or null when no valid target was ever received. */
    fun renderedLng(riderId: String): Double? = riders[riderId]?.takeIf { it.hasRendered }?.renderedLng

    /** Rendered bearing (0..360) for a rider, or null when no valid target was ever received. */
    fun renderedBearing(riderId: String): Double? = riders[riderId]?.takeIf { it.hasRendered }?.renderedBearingDeg

    /**
     * Returns the target longitude adjusted into the representation nearest the rendered
     * longitude (within +/-180 degrees), which keeps interpolation across the ±180°
     * antimeridian discontinuity correct.
     */
    private fun wrappedTargetLongitude(renderedLng: Double, targetLng: Double): Double {
        var delta = targetLng - renderedLng
        if (delta > 180.0) delta -= 360.0
        else if (delta < -180.0) delta += 360.0
        return renderedLng + delta
    }
}
