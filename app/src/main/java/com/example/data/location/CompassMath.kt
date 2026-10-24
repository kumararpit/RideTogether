package com.example.data.location

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure compass math shared by [CompassHeadingProvider] and its unit tests.
 *
 * The compass is a *device orientation* source, not a travel direction: it must
 * be quality-gated before anything is allowed to rotate the map. The gates here
 * are the ones that survive on a motorcycle mount — device calibration status,
 * measured-vs-modelled magnetic field strength (a local disturbance proxy) and
 * the current rotation rate (a compass lags while the bars are turning).
 */
object CompassMath {

    // Mirrors android.hardware.SensorManager.SENSOR_STATUS_* — inlined as
    // compile-time constants so this stays a pure JVM object.
    const val STATUS_UNRELIABLE = 0
    const val STATUS_LOW = 1
    const val STATUS_MEDIUM = 2
    const val STATUS_HIGH = 3

    /** Magnetic azimuth → true north (wrapped to 0..360). */
    fun toTrueNorth(magneticAzimuthDeg: Float, declinationDeg: Float): Float =
        HeadingMath.normalize360(magneticAzimuthDeg + declinationDeg)

    /** Magnitude of a 3-axis sensor vector. */
    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /**
     * Relative deviation of the measured field magnitude from the modelled field
     * strength. Returns `null` when either side is unknown, so callers report
     * "unknown" rather than a fabricated zero.
     */
    fun fieldDeviationRatio(measuredMicroTesla: Float?, expectedMicroTesla: Float?): Float? {
        if (measuredMicroTesla == null || expectedMicroTesla == null) return null
        if (!measuredMicroTesla.isFinite() || measuredMicroTesla <= 0f) return null
        if (!expectedMicroTesla.isFinite() || expectedMicroTesla <= 0f) return null
        return abs(measuredMicroTesla - expectedMicroTesla) / expectedMicroTesla
    }

    /**
     * Expected angular error of the compass, in degrees.
     *
     * Base error comes from the platform's calibration status bucket; magnetic
     * disturbance and rotation add on top. A device that has never been
     * calibrated (unreliable status) is returned as 90° so it can never pass the
     * camera's quality gate.
     */
    fun estimateAccuracyDeg(
        sensorStatus: Int,
        fieldDeviationRatio: Float? = null,
        rotationRateDegPerSec: Float? = null
    ): Float {
        var accuracy = when (sensorStatus) {
            STATUS_HIGH -> 5f
            STATUS_MEDIUM -> 12f
            STATUS_LOW -> 25f
            else -> 90f
        }

        fieldDeviationRatio?.let { ratio ->
            if (ratio.isFinite()) {
                accuracy += when {
                    ratio <= 0.15f -> 0f
                    ratio <= 0.30f -> 8f
                    ratio <= 0.50f -> 20f
                    else -> 60f
                }
            }
        }

        rotationRateDegPerSec?.let { rate ->
            if (rate.isFinite() && rate > 0f) {
                accuracy += (rate / 30f).coerceAtMost(3f) * 10f
            }
        }

        return accuracy
    }
}
