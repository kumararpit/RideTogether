package com.example.data.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.example.data.model.HeadingEstimate
import com.example.data.model.HeadingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Device-orientation (compass) heading source.
 *
 * Evidence (Google Maps 26.42.01): the map keeps *device orientation* and
 * *travel direction* as separate estimates. The compass is registered at the
 * same ~60 ms cadence the reference uses (`0xEA60` µs) and its samples run
 * through the same velocity-adaptive circular filter ([AdaptiveHeadingFilter])
 * as the reference's `ajde`.
 *
 * Quality model (the part that matters on a motorcycle):
 *  - platform calibration status (`SENSOR_STATUS_*`),
 *  - measured magnetic field magnitude vs the World Magnetic Model value
 *    (`GeomagneticField.fieldStrength`) — a local disturbance proxy,
 *  - current rotation rate — a compass lags while the device is turning,
 * combined into an expected angular error in degrees by [CompassMath].
 *
 * The provider itself never decides whether the map may rotate; it only reports
 * a [HeadingEstimate] with `source = COMPASS` and an honest accuracy. The
 * camera applies the speed/age/accuracy gate.
 *
 * Lifecycle: [start] / [stop] must bracket the map's visible lifetime. The
 * provider is deliberately NOT a Compose state holder — samples are delivered
 * through [listener] so a 16 Hz compass does not recompose the map canvas.
 */
class CompassHeadingProvider(context: Context) {

    private val appContext: Context = context.applicationContext

    private val sensorManager: SensorManager? =
        runCatching { appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }.getOrNull()

    /** Sensor-rate smoothing of the true-north heading. */
    private val filter = AdaptiveHeadingFilter(wrap = true, maxGapMs = 500L)

    private val _heading = MutableStateFlow<HeadingEstimate?>(null)

    /** Latest estimate (null while stopped or unsupported). */
    val heading: StateFlow<HeadingEstimate?> = _heading.asStateFlow()

    /** Sensor-rate consumer. Kept out of Compose state on purpose. */
    var listener: ((HeadingEstimate) -> Unit)? = null

    private val rotationVectorSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }.getOrNull()
    private val magneticRotationSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) }.getOrNull()
    private val accelerometerSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull()
    private val magnetometerSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }.getOrNull()
    private val gyroscopeSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }.getOrNull()

    /** True when this device can produce a device heading at all. */
    val isSupported: Boolean
        get() = rotationVectorSensor != null ||
            magneticRotationSensor != null ||
            (accelerometerSensor != null && magnetometerSensor != null)

    var isRunning: Boolean = false
        private set

    // Reused buffers — no per-sample allocation.
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravityFiltered = FloatArray(3)
    private val geomagneticFiltered = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false

    private var magnetometerStatus = CompassMath.STATUS_UNRELIABLE
    private var measuredFieldMicroTesla: Float? = null
    private var rotationRateDegPerSec: Float? = null

    private var expectedFieldMicroTesla: Float? = null
    private var declinationDeg: Float = 0f
    private var lastDeclinationLat: Double? = null
    private var lastDeclinationLng: Double? = null
    private var lastDeclinationAtMs: Long = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = handleSensorEvent(event)

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            when (sensor?.type) {
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> magnetometerStatus = accuracy
            }
        }
    }

    /**
     * Provides the rider's position for magnetic declination and field-strength
     * modelling. Cached and only recomputed after a ~5 km move or 10 minutes.
     */
    fun updateLocation(latitude: Double, longitude: Double, altitudeMeters: Double = 0.0) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return

        val now = System.currentTimeMillis()
        val prevLat = lastDeclinationLat
        val prevLng = lastDeclinationLng
        val movedFar = prevLat == null || prevLng == null ||
            abs(latitude - prevLat) > 0.05 || abs(longitude - prevLng) > 0.05
        val expired = now - lastDeclinationAtMs > DECLINATION_REFRESH_MS
        if (!movedFar && !expired) return

        runCatching {
            val field = GeomagneticField(
                latitude.toFloat(),
                longitude.toFloat(),
                altitudeMeters.toFloat(),
                now
            )
            declinationDeg = field.declination
            expectedFieldMicroTesla = field.fieldStrength / 1000f // nT → µT
        }

        lastDeclinationLat = latitude
        lastDeclinationLng = longitude
        lastDeclinationAtMs = now
    }

    /** Registers the sensors. Idempotent; no-op when unsupported. */
    fun start() {
        val manager = sensorManager ?: return
        if (isRunning || !isSupported) return

        val periodUs = SAMPLE_PERIOD_US
        val primary = rotationVectorSensor ?: magneticRotationSensor
        if (primary != null) {
            manager.registerListener(sensorListener, primary, periodUs)
        } else {
            accelerometerSensor?.let { manager.registerListener(sensorListener, it, periodUs) }
        }
        // The magnetometer is also read when a rotation vector is present: its
        // field magnitude is the disturbance input to the accuracy model.
        magnetometerSensor?.let { manager.registerListener(sensorListener, it, periodUs) }
        gyroscopeSensor?.let { manager.registerListener(sensorListener, it, periodUs) }

        isRunning = true
    }

    /** Unregisters the sensors and clears the estimate. */
    fun stop() {
        val manager = sensorManager ?: return
        if (!isRunning) return
        runCatching { manager.unregisterListener(sensorListener) }
        isRunning = false
        filter.reset()
        hasGravity = false
        hasGeomagnetic = false
        measuredFieldMicroTesla = null
        rotationRateDegPerSec = null
        _heading.value = null
    }

    private fun handleSensorEvent(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                if (event.values.size < 3) return
                magnetometerStatus = if (event.accuracy != 0) event.accuracy else magnetometerStatus
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                publish(event.accuracy)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                lowPass(event.values, gravityFiltered, GRAVITY_SMOOTHING)
                hasGravity = true
                publishFromFallback()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.values.size < 3) return
                measuredFieldMicroTesla = CompassMath.magnitude(
                    event.values[0], event.values[1], event.values[2]
                )
                magnetometerStatus = if (event.accuracy != 0) event.accuracy else magnetometerStatus
                lowPass(event.values, geomagneticFiltered, MAGNETIC_SMOOTHING)
                hasGeomagnetic = true
                publishFromFallback()
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (event.values.size < 3) return
                val rate = CompassMath.magnitude(
                    event.values[0], event.values[1], event.values[2]
                ) * RAD_TO_DEG
                rotationRateDegPerSec = rotationRateDegPerSec
                    ?.let { it * GYRO_SMOOTHING + rate * (1f - GYRO_SMOOTHING) }
                    ?: rate
            }
        }
    }

    /**
     * Accelerometer + magnetometer path: only used when the device has no
     * rotation-vector sensor. Both vectors are low-pass filtered first, which is
     * the standard way to keep hand/bike vibration out of `getRotationMatrix`.
     */
    private fun publishFromFallback() {
        if (rotationVectorSensor != null || magneticRotationSensor != null) return
        if (!hasGravity || !hasGeomagnetic) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravityFiltered, geomagneticFiltered)) return
        publish(magnetometerStatus)
    }

    /** Remaps the rotation matrix for the current display orientation and emits. */
    private fun publish(sensorStatus: Int) {
        remapForDisplay(displayRotation())
        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        val magneticAzimuth = HeadingMath.normalize360(
            Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        )
        val trueNorth = CompassMath.toTrueNorth(magneticAzimuth, declinationDeg)

        val nowMs = System.currentTimeMillis()
        val smoothed = HeadingMath.normalize360(filter.update(nowMs, trueNorth))

        val accuracy = CompassMath.estimateAccuracyDeg(
            sensorStatus = sensorStatus,
            fieldDeviationRatio = CompassMath.fieldDeviationRatio(
                measuredFieldMicroTesla,
                expectedFieldMicroTesla
            ),
            rotationRateDegPerSec = rotationRateDegPerSec
        )

        val estimate = HeadingEstimate(
            degrees = smoothed,
            source = HeadingSource.COMPASS,
            accuracyDeg = accuracy,
            timestampMs = nowMs
        )
        _heading.value = estimate
        listener?.invoke(estimate)
    }

    private fun remapForDisplay(rotation: Int) {
        val ok = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix
            )
            else -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix
            )
        }
        if (!ok) {
            // Fall back to the raw matrix rather than leaving stale remapped data.
            System.arraycopy(rotationMatrix, 0, remappedMatrix, 0, 9)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val windowManager = runCatching {
            appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return Surface.ROTATION_0
        return runCatching { windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0 }
            .getOrDefault(Surface.ROTATION_0)
    }

    private fun lowPass(input: FloatArray, output: FloatArray, smoothing: Float) {
        for (i in 0 until 3) {
            output[i] = output[i] * smoothing + input[i] * (1f - smoothing)
        }
    }

    private companion object {
        /** 60 ms — the reference's sensor cadence (`0xEA60` µs). */
        const val SAMPLE_PERIOD_US = 60_000

        const val DECLINATION_REFRESH_MS = 10 * 60 * 1000L

        const val GRAVITY_SMOOTHING = 0.85f
        const val MAGNETIC_SMOOTHING = 0.80f
        const val GYRO_SMOOTHING = 0.80f

        val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }
}
