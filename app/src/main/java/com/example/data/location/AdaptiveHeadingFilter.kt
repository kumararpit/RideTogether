package com.example.data.location

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Velocity-adaptive circular heading filter — a direct port of Google Maps'
 * `ajde.a(long timestampMs, float rawHeading)`.
 *
 * Why this shape (from the decompiled reference):
 *
 *  - It always integrates on a fixed 50 ms step, so behaviour does not depend on
 *    the caller's sample rate.
 *  - The internal `velocity` accumulator is accelerated by `|v|·v·step`, then
 *    decayed by `(1 − s·step)` where `s = exp(−(error/velocity)²) + 0.5`.
 *  - Small residual error ⇒ `s ≈ 1.5` ⇒ the velocity is killed quickly and the
 *    output stays quiet at rest.
 *  - Large error ⇒ `s ≈ 0.5` ⇒ the velocity survives and the output follows a
 *    real turn quickly.
 *  - A gap longer than [maxGapMs] resets to the raw target instead of
 *    interpolating across missing data (the reference uses 1000 ms).
 *  - All arithmetic is shortest-arc, then normalized to [0, 360).
 *
 * This filter is intended for *high-rate* sources (a compass sampled at ~60 ms).
 * GNSS course arrives at 1–4 s; feeding it here would hit the gap reset every
 * time, so the camera uses [CourseHeadingStabilizer] for that cadence instead.
 */
class AdaptiveHeadingFilter(
    private val wrap: Boolean = true,
    private val maxGapMs: Long = 1_000L
) {
    private var angle: Float = Float.NaN
    private var velocity: Float = 0f
    private var lastTimestampMs: Long = 0L
    private var hasSample: Boolean = false

    /** Current filtered angle, or `null` before the first sample. */
    val value: Float?
        get() = if (angle.isNaN()) null else angle

    fun reset() {
        angle = Float.NaN
        velocity = 0f
        lastTimestampMs = 0L
        hasSample = false
    }

    /**
     * Feed one raw sample. Non-finite targets are ignored and the previous value
     * is returned (or `0f` when there is no previous value).
     */
    fun update(timestampMs: Long, targetDeg: Float): Float {
        if (!targetDeg.isFinite()) return value ?: 0f

        var target = targetDeg

        if (!hasSample || angle.isNaN() || timestampMs < lastTimestampMs) {
            angle = target
            velocity = 0f
            lastTimestampMs = timestampMs
            hasSample = true
            return finish()
        }

        if (wrap) {
            val separation = abs(target - angle)
            if (separation + separation > 360f) {
                target = if (target < angle) target + 360f else target - 360f
            }
        }

        val elapsedMs = timestampMs - lastTimestampMs
        var remaining = elapsedMs / 100f

        if (elapsedMs > maxGapMs || remaining < 0f) {
            angle = target
            velocity = 0f
        } else {
            while (remaining > 0f) {
                val step = min(0.5f, remaining)
                val previous = angle
                val scaledError = (target - previous) / 10f

                velocity += abs(scaledError) * scaledError * step

                if (velocity != 0f) {
                    val ratio = (scaledError * 10f) / velocity
                    val gain = (exp(-(ratio * ratio).toDouble()) + 0.5).toFloat()
                    if (gain * step >= 1f) {
                        velocity = 0f
                    } else {
                        velocity -= gain * velocity * step
                        angle = previous + velocity * step
                    }
                }
                remaining -= 0.5f
            }
        }

        lastTimestampMs = timestampMs
        return finish()
    }

    private fun finish(): Float {
        if (wrap) angle = HeadingMath.normalize360(angle)
        return angle
    }
}
