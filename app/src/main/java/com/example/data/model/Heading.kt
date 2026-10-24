package com.example.data.model

/**
 * Which estimator produced a heading, and therefore how much it may be trusted.
 *
 * Evidence (Google Maps 26.42.01): the map keeps *device orientation* and
 * *travel direction* as separate estimates. Only a course that passed the speed /
 * accuracy / age gate is allowed to rotate the camera, and when no source is
 * qualified the previous heading is frozen — never replaced with 0° (north).
 */
enum class HeadingSource {
    /** Course over ground reported by the fused location provider. */
    GNSS_COURSE,

    /** Course derived from two consecutive fixes (great-circle bearing). */
    DERIVED_COURSE,

    /** Device heading from rotation-vector / magnetometer fusion. */
    COMPASS,

    /** Dead-reckoned continuation across a short gap. */
    COASTED,

    /** No qualified source this fix. Consumers must preserve the previous heading. */
    UNAVAILABLE;

    val isQualified: Boolean
        get() = this == GNSS_COURSE || this == DERIVED_COURSE || this == COMPASS
}

/**
 * A heading with provenance and uncertainty.
 *
 * [accuracyDeg] is the expected 1-sigma-ish angular error in degrees, or `null`
 * when the source cannot report one. Consumers must treat `null` as "unknown",
 * not as "perfect".
 */
data class HeadingEstimate(
    val degrees: Float,
    val source: HeadingSource,
    val accuracyDeg: Float? = null,
    val timestampMs: Long = 0L
) {
    val isValid: Boolean
        get() = source.isQualified &&
            degrees.isFinite() &&
            degrees >= 0f &&
            degrees < 360f &&
            (accuracyDeg == null || (accuracyDeg.isFinite() && accuracyDeg >= 0f))

    /**
     * A heading is fresh only while it is structurally valid *and* younger than
     * [HeadingPolicy.MAX_HEADING_AGE_MS]. Google Maps expires its orientation
     * state after 5 s without an update rather than reusing it indefinitely.
     */
    fun isFresh(nowMs: Long, maxAgeMs: Long = HeadingPolicy.MAX_HEADING_AGE_MS): Boolean {
        if (!isValid) return false
        val age = nowMs - timestampMs
        return age in 0..maxAgeMs
    }
}

/**
 * Heading qualification constants derived from the Google Maps evidence report.
 *
 * The 3.0 m/s (10.8 km/h) floor is the point at which Google Maps stops trusting
 * a GNSS bearing and deletes it at the source (`aizh.x` / `aizg.k`). Above it,
 * the course is considered physically meaningful; below it, only an independent
 * "moving" signal may lower the bar, and motion must still be non-zero.
 */
object HeadingPolicy {
    /** Primary GNSS-course floor: 3.0 m/s == 10.8 km/h. */
    const val MIN_COURSE_SPEED_MPS: Float = 3.0f
    const val MIN_COURSE_SPEED_KMH: Float = 10.8f

    /**
     * Lower floor when an independent activity-recognition signal says the rider
     * is moving (walking/pushing the bike). Never zero: a stationary fix with a
     * stale "in vehicle" activity must not qualify.
     */
    const val MIN_ACTIVITY_MOVING_SPEED_MPS: Float = 1.0f

    /** Reject a bearing whose reported accuracy is worse than this. */
    const val MAX_BEARING_ACCURACY_DEG: Float = 45f

    /** Reject a fix whose speed uncertainty is worse than this. */
    const val MAX_SPEED_ACCURACY_MPS: Float = 1.0f

    /** Google Maps expires orientation state after 5 s without an update. */
    const val MAX_HEADING_AGE_MS: Long = 5_000L

    /** Derived course must imply at least this speed, not merely displacement. */
    const val MIN_DERIVATION_SPEED_MPS: Float = 3.0f

    /** Angular changes below this are treated as estimator noise. */
    const val HEADING_DEAD_BAND_DEG: Float = 2.0f

    /**
     * Compass quality gate. A motorcycle mount is a hostile magnetic
     * environment, so the compass must be *better* than this to steer the map.
     */
    const val MAX_COMPASS_ACCURACY_DEG: Float = 30f

    /** Compass samples are trusted only while this fresh. */
    const val MAX_COMPASS_AGE_MS: Long = 1_000L

    /**
     * How long the GNSS course must have been absent before the compass is
     * allowed to steer the camera. Stops a momentary magnetic disturbance from
     * winning over a course that is merely between two fixes.
     */
    const val COMPASS_TAKEOVER_DELAY_MS: Long = 2_000L

    /**
     * The compass uses GNSS speed as its "is the rider actually moving?" input.
     * Beyond this age that speed is no longer evidence of motion, so the compass
     * stops steering (a GPS outage must not let a parked bike rotate the map).
     */
    const val COMPASS_MAX_FIX_AGE_MS: Long = 10_000L
}
