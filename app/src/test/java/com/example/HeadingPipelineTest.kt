package com.example

import com.example.data.location.AdaptiveHeadingFilter
import com.example.data.location.BearingGate
import com.example.data.location.CourseHeadingStabilizer
import com.example.data.location.CourseOverGround
import com.example.data.location.HeadingMath
import com.example.data.location.LocationUpdate
import com.example.data.map.MapCameraController
import com.example.data.map.OrientationMode
import com.example.data.model.HeadingEstimate
import com.example.data.model.HeadingPolicy
import com.example.data.model.HeadingSource
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Evidence-derived heading pipeline tests.
 *
 * The reference behaviour comes from the Google Maps 26.42.01 decompile:
 * GNSS course is deleted at source below 3.0 m/s, orientation state expires
 * after 5 s, and a missing bearing is never replaced with north.
 */
class HeadingPipelineTest {

    private val start = LatLng(18.5204, 73.8567)

    private fun update(
        headingDeg: Float?,
        speedKmh: Double,
        timestampMs: Long = 1_000_000L,
        headingAccuracyDeg: Float? = null,
        speedAccuracyMps: Float? = null,
        activityMoving: Boolean = false
    ) = LocationUpdate(
        latLng = start,
        speedKmh = speedKmh,
        headingDeg = headingDeg,
        accuracyMeters = 5f,
        timestampMs = timestampMs,
        headingSource = if (headingDeg != null) HeadingSource.GNSS_COURSE else HeadingSource.UNAVAILABLE,
        headingAccuracyDeg = headingAccuracyDeg,
        speedAccuracyMps = speedAccuracyMps,
        activityMoving = activityMoving
    )

    // ---------------------------------------------------------------- math

    @Test
    fun `heading math uses shortest arc across north`() {
        assertEquals(2f, HeadingMath.shortestDelta(359f, 1f), 1e-4f)
        assertEquals(-2f, HeadingMath.shortestDelta(1f, 359f), 1e-4f)
        assertEquals(180f, HeadingMath.shortestDelta(0f, 180f), 1e-4f)
        assertEquals(0f, HeadingMath.normalize360(360f), 1e-4f)
        assertEquals(350f, HeadingMath.normalize360(-10f), 1e-4f)
        assertEquals(0f, HeadingMath.normalize360(Float.NaN), 1e-4f)
        assertEquals(-90f, HeadingMath.wrap180(270f), 1e-4f)
    }

    // ---------------------------------------------------------------- gate

    @Test
    fun `course below 3 m per second is rejected`() {
        assertFalse(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 2.9f
            )
        )
        assertTrue(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 3.0f
            )
        )
    }

    @Test
    fun `activity moving lowers the floor but never allows zero speed`() {
        assertTrue(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 1.5f,
                activityMoving = true
            )
        )
        assertFalse(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 0.0f,
                activityMoving = true
            )
        )
    }

    @Test
    fun `inaccurate bearing or speed is rejected`() {
        assertFalse(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 12f,
                bearingAccuracyDeg = HeadingPolicy.MAX_BEARING_ACCURACY_DEG + 1f
            )
        )
        assertFalse(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 12f,
                speedAccuracyMps = HeadingPolicy.MAX_SPEED_ACCURACY_MPS + 0.1f
            )
        )
        assertTrue(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 12f,
                bearingAccuracyDeg = 10f,
                speedAccuracyMps = 0.3f
            )
        )
    }

    @Test
    fun `stale fix is rejected`() {
        assertFalse(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 12f,
                ageMs = HeadingPolicy.MAX_HEADING_AGE_MS + 1L
            )
        )
        assertTrue(
            BearingGate.qualifiesCourse(
                headingDeg = 90f,
                speedMps = 12f,
                ageMs = HeadingPolicy.MAX_HEADING_AGE_MS
            )
        )
    }

    @Test
    fun `structurally invalid headings are rejected`() {
        assertFalse(BearingGate.qualifiesCourse(headingDeg = null, speedMps = 12f))
        assertFalse(BearingGate.qualifiesCourse(headingDeg = Float.NaN, speedMps = 12f))
        assertFalse(BearingGate.qualifiesCourse(headingDeg = -10f, speedMps = 12f))
        assertFalse(BearingGate.qualifiesCourse(headingDeg = 361f, speedMps = 12f))
    }

    // ---------------------------------------------------------------- estimate

    @Test
    fun `heading estimate validity and freshness`() {
        val valid = HeadingEstimate(90f, HeadingSource.GNSS_COURSE, 5f, 1_000L)
        assertTrue(valid.isValid)
        assertTrue(valid.isFresh(5_000L))
        assertFalse(valid.isFresh(7_000L))

        assertFalse(HeadingEstimate(90f, HeadingSource.UNAVAILABLE).isValid)
        assertFalse(HeadingEstimate(90f, HeadingSource.GNSS_COURSE, -1f).isValid)
        assertFalse(HeadingEstimate(Float.NaN, HeadingSource.GNSS_COURSE).isValid)
        assertFalse(HeadingEstimate(360f, HeadingSource.GNSS_COURSE).isValid)
    }

    // ------------------------------------------------------- adaptive filter

    @Test
    fun `adaptive filter damps stationary jitter`() {
        val filter = AdaptiveHeadingFilter()
        var t = 0L
        filter.update(t, 0f)
        val jitter = listOf(1.5f, -1.2f, 0.8f, -0.9f, 1.7f, -1.6f, 0.4f, -0.3f)
        var output = 0f
        repeat(40) {
            for (sample in jitter) {
                t += 60L
                output = filter.update(t, sample)
            }
        }
        assertTrue("jitter should stay within a few degrees, got $output", abs(output) < 3f)
    }

    @Test
    fun `adaptive filter follows a real turn`() {
        val filter = AdaptiveHeadingFilter()
        var t = 0L
        filter.update(t, 0f)
        var output = 0f
        repeat(60) {
            t += 60L
            output = filter.update(t, 90f)
        }
        assertTrue(
            "filter should converge to the turn, got $output",
            abs(HeadingMath.shortestDelta(90f, output)) < 5f
        )
    }

    @Test
    fun `adaptive filter resets across a long gap`() {
        val filter = AdaptiveHeadingFilter()
        filter.update(0L, 0f)
        val output = filter.update(2_000L, 90f)
        assertEquals(90f, output, 1e-3f)
    }

    @Test
    fun `adaptive filter takes the short arc from 350 to 10`() {
        val filter = AdaptiveHeadingFilter()
        filter.update(0L, 350f)
        val output = filter.update(100L, 10f)
        val delta = HeadingMath.shortestDelta(350f, output)
        assertTrue("expected a small positive move, got $output", delta > 0f && delta < 20f)
    }

    // ---------------------------------------------------- course stabilizer

    @Test
    fun `course stabilizer ignores dead-band jitter and adopts real turns`() {
        val stabilizer = CourseHeadingStabilizer()
        assertEquals(45f, stabilizer.update(45f), 1e-3f)
        // Sub dead-band change is noise.
        assertEquals(45f, stabilizer.update(45.5f), 1e-3f)
        // Intermediate change is eased, not snapped.
        val eased = stabilizer.update(60f)
        assertTrue(eased > 45f && eased < 60f)
        // A real turn (> 30 deg) is adopted immediately; the 60 fps renderer
        // animates the visual transition.
        assertEquals(120f, stabilizer.update(120f), 1e-3f)
    }

    // ------------------------------------------------------ course over ground

    @Test
    fun `derived course requires implied speed of at least 3 m per second`() {
        // 3 m over 2 s = 1.5 m/s -> exactly the band the OS bearing gate rejects.
        val threeMetersNorth = LatLng(start.latitude + 2.6979e-5, start.longitude)
        assertNull(CourseOverGround.derive(start, 0L, threeMetersNorth, 2_000L))

        // 10 m over 2 s = 5 m/s -> trustworthy.
        val tenMetersNorth = LatLng(start.latitude + 8.9929e-5, start.longitude)
        val heading = CourseOverGround.derive(start, 0L, tenMetersNorth, 2_000L)
        assertTrue("expected a derived heading", heading != null)
        assertEquals(0f, heading!!, 1f)
    }

    @Test
    fun `derived bearing accuracy grows as displacement shrinks`() {
        val near = CourseOverGround.estimatedBearingAccuracyDeg(5f, 10.0)
        val far = CourseOverGround.estimatedBearingAccuracyDeg(5f, 100.0)
        assertTrue(near != null && far != null)
        assertTrue("near displacement should be less certain", near!! > far!!)
        assertNull(CourseOverGround.estimatedBearingAccuracyDeg(5f, 0.0))
    }

    // ------------------------------------------------------- camera contract

    @Test
    fun `camera adopts a qualified heading`() {
        val controller = MapCameraController()
        controller.processLocation(update(90f, 45.0), nowMs = 1_000_000L)

        val state = controller.state.value
        assertEquals(90f, state.heading, 1e-3f)
        assertTrue(state.headingValid)
        assertEquals(HeadingSource.GNSS_COURSE, state.headingSource)
    }

    @Test
    fun `camera rejects a low speed heading and never rotates north`() {
        val controller = MapCameraController()
        controller.processLocation(update(180f, 45.0), nowMs = 1_000_000L)
        controller.processLocation(update(90f, 9.0), nowMs = 1_001_000L)

        val state = controller.state.value
        assertEquals("must freeze at the last qualified heading", 180f, state.heading, 1e-3f)
        assertTrue(state.headingValid)
    }

    @Test
    fun `camera rejects an inaccurate bearing`() {
        val controller = MapCameraController()
        controller.processLocation(
            update(90f, 45.0, headingAccuracyDeg = 60f),
            nowMs = 1_000_000L
        )

        val state = controller.state.value
        assertEquals(0f, state.heading, 1e-3f)
        assertFalse(state.headingValid)
    }

    @Test
    fun `camera rejects a stale fix`() {
        val controller = MapCameraController()
        controller.processLocation(update(90f, 45.0), nowMs = 1_000_000L + 6_000L)

        assertFalse(controller.state.value.headingValid)
    }

    @Test
    fun `heading expires after five seconds but stays frozen`() {
        val controller = MapCameraController()
        val t0 = 1_000_000L
        controller.processLocation(update(90f, 45.0, t0), nowMs = t0)
        assertTrue(controller.state.value.headingValid)

        // Stopped, no bearing: still inside the 5 s TTL, so it remains valid.
        controller.processLocation(update(null, 0.0, t0 + 1_000L), nowMs = t0 + 1_000L)
        assertTrue(controller.state.value.headingValid)
        assertEquals(90f, controller.state.value.heading, 1e-3f)

        // Past the TTL: invalid, but the displayed heading is frozen, not reset.
        controller.processLocation(update(null, 0.0, t0 + 6_000L), nowMs = t0 + 6_000L)
        assertFalse(controller.state.value.headingValid)
        assertEquals("must not snap to north", 90f, controller.state.value.heading, 1e-3f)
    }

    @Test
    fun `camera dampens jitter but follows a real turn`() {
        val controller = MapCameraController()
        val t0 = 1_000_000L
        controller.processLocation(update(100f, 45.0, t0), nowMs = t0)
        controller.processLocation(update(101.2f, 45.0, t0 + 1_000L), nowMs = t0 + 1_000L)
        assertEquals(100f, controller.state.value.heading, 1e-3f)

        controller.processLocation(update(190f, 45.0, t0 + 2_000L), nowMs = t0 + 2_000L)
        assertEquals(190f, controller.state.value.heading, 1e-3f)
    }

    @Test
    fun `activity moving allows a slow but real course`() {
        val controller = MapCameraController()
        controller.processLocation(
            update(90f, 5.0, activityMoving = true),
            nowMs = 1_000_000L
        )
        assertTrue(controller.state.value.headingValid)
        assertEquals(90f, controller.state.value.heading, 1e-3f)
    }

    @Test
    fun `north up mode still reports the frozen heading`() {
        val controller = MapCameraController()
        controller.processLocation(update(135f, 45.0), nowMs = 1_000_000L)
        controller.setOrientationMode(OrientationMode.NORTH_UP)

        assertEquals(0f, controller.state.value.effectiveHeading, 1e-3f)
        assertEquals(135f, controller.state.value.heading, 1e-3f)
    }
}
