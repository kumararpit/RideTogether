package com.example

import com.example.data.map.MarkerSmoothingMath
import com.example.data.map.RiderMarkerAnimator
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Task 17 — Frame-paced rider marker & bearing gliding.
 *
 * Deterministic tests: every animation step is driven by an explicit elapsed
 * time via [RiderMarkerAnimator.advance] — no real-time sleeps, no frame-rate
 * dependencies.
 */
class Task17RiderMarkerSmoothingTest {

    private companion object {
        const val EPS = 1e-6
        const val FLOAT_EPS = 1e-6f
        const val FRAME_60HZ = 1.0 / 60.0        // ~16.67 ms
        const val FRAME_120HZ = 1.0 / 120.0      // ~8.33 ms
        /** Degrees of latitude per meter (equirectangular, near equator). */
        const val LAT_DEG_PER_METER = 1.0 / 111_320.0
    }

    private fun metersNorth(originLat: Double, meters: Double): Double =
        originLat + meters * LAT_DEG_PER_METER

    // ------------------------------------------------------------------
    // MarkerSmoothingMath — interpolation factor
    // ------------------------------------------------------------------

    @Test
    fun `constants match the specification`() {
        assertEquals(0.15f, MarkerSmoothingMath.POSITION_TAU_SECONDS, 1e-6f)
        assertEquals(0.10f, MarkerSmoothingMath.BEARING_TAU_SECONDS, 1e-6f)
        assertEquals(150.0, MarkerSmoothingMath.TELEPORT_THRESHOLD_METERS, EPS)
    }

    @Test
    fun `interpolation factor increases with elapsed time`() {
        val a1 = MarkerSmoothingMath.smoothingAlpha(0.05, MarkerSmoothingMath.POSITION_TAU_SECONDS)
        val a2 = MarkerSmoothingMath.smoothingAlpha(0.10, MarkerSmoothingMath.POSITION_TAU_SECONDS)
        val a3 = MarkerSmoothingMath.smoothingAlpha(0.20, MarkerSmoothingMath.POSITION_TAU_SECONDS)
        assertTrue(a1 in 0f..1f && a2 in 0f..1f && a3 in 0f..1f)
        assertTrue("alpha must grow with dt", a1 < a2 && a2 < a3)
    }

    @Test
    fun `zero and negative elapsed time produce zero movement factor`() {
        assertEquals(0f, MarkerSmoothingMath.smoothingAlpha(0.0, 0.15f), FLOAT_EPS)
        assertEquals(0f, MarkerSmoothingMath.smoothingAlpha(-0.016, 0.15f), FLOAT_EPS)
    }

    @Test
    fun `same elapsed time gives same progress at 60Hz and 120Hz frame pacing`() {
        // Two 120 Hz frames (8.33 ms each) must advance interpolation by the same
        // amount as one 60 Hz frame (16.67 ms) — closing-error is the only difference.
        val alpha60 = MarkerSmoothingMath.smoothingAlpha(FRAME_60HZ, 0.15f)
        val alpha120 = MarkerSmoothingMath.smoothingAlpha(FRAME_120HZ, 0.15f)
        val composed120 = 1.0 - (1.0 - alpha120) * (1.0 - alpha120)
        assertEquals(alpha60.toDouble(), composed120, 0.005)
    }

    @Test
    fun `one-tau elapsed time yields 63 percent progress`() {
        assertEquals(0.6321f, MarkerSmoothingMath.smoothingAlpha(0.15, 0.15f), 0.001f)
    }

    // ------------------------------------------------------------------
    // MarkerSmoothingMath — shortest-arc bearing
    // ------------------------------------------------------------------

    @Test
    fun `359 to 1 rotates through north along the short arc`() {
        assertEquals(2f, MarkerSmoothingMath.shortestAngleDeltaDeg(359f, 1f), FLOAT_EPS)
    }

    @Test
    fun `1 to 359 rotates through north along the short arc`() {
        assertEquals(-2f, MarkerSmoothingMath.shortestAngleDeltaDeg(1f, 359f), FLOAT_EPS)
    }

    @Test
    fun `equal bearings are stable`() {
        assertEquals(0f, MarkerSmoothingMath.shortestAngleDeltaDeg(137f, 137f), FLOAT_EPS)
        assertEquals(0f, MarkerSmoothingMath.shortestAngleDeltaDeg(359.5f, 359.5f), FLOAT_EPS)
    }

    @Test
    fun `negative and greater-than-360 raw values normalize safely`() {
        assertEquals(10.0, MarkerSmoothingMath.normalizeBearingDeg(370.0), EPS)
        assertEquals(350.0, MarkerSmoothingMath.normalizeBearingDeg(-10.0), EPS)
        assertEquals(180.0, MarkerSmoothingMath.normalizeBearingDeg(540.0), EPS)
        assertEquals(0.0, MarkerSmoothingMath.normalizeBearingDeg(720.0), EPS)
        assertEquals(0.0, MarkerSmoothingMath.normalizeBearingDeg(Double.NaN), EPS)
    }

    @Test
    fun `bearing step stays between 0 and 360 and follows short arc`() {
        val next = MarkerSmoothingMath.stepBearingDeg(1.0, 359.0, 1.0f) // full step
        assertEquals(359.0, next, EPS)
    }

    // ------------------------------------------------------------------
    // RiderMarkerAnimator — position initialization & invalid input
    // ------------------------------------------------------------------

    @Test
    fun `first valid target initializes rendered position without origin animation`() {
        val anim = RiderMarkerAnimator()
        assertNull("No rendered state before first valid target", anim.renderedLat("rider1"))

        anim.updateTarget("rider1", 18.5204, 73.8567, 90.0)
        assertEquals(18.5204, anim.renderedLat("rider1")!!, EPS)
        assertEquals(73.8567, anim.renderedLng("rider1")!!, EPS)
        assertEquals(90.0, anim.renderedBearing("rider1")!!, EPS)

        // A frame tick must not move an already-initialized marker when target is unchanged.
        anim.advance(FRAME_60HZ)
        assertEquals(18.5204, anim.renderedLat("rider1")!!, EPS)
    }

    @Test
    fun `invalid coordinates never corrupt the rendered state`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5204, 73.8567, 90.0)

        // Invalid updates must be rejected outright.
        anim.updateTarget("rider1", Double.NaN, 73.8567, 90.0)
        anim.updateTarget("rider1", 18.5204, Double.POSITIVE_INFINITY, 90.0)
        anim.updateTarget("rider1", 95.0, 73.8567, 90.0)   // lat out of bounds
        anim.updateTarget("rider1", 18.5204, 200.0, 90.0)  // lng out of bounds
        anim.advance(FRAME_60HZ)

        assertEquals(18.5204, anim.renderedLat("rider1")!!, EPS)
        assertEquals(73.8567, anim.renderedLng("rider1")!!, EPS)
        assertEquals(90.0, anim.renderedBearing("rider1")!!, EPS)
    }

    @Test
    fun `advance with no target and with zero dt are safe no-ops`() {
        val anim = RiderMarkerAnimator()
        anim.advance(FRAME_60HZ) // no targets registered: nothing to move
        assertNull(anim.renderedLat("ghost"))

        anim.updateTarget("rider1", 18.5, 73.8, 90.0)
        val versionBefore = anim.frameVersion
        anim.advance(0.0)
        anim.advance(-0.5)
        assertEquals("Zero/negative dt is a no-op", versionBefore, anim.frameVersion)
        assertEquals(18.5, anim.renderedLat("rider1")!!, EPS)
    }

    // ------------------------------------------------------------------
    // RiderMarkerAnimator — glide toward a fixed target
    // ------------------------------------------------------------------

    @Test
    fun `small position change converges monotonically toward the target`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 0.0)  // first fix (snaps)

        val targetLat = metersNorth(18.5200, 50.0) // rider moved 50 m north
        anim.updateTarget("rider1", targetLat, 73.8567, 0.0)

        var previousDistance = anim.renderedLat("rider1")!! distanceToTargetLat targetLat
        // 60 frames ≈ 1 s of smooth glide
        repeat(60) {
            anim.advance(FRAME_60HZ)
            val current = anim.renderedLat("rider1")!! distanceToTargetLat targetLat
            assertTrue(
                "Rendered position must move monotonically toward the target " +
                    "(prev=$previousDistance cur=$current)",
                current < previousDistance + EPS
            )
            previousDistance = current
        }
        assertEquals("Must converge to the target after ~1 second", 0.0, previousDistance, 0.5 * LAT_DEG_PER_METER)
    }

    private infix fun Double.distanceToTargetLat(targetLat: Double): Double = abs(this - targetLat)

    @Test
    fun `glide progress is frame-rate independent for equal elapsed time`() {
        fun sim(frameDt: Double, frameCount: Int): Double {
            val anim = RiderMarkerAnimator()
            anim.updateTarget("r", 0.0, 0.0, 0.0)
            anim.updateTarget("r", metersNorth(0.0, 100.0), 0.0, 0.0) // 100 m north
            repeat(frameCount) { anim.advance(frameDt) }
            return anim.renderedLat("r")!!
        }
        val at60Hz = sim(FRAME_60HZ, 60)      // 1.0 s of frames
        val at120Hz = sim(FRAME_120HZ, 120)   // 1.0 s of frames
        assertEquals(at60Hz, at120Hz, 0.15 * LAT_DEG_PER_METER) // ≈ same glide position
    }

    // ------------------------------------------------------------------
    // RiderMarkerAnimator — teleport protection & snap
    // ------------------------------------------------------------------

    @Test
    fun `target beyond 150 meters snaps instead of gliding across the map`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 45.0)

        val jumpLat = metersNorth(18.5200, 500.0) // 500 m discontinuity
        anim.updateTarget("rider1", jumpLat, 73.8567, 220.0)
        anim.advance(FRAME_60HZ)

        assertEquals("Must snap instantly", jumpLat, anim.renderedLat("rider1")!!, EPS)
        assertEquals(73.8567, anim.renderedLng("rider1")!!, EPS)
        assertEquals("Bearing snaps with the position", 220.0, anim.renderedBearing("rider1")!!, EPS)
    }

    @Test
    fun `snap forces immediate alignment for recenter`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 0.0)
        anim.updateTarget("rider1", metersNorth(18.5200, 20.0), 73.8567, 10.0)
        anim.advance(FRAME_60HZ)
        val midGlide = anim.renderedLat("rider1")!!
        assertTrue("Sanity: mid-glide state exists", midGlide < metersNorth(18.5200, 20.0))

        assertTrue(anim.snap("rider1"))
        assertEquals(metersNorth(18.5200, 20.0), anim.renderedLat("rider1")!!, EPS)
        assertEquals(10.0, anim.renderedBearing("rider1")!!, EPS)
    }

    @Test
    fun `snap for unknown rider is a safe no-op`() {
        val anim = RiderMarkerAnimator()
        assertFalse(anim.snap("ghost"))
    }

    // ------------------------------------------------------------------
    // RiderMarkerAnimator — bearing gliding
    // ------------------------------------------------------------------

    @Test
    fun `bearing glides through north on the short arc from 359 to 1`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5, 73.8, 359.0)
        anim.updateTarget("rider1", 18.5, 73.8, 1.0) // +2° short arc through north

        repeat(20) { anim.advance(FRAME_60HZ) } // 333 ms ≈ several bearing time constants
        val final = anim.renderedBearing("rider1")!!
        assertEquals("Must converge near 1° without rotating through 180°", 1.0, final, 1.0)
        // All intermediate values must stay on the north side (short arc), i.e.
        // the rendered bearing can never wander into the 2..358 band for this target.
        anim.advance(FRAME_60HZ)
        overshootGuard(anim.renderedBearing("rider1")!!, 1.0)
    }

    private fun overshootGuard(currentNear: Double, target: Double) {
        val delta = MarkerSmoothingMath.shortestAngleDeltaDeg(currentNear.toFloat(), target.toFloat())
        assertTrue("Bearing must stay on the short arc after convergence", abs(delta) < 2.5f)
    }

    @Test
    fun `bearing glides through north on the short arc from 1 to 359`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5, 73.8, 1.0)
        anim.updateTarget("rider1", 18.5, 73.8, 359.0) // -2° short arc through north

        repeat(20) { anim.advance(FRAME_60HZ) }
        assertEquals("Must converge near 359° on the short arc", 359.0, anim.renderedBearing("rider1")!!, 1.0)
    }

    @Test
    fun `initial bearing never animates from an arbitrary zero value`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5, 73.8, 280.0)
        assertEquals("First fix must snap bearing directly", 280.0, anim.renderedBearing("rider1")!!, EPS)
        anim.advance(FRAME_60HZ)
        assertEquals(280.0, anim.renderedBearing("rider1")!!, EPS)
    }

    @Test
    fun `bearing converges with the 100 ms time constant`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5, 73.8, 0.0)
        anim.updateTarget("rider1", 18.5, 73.8, 90.0) // +90° short arc
        // After 300 ms (≈3 tau): remaining error = 90° * e^-3 ≈ 4.5°
        repeat(18) { anim.advance(FRAME_60HZ) }
        assertEquals(90.0, anim.renderedBearing("rider1")!!, 6.0)
    }

    // ------------------------------------------------------------------
    // Antimeridian behavior
    // ------------------------------------------------------------------

    @Test
    fun `longitude discontinuity around -180 is handled without a globe-wide glide`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 0.0, 179.95, 90.0) // just west of the antimeridian
        anim.updateTarget("rider1", 0.0, -179.95, 90.0) // just east — only ~11 km apart

        repeat(60) { anim.advance(FRAME_60HZ) }
        val lng = anim.renderedLng("rider1")!!
        // Rendered longitude must remain near the antimeridian (no wrap-around glide
        // through the prime meridian), converging toward -179.95.
        val nearEast = abs(lng - (-179.95)) < 0.01
        val nearWest = abs(lng - 179.95) < 0.01
        assertTrue(
            "Rendered longitude must converge across ±180 without flying across the map (lng=$lng)",
            nearEast || nearWest
        )
    }

    // ------------------------------------------------------------------
    // Multi-rider & repeated updates
    // ------------------------------------------------------------------

    @Test
    fun `repeated target updates do not create competing animation states`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 0.0)
        // A burst of GPS updates between frames: only the latest target may matter,
        // and a single advance must keep exactly one coherent rendered state.
        val targetLat = metersNorth(18.5200, 30.0)
        repeat(10) { anim.updateTarget("rider1", targetLat, 73.8567, 90.0) }
        anim.advance(FRAME_60HZ)
        anim.advance(FRAME_60HZ)

        val lat = anim.renderedLat("rider1")!!
        assertTrue(
            "Rendered state must be single and coherent between old and new positions",
            lat > 18.5200 && lat < targetLat
        )
    }

    @Test
    fun `multiple riders animate independently`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 0.0)
        anim.updateTarget("rider2", 18.5300, 73.8600, 180.0)

        val r1Target = metersNorth(18.5200, 40.0)
        anim.updateTarget("rider1", r1Target, 73.8567, 0.0)

        repeat(10) { anim.advance(FRAME_60HZ) }

        assertTrue(anim.renderedLat("rider1")!! < r1Target)
        assertEquals("Rider 2 target untouched", 18.5300, anim.renderedLat("rider2")!!, 1e-4)
        assertEquals(180.0, anim.renderedBearing("rider2")!!, EPS)
    }

    @Test
    fun `retains drops animation state for riders no longer in the group`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5, 73.8, 0.0)
        anim.updateTarget("rider2", 18.6, 73.9, 0.0)
        anim.retain(listOf("rider1"))
        assertNull(anim.renderedLat("rider2"))
        assertEquals(18.5, anim.renderedLat("rider1")!!, EPS)
    }

    // ------------------------------------------------------------------
    // Qualified Bearing: null bearing in a target does not fabricate north
    // ------------------------------------------------------------------

    @Test
    fun `target update with null bearing keeps previous bearing and still advances position`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, 220.0) // first fix with bearing

        // Next fix is further north but provides NO bearing (null)
        val nextLat = metersNorth(18.5200, 40.0)
        anim.updateTarget("rider1", nextLat, 73.8567, null)

        repeat(60) { anim.advance(FRAME_60HZ) }

        assertEquals(
            "Null bearing must preserve the previous qualified bearing (never 0°)",
            220.0, anim.renderedBearing("rider1")!!, EPS
        )
        assertEquals(
            "Position must still glide toward the new target while bearing is unknown",
            nextLat, anim.renderedLat("rider1")!!, 0.5 * LAT_DEG_PER_METER
        )
    }

    @Test
    fun `first target without bearing initializes with arrow-up default and later bearing is adopted`() {
        val anim = RiderMarkerAnimator()
        anim.updateTarget("rider1", 18.5200, 73.8567, null)
        assertEquals(
            "First fix without bearing settles on the neutral 0° arrow rather than garbage",
            0.0, anim.renderedBearing("rider1")!!, EPS
        )

        anim.updateTarget("rider1", 18.5200, 73.8567, 45.0)
        repeat(60) { anim.advance(FRAME_60HZ) }
        assertEquals(
            "Once a qualified bearing exists it must be adopted",
            45.0, anim.renderedBearing("rider1")!!, 1.0
        )
    }

    // ------------------------------------------------------------------
    // Bounded dead reckoning (visual-only marker prediction)
    // ------------------------------------------------------------------

    private fun distanceMeters(
        lat1: Double, lng1: Double, lat2: Double, lng2: Double
    ): Double = LatLng(lat1, lng1).distanceTo(LatLng(lat2, lng2)) * 1000.0

    @Test
    fun `dead reckoning advances the marker between fixes and holds after the 3 s bound`() {
        val anim = RiderMarkerAnimator()
        val fix = LatLng(18.5200, 73.8567)

        // 36 km/h = 10 m/s due east: DR runs while awaiting the next (4 s cadence) fix
        anim.updateTarget("r1", fix.latitude, fix.longitude, 90.0, speedKmh = 36.0, accuracyMeters = 5f)

        // 4 s of frames WITHOUT any new fix: prediction covers 3 s (30 m), then holds
        val totalAdvances = 240 // 240 × (1/60 s) = 4 s
        repeat(totalAdvances) { anim.advance(FRAME_60HZ) }

        val expectedEnd = MarkerSmoothingMath.positionAlongBearing(fix.latitude, fix.longitude, 90.0, 30.0)!!
        val renderedEnd = LatLng(anim.renderedLat("r1")!!, anim.renderedLng("r1")!!)
        assertEquals(
            "Prediction must reach exactly the 3 s bound (30 m at 10 m/s), no further",
            0.0, distanceMeters(expectedEnd.latitude, expectedEnd.longitude, renderedEnd.latitude, renderedEnd.longitude), 0.5
        )

        // Holding: further frames (age 4 s → 6 s) must not move the marker
        val heldLat = anim.renderedLat("r1")!!
        val heldLng = anim.renderedLng("r1")!!
        repeat(120) { anim.advance(FRAME_60HZ) }
        assertEquals("Held prediction must never keep sailing", heldLat, anim.renderedLat("r1")!!, EPS)
        assertEquals("Held prediction must never keep sailing", heldLng, anim.renderedLng("r1")!!, EPS)
    }

    @Test
    fun `teleport fix farther than 50 m from prediction is accepted instantly with no glide`() {
        val anim = RiderMarkerAnimator()
        val fix = LatLng(18.5200, 73.8567)

        anim.updateTarget("r1", fix.latitude, fix.longitude, 90.0, speedKmh = 36.0, accuracyMeters = 5f)
        anim.advance(2.0) // prediction is now ~20 m east of the anchor fix

        // Next fix lands ~120 m NORTH of the anchor — way beyond the 50 m teleport bound
        val jumpedFix = LatLng(metersNorth(fix.latitude, 120.0), fix.longitude)
        anim.updateTarget("r1", jumpedFix.latitude, jumpedFix.longitude, 0.0, speedKmh = 36.0, accuracyMeters = 5f)

        // Zero gliding frames since the fix: rendered state must ALREADY be on the fix
        val rendered = LatLng(anim.renderedLat("r1")!!, anim.renderedLng("r1")!!)
        assertEquals(
            "Teleport-accept must snap the rendered marker to the fix with no glide",
            0.0, distanceMeters(jumpedFix.latitude, jumpedFix.longitude, rendered.latitude, rendered.longitude), 0.1
        )
    }

    @Test
    fun `dead reckoning is gated off for slow fixes missing bearings poor accuracy`() {
        val anim = RiderMarkerAnimator()
        val fix = LatLng(18.5200, 73.8567)

        // Below the 5.4 km/h speed gate
        anim.updateTarget("r1", fix.latitude, fix.longitude, 90.0, speedKmh = 4.0, accuracyMeters = 5f)
        repeat(120) { anim.advance(FRAME_60HZ) }
        assertEquals("Slow fix must not trigger prediction", fix.latitude, anim.renderedLat("r1")!!, EPS)
        assertEquals("Slow fix must not trigger prediction", fix.longitude, anim.renderedLng("r1")!!, EPS)

        // Missing qualified bearing (null)
        anim.updateTarget("r2", fix.latitude, fix.longitude, null, speedKmh = 60.0, accuracyMeters = 5f)
        repeat(120) { anim.advance(FRAME_60HZ) }
        assertEquals("Fix without bearing must not trigger prediction", fix.latitude, anim.renderedLat("r2")!!, EPS)
        assertEquals("Fix without bearing must not trigger prediction", fix.longitude, anim.renderedLng("r2")!!, EPS)

        // Poor accuracy (25 m > 20 m gate)
        anim.updateTarget("r3", fix.latitude, fix.longitude, 90.0, speedKmh = 60.0, accuracyMeters = 25f)
        repeat(120) { anim.advance(FRAME_60HZ) }
        assertEquals("Coarse fix must not trigger prediction", fix.latitude, anim.renderedLat("r3")!!, EPS)
        assertEquals("Coarse fix must not trigger prediction", fix.longitude, anim.renderedLng("r3")!!, EPS)
    }
}
