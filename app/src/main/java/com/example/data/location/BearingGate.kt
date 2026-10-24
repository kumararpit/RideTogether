package com.example.data.location

import com.example.data.model.HeadingPolicy

/**
 * Decides whether a heading is trustworthy enough to rotate the map.
 *
 * Mirrors the Google Maps gate (`aizh.x`):
 *
 * ```java
 * return hasBearing && (speed > 3.0f || activityMoving);
 * ```
 *
 * with three additions that make it safer on a motorcycle:
 *
 *  - the bearing must be structurally valid (finite, 0..360);
 *  - the fix must be fresh (5 s TTL, matching `ajdt.b`);
 *  - an explicitly reported bearing/speed accuracy may veto the fix.
 *
 * The activity branch is deliberately *not* an unconditional OR: a stale
 * "in vehicle" activity while parked must not keep a noisy bearing alive, so
 * motion still has to be non-zero.
 */
object BearingGate {

    fun qualifiesCourse(
        headingDeg: Float?,
        speedMps: Float,
        bearingAccuracyDeg: Float? = null,
        speedAccuracyMps: Float? = null,
        activityMoving: Boolean = false,
        ageMs: Long = 0L
    ): Boolean {
        if (headingDeg == null) return false
        if (!headingDeg.isFinite() || headingDeg < 0f || headingDeg > 360f) return false
        if (!speedMps.isFinite() || speedMps < 0f) return false
        if (ageMs !in 0..HeadingPolicy.MAX_HEADING_AGE_MS) return false

        if (bearingAccuracyDeg != null &&
            (!bearingAccuracyDeg.isFinite() ||
                bearingAccuracyDeg > HeadingPolicy.MAX_BEARING_ACCURACY_DEG)
        ) {
            return false
        }

        if (speedAccuracyMps != null &&
            (!speedAccuracyMps.isFinite() ||
                speedAccuracyMps > HeadingPolicy.MAX_SPEED_ACCURACY_MPS)
        ) {
            return false
        }

        return if (activityMoving) {
            speedMps >= HeadingPolicy.MIN_ACTIVITY_MOVING_SPEED_MPS
        } else {
            speedMps >= HeadingPolicy.MIN_COURSE_SPEED_MPS
        }
    }

    /**
     * Derived course over ground is only as good as the positions it came from,
     * so it uses the same speed floor as a reported bearing plus the age gate.
     */
    fun qualifiesDerived(speedMps: Float, ageMs: Long = 0L): Boolean =
        speedMps.isFinite() &&
            speedMps >= HeadingPolicy.MIN_COURSE_SPEED_MPS &&
            ageMs in 0..HeadingPolicy.MAX_HEADING_AGE_MS
}
