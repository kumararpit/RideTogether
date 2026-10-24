package com.example.data.location

import com.example.data.model.HeadingPolicy
import kotlin.math.abs

/**
 * Target-level damping for a *low-rate* heading source such as GNSS course over
 * ground (one fix every 1–4 s).
 *
 * The compass filter ([AdaptiveHeadingFilter]) cannot be used here: its 1 s gap
 * reset would fire on every fix. Instead this stabilizer applies the same intent
 * at fix cadence:
 *
 *  - changes inside [deadBandDeg] are treated as estimator noise and ignored;
 *  - a large change is a real turn, adopted immediately (the 60 fps render loop
 *    still animates the camera smoothly toward the new target);
 *  - intermediate changes are eased so a single noisy fix cannot swing the map.
 *
 * It never fabricates a value: before the first target it returns whatever it is
 * given, and [reset] clears the memory so the next value is adopted directly.
 */
class CourseHeadingStabilizer(
    private val deadBandDeg: Float = HeadingPolicy.HEADING_DEAD_BAND_DEG,
    private val turnThresholdDeg: Float = 30f
) {
    private var heading: Float? = null

    val value: Float?
        get() = heading

    fun reset() {
        heading = null
    }

    fun update(targetDeg: Float): Float {
        if (!targetDeg.isFinite()) return heading ?: 0f
        val target = HeadingMath.normalize360(targetDeg)
        val current = heading
        if (current == null) {
            heading = target
            return target
        }

        val delta = HeadingMath.shortestDelta(current, target)
        val magnitude = abs(delta)

        return when {
            magnitude <= deadBandDeg -> current
            magnitude >= turnThresholdDeg -> {
                heading = target
                target
            }
            else -> {
                val alpha = if (magnitude >= 10f) 0.4f else 0.2f
                val next = HeadingMath.normalize360(current + delta * alpha)
                heading = next
                next
            }
        }
    }
}
