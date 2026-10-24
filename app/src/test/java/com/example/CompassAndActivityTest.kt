package com.example

import com.example.data.location.ActivityRecognitionMonitor
import com.example.data.location.CompassMath
import com.example.data.location.LocationUpdate
import com.example.data.location.MotionState
import com.example.data.map.MapCameraController
import com.example.data.model.HeadingEstimate
import com.example.data.model.HeadingPolicy
import com.example.data.model.HeadingSource
import com.example.data.model.LatLng
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compass provider math + the ACTIVITY_RECOGNITION signal, and the camera's
 * compass acceptance contract.
 */
class CompassAndActivityTest {

    private val start = LatLng(18.5204, 73.8567)
    private val t0 = 1_000_000L

    private fun compassEstimate(
        degrees: Float,
        accuracyDeg: Float? = 8f,
        timestampMs: Long = System.currentTimeMillis(),
        source: HeadingSource = HeadingSource.COMPASS
    ) = HeadingEstimate(
        degrees = degrees,
        source = source,
        accuracyDeg = accuracyDeg,
        timestampMs = timestampMs
    )

    private fun fix(
        speedKmh: Double,
        headingDeg: Float?,
        timestampMs: Long
    ) = LocationUpdate(
        latLng = start,
        speedKmh = speedKmh,
        headingDeg = headingDeg,
        accuracyMeters = 5f,
        timestampMs = timestampMs
    )

    // ------------------------------------------------------------- compass math

    @Test
    fun `true north applies declination and wraps`() {
        assertEquals(10f, CompassMath.toTrueNorth(350f, 20f), 1e-3f)
        assertEquals(340f, CompassMath.toTrueNorth(350f, -10f), 1e-3f)
        assertEquals(0f, CompassMath.toTrueNorth(360f, 0f), 1e-3f)
    }

    @Test
    fun `vector magnitude is euclidean`() {
        assertEquals(5f, CompassMath.magnitude(3f, 4f, 0f), 1e-4f)
        assertEquals(0f, CompassMath.magnitude(0f, 0f, 0f), 1e-4f)
    }

    @Test
    fun `field deviation needs both sides to be known`() {
        assertEquals(0.1f, CompassMath.fieldDeviationRatio(45f, 50f)!!, 1e-4f)
        assertNull(CompassMath.fieldDeviationRatio(null, 50f))
        assertNull(CompassMath.fieldDeviationRatio(45f, null))
        assertNull("a zero reading is not a measurement", CompassMath.fieldDeviationRatio(0f, 50f))
        assertNull(CompassMath.fieldDeviationRatio(45f, 0f))
    }

    @Test
    fun `accuracy tracks platform calibration status`() {
        assertEquals(5f, CompassMath.estimateAccuracyDeg(CompassMath.STATUS_HIGH), 1e-3f)
        assertEquals(12f, CompassMath.estimateAccuracyDeg(CompassMath.STATUS_MEDIUM), 1e-3f)
        assertEquals(25f, CompassMath.estimateAccuracyDeg(CompassMath.STATUS_LOW), 1e-3f)
        assertEquals(
            "an uncalibrated compass must never pass the gate",
            90f,
            CompassMath.estimateAccuracyDeg(CompassMath.STATUS_UNRELIABLE),
            1e-3f
        )
    }

    @Test
    fun `magnetic disturbance and rotation both degrade accuracy`() {
        val cleanHigh = CompassMath.estimateAccuracyDeg(CompassMath.STATUS_HIGH)
        val disturbed = CompassMath.estimateAccuracyDeg(
            CompassMath.STATUS_HIGH,
            fieldDeviationRatio = 0.40f
        )
        val heavilyDisturbed = CompassMath.estimateAccuracyDeg(
            CompassMath.STATUS_HIGH,
            fieldDeviationRatio = 0.80f
        )
        assertTrue("disturbance must add error", disturbed > cleanHigh)
        assertTrue("severe disturbance must add a lot", heavilyDisturbed > disturbed)

        val turning = CompassMath.estimateAccuracyDeg(
            CompassMath.STATUS_HIGH,
            rotationRateDegPerSec = 60f
        )
        assertTrue("rotation must add error", turning > cleanHigh)
        assertEquals(5f + 20f, turning, 1e-3f)
    }

    @Test
    fun `a disturbed mount can be pushed out of the camera gate`() {
        // Street reality: a phone clamped to a vibrating steel bar.
        val accuracy = CompassMath.estimateAccuracyDeg(
            sensorStatus = CompassMath.STATUS_MEDIUM,
            fieldDeviationRatio = 0.35f,
            rotationRateDegPerSec = 45f
        )
        assertTrue(
            "expected $accuracy to exceed the compass gate",
            accuracy > HeadingPolicy.MAX_COMPASS_ACCURACY_DEG
        )
    }

    // ------------------------------------------------------- activity recognition

    @Test
    fun `activity types map to movement classifications`() {
        assertEquals(MotionState.STILL, ActivityRecognitionMonitor.motionStateFor(DetectedActivity.STILL))

        for (type in listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING
        )) {
            assertEquals(
                "type $type must count as moving",
                MotionState.MOVING,
                ActivityRecognitionMonitor.motionStateFor(type)
            )
        }

        assertEquals(MotionState.UNKNOWN, ActivityRecognitionMonitor.motionStateFor(DetectedActivity.TILTING))
        assertEquals(MotionState.UNKNOWN, ActivityRecognitionMonitor.motionStateFor(DetectedActivity.UNKNOWN))
        assertEquals(MotionState.UNKNOWN, ActivityRecognitionMonitor.motionStateFor(999))
    }

    @Test
    fun `a null result is ignored rather than reported as still`() {
        val before = ActivityRecognitionMonitor.state.value
        ActivityRecognitionMonitor.onActivityResult(null)
        assertEquals(before, ActivityRecognitionMonitor.state.value)
    }

    // ------------------------------------------------- camera compass contract

    @Test
    fun `camera accepts a compass heading when moving slowly without a course`() {
        val controller = MapCameraController()
        // 5 km/h = 1.39 m/s: below the 3.0 m/s course floor, above the motion floor.
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = t0), nowMs = t0)

        val state = controller.state.value
        assertTrue(state.headingValid)
        assertEquals(90f, state.heading, 1e-3f)
        assertEquals(HeadingSource.COMPASS, state.headingSource)
    }

    @Test
    fun `camera never rotates to the compass while stopped`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 0.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = t0), nowMs = t0)

        val state = controller.state.value
        assertFalse(state.headingValid)
        assertEquals("must not invent a direction at rest", 0f, state.heading, 1e-3f)
    }

    @Test
    fun `an inaccurate compass is rejected`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.setDeviceHeading(
            compassEstimate(90f, accuracyDeg = HeadingPolicy.MAX_COMPASS_ACCURACY_DEG + 5f, timestampMs = t0),
            nowMs = t0
        )

        assertFalse(controller.state.value.headingValid)
        assertEquals(0f, controller.state.value.heading, 1e-3f)
    }

    @Test
    fun `a stale compass sample is rejected`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.setDeviceHeading(
            compassEstimate(90f, timestampMs = t0 - 5_000L),
            nowMs = t0
        )

        assertFalse(controller.state.value.headingValid)
    }

    @Test
    fun `a non compass source is ignored by the device heading path`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.setDeviceHeading(
            compassEstimate(90f, timestampMs = t0, source = HeadingSource.GNSS_COURSE),
            nowMs = t0
        )

        assertFalse(controller.state.value.headingValid)
    }

    @Test
    fun `compass waits for the course to go quiet before taking over`() {
        val controller = MapCameraController()
        // A qualified course at t0, then the rider slows down.
        controller.processLocation(fix(speedKmh = 45.0, headingDeg = 200f, timestampMs = t0), nowMs = t0)

        // 0.5 s later the compass must NOT override the live course.
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0 + 500L), nowMs = t0 + 500L)
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = t0 + 500L), nowMs = t0 + 500L)
        assertEquals(200f, controller.state.value.heading, 1e-3f)
        assertEquals(HeadingSource.GNSS_COURSE, controller.state.value.headingSource)

        // Past the takeover delay, still moving: the compass takes over.
        val later = t0 + HeadingPolicy.COMPASS_TAKEOVER_DELAY_MS + 500L
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = later), nowMs = later)
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = later), nowMs = later)
        assertEquals(90f, controller.state.value.heading, 1e-3f)
        assertEquals(HeadingSource.COMPASS, controller.state.value.headingSource)
    }

    @Test
    fun `compass is ignored outside follow mode`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)
        controller.onUserGesture() // FREE_EXPLORE
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = t0), nowMs = t0)

        assertFalse(controller.state.value.headingValid)
    }

    @Test
    fun `compass is ignored when the speed context is stale after a gps outage`() {
        val controller = MapCameraController()
        controller.processLocation(fix(speedKmh = 5.0, headingDeg = null, timestampMs = t0), nowMs = t0)

        // No fix for longer than the speed-context window: the remembered
        // "moving" speed is no longer evidence that the bike is moving.
        val stale = t0 + HeadingPolicy.COMPASS_MAX_FIX_AGE_MS + 1_000L
        controller.setDeviceHeading(compassEstimate(90f, timestampMs = stale), nowMs = stale)

        assertFalse(controller.state.value.headingValid)
        assertEquals(0f, controller.state.value.heading, 1e-3f)
    }
}
