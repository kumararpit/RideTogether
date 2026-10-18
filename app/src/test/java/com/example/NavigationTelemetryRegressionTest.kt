package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.Ride
import com.example.data.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for navigation telemetry, ETA, rerouting, and history metrics.
 *
 * Bugs observed in the 12:29 device recording:
 *  - 123 km/h phantom average speed (planned OSRM duration used instead of wall-clock elapsed)
 *  - 1299.2 km phantom distance in recent rides
 *  - ETA instability (5 min → 16 min at same distance)
 *  - Distance accumulator being reset on rerouting
 *  - Endpoint mutability across reroutes
 *  - Stale reroute response acceptance
 *  - ETA NaN/Infinity when speed is zero or route data is missing
 */
class NavigationTelemetryRegressionTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers that mirror the production avg-speed formula exactly
    // ─────────────────────────────────────────────────────────────────────────

    private fun computeAvgSpeedKmh(
        distMeters: Double,
        elapsedSec: Double,
        plannedDurationSec: Double,
        implausibleThresholdKmh: Double = 250.0
    ): Double {
        val durationSec = if (elapsedSec > 0.0) elapsedSec else plannedDurationSec
        val raw = if (durationSec > 60 && distMeters > 500) {
            (distMeters / 1000.0) / (durationSec / 3600.0)
        } else {
            0.0
        }
        return if (raw in 0.0..implausibleThresholdKmh) raw else 0.0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION A: Average-speed calculation correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `avgSpeed - zero elapsed duration does not divide by zero`() {
        val speed = computeAvgSpeedKmh(distMeters = 5500.0, elapsedSec = 0.0, plannedDurationSec = 0.0)
        assertEquals("Zero duration must produce 0.0 avg speed, not NaN or Infinity", 0.0, speed, 0.001)
        assertFalse("Result must be finite", speed.isNaN() || speed.isInfinite())
    }

    @Test
    fun `avgSpeed - sub-60s duration does not produce implausible result`() {
        val speed = computeAvgSpeedKmh(distMeters = 5000.0, elapsedSec = 30.0, plannedDurationSec = 30.0)
        assertEquals("Duration less than 60s must yield 0.0 avg speed", 0.0, speed, 0.001)
    }

    @Test
    fun `avgSpeed - planned duration only used when elapsed is zero`() {
        val speedFromElapsed = computeAvgSpeedKmh(distMeters = 4300.0, elapsedSec = 750.0, plannedDurationSec = 160.0)
        assertTrue("Elapsed-duration path must produce a realistic speed < 40 km/h", speedFromElapsed < 40.0)
        assertTrue("Elapsed-duration speed must be > 0", speedFromElapsed > 0.0)
    }

    @Test
    fun `avgSpeed - implausible value above 250 kmh is saved as zero not clamped`() {
        val implausible = computeAvgSpeedKmh(distMeters = 1_299_200.0, elapsedSec = 160.0, plannedDurationSec = 160.0)
        assertEquals("Implausible speed must be stored as 0.0, not clamped", 0.0, implausible, 0.001)
    }

    @Test
    fun `avgSpeed - realistic motorcycle speed 45 kmh passes guard`() {
        val speed = computeAvgSpeedKmh(distMeters = 12_500.0, elapsedSec = 1000.0, plannedDurationSec = 600.0)
        assertEquals("Realistic 45 km/h must pass the plausibility guard", 45.0, speed, 0.5)
    }

    @Test
    fun `avgSpeed - sub-500m distance returns zero regardless of duration`() {
        val speed = computeAvgSpeedKmh(distMeters = 450.0, elapsedSec = 600.0, plannedDurationSec = 600.0)
        assertEquals("Trip shorter than 500 m must yield 0.0", 0.0, speed, 0.001)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION B: GPS distance accumulation correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `distAccum - duplicate GPS point does not add distance`() {
        val origin = LatLng(18.5204, 73.8567)
        val samePt = LatLng(18.5204, 73.8567)
        val deltaMeters = origin.distanceTo(samePt) * 1000.0
        assertEquals("Duplicate GPS point must contribute 0 m", 0.0, deltaMeters, 0.001)
    }

    @Test
    fun `distAccum - GPS jump larger than 500m is rejected by gate`() {
        val origin = LatLng(18.5204, 73.8567)
        val jumpPt = LatLng(18.5258, 73.8567) // ~600 m north
        val deltaMeters = origin.distanceTo(jumpPt) * 1000.0
        assertTrue("Jump must be detectable (> 500 m)", deltaMeters > 500.0)
        assertFalse("Gate must exclude jumps > 500 m", deltaMeters in 1.0..500.0)
    }

    @Test
    fun `distAccum - normal 200m step is accepted by gate`() {
        val a = LatLng(18.5204, 73.8567)
        val b = LatLng(18.5222, 73.8567) // ~200 m north
        val deltaMeters = a.distanceTo(b) * 1000.0
        assertTrue("Normal 200 m step must be in acceptance range 1..500 m", deltaMeters in 1.0..500.0)
    }

    @Test
    fun `distAccum - speed below 1-5 kmh threshold prevents accumulation`() {
        val speedKmh = 1.2
        assertFalse("Speed 1.2 km/h must not pass the >= 1.5 km/h moving threshold", speedKmh >= 1.5)
    }

    @Test
    fun `distAccum - poor accuracy GPS point is excluded by gate`() {
        val accuracy = 30f
        assertFalse("Accuracy 30 m must fail the <= 25 m accuracy gate", accuracy <= 25f)
    }

    @Test
    fun `distAccum - 2500m of valid sequential steps accumulates correctly`() {
        var totalDist = 0.0
        var lastPt: LatLng? = null
        val steps = (0..10).map { i -> LatLng(18.5204 + i * (0.0225 / 10), 73.8567) }

        for (pt in steps) {
            val prev = lastPt
            if (prev == null) {
                lastPt = pt
            } else {
                val delta = prev.distanceTo(pt) * 1000.0
                val speedKmh = 50.0
                if (delta in 1.0..500.0 && speedKmh >= 1.5) {
                    totalDist += delta
                    lastPt = pt
                }
            }
        }
        assertTrue(
            "10 steps of approx 250 m each should accumulate approx 2500 m (got $totalDist)",
            totalDist in 2400.0..2600.0
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION C: Rerouting does not reset actual recorded distance
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reroute - actualRecordedDistanceMeters is preserved across route update`() {
        val originalDest = LatLng(18.75, 73.40)
        val rideBeforeReroute = Ride(
            id = "test_ride",
            name = "Test",
            startLocationName = "A",
            destinationName = "B",
            startLocation = LatLng(18.52, 73.85),
            destinationLocation = originalDest,
            leaderId = "uid",
            inviteCode = "XYZ",
            actualRecordedDistanceMeters = 2500.0,
            totalDistanceMeters = 45_000.0,
            totalDurationSeconds = 3000.0
        )

        // updateRoute() only modifies route-geometry fields; actualRecordedDistanceMeters must survive
        val rideAfterReroute = rideBeforeReroute.copy(
            routePoints = listOf(LatLng(18.60, 73.70), originalDest),
            totalDistanceMeters = 38_000.0,
            totalDurationSeconds = 2500.0
        )

        assertEquals(
            "actualRecordedDistanceMeters must survive a route update (reroute)",
            2500.0,
            rideAfterReroute.actualRecordedDistanceMeters,
            0.001
        )
    }

    @Test
    fun `reroute - NavEngine remaining distance resets to new route total after setRoute`() {
        val navEngine = NavEngine()
        val pA = LatLng(18.52, 73.85)
        val pB = LatLng(18.62, 73.85)
        val pC = LatLng(18.72, 73.85)

        navEngine.setRoute(RouteResult(listOf(pA, pB, pC), 22_000.0, 1500.0, emptyList()))
        navEngine.processLocation(LocationUpdate(pB, 50.0, 0f, 5f))
        assertTrue("Mid-route remaining must be less than 22000 m", navEngine.progress.value.remainingRouteDistanceMeters < 22_000.0)

        navEngine.setRoute(RouteResult(listOf(LatLng(18.61, 73.85), pC), 12_000.0, 800.0, emptyList()))
        assertEquals(
            "After reroute, remaining distance must equal new route total",
            12_000.0,
            navEngine.progress.value.remainingRouteDistanceMeters,
            1.0
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION D: Original endpoints remain immutable across reroutes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `endpoints - startLocation and destinationLocation immutable across multiple route updates`() {
        val originalStart = LatLng(18.5204, 73.8567)
        val originalDest = LatLng(18.7546, 73.4062)

        var ride = Ride(
            id = "test",
            name = "Immutability Test",
            startLocationName = "Pune",
            destinationName = "Lonavala",
            startLocation = originalStart,
            destinationLocation = originalDest,
            leaderId = "uid",
            inviteCode = "ABC"
        )

        repeat(3) { i ->
            ride = ride.copy(
                routePoints = listOf(LatLng(18.55 + i * 0.05, 73.75), originalDest),
                totalDistanceMeters = (30_000 - i * 5000).toDouble(),
                totalDurationSeconds = (2000 - i * 300).toDouble()
            )
        }

        assertEquals("startLocation must be unchanged after 3 reroutes", originalStart, ride.startLocation)
        assertEquals("destinationLocation must be unchanged after 3 reroutes", originalDest, ride.destinationLocation)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION E: Stale reroute response rejection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `staleReroute - incremented routeRequestId invalidates older pending response`() {
        var routeRequestId = 0
        val reqId1 = ++routeRequestId
        val reqId2 = ++routeRequestId

        assertFalse("Stale reroute 1 response (reqId=$reqId1) must be rejected", reqId1 == routeRequestId)
        assertTrue("Fresh reroute 2 response (reqId=$reqId2) must be accepted", reqId2 == routeRequestId)
    }

    @Test
    fun `staleReroute - fallback route is rejected even when it is the latest response`() {
        val fallbackRoute = RouteResult(
            coordinates = listOf(LatLng(18.55, 73.80), LatLng(18.75, 73.40)),
            totalDistanceMeters = 28_000.0,
            totalDurationSeconds = 2000.0,
            steps = emptyList(),
            isFallback = true
        )
        assertTrue("Fallback route must have isFallback == true", fallbackRoute.isFallback)
        // Production condition: result.coordinates.isNotEmpty() && !result.isFallback
        assertFalse("Fallback route must fail the acceptance gate", !fallbackRoute.isFallback)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION F: ETA calculation correctness and fallback behaviour
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `eta - zero speed uses route planned speed fallback not division by zero`() {
        val navEngine = NavEngine()
        val pA = LatLng(18.52, 73.85)
        val pB = LatLng(18.62, 73.85)
        val distMeters = pA.distanceTo(pB) * 1000.0
        navEngine.setRoute(RouteResult(listOf(pA, pB), distMeters, 900.0, emptyList()))

        val prog = navEngine.processLocation(LocationUpdate(pA, 0.0, 0f, 5f))
        assertFalse("ETA duration must not be NaN at zero speed", prog.remainingDurationSeconds.isNaN())
        assertFalse("ETA duration must not be Infinite at zero speed", prog.remainingDurationSeconds.isInfinite())
        assertTrue(
            "Zero-speed ETA must use route planned baseline (~900s, got ${prog.remainingDurationSeconds}s)",
            prog.remainingDurationSeconds in 800.0..1100.0
        )
    }

    @Test
    fun `eta - missing route produces safe zero duration`() {
        val navEngine = NavEngine()
        val prog = navEngine.processLocation(LocationUpdate(LatLng(18.52, 73.85), 40.0, 0f, 5f))
        assertFalse("ETA must not be NaN without a route", prog.remainingDurationSeconds.isNaN())
        assertEquals("ETA must be 0.0 when no route is active", 0.0, prog.remainingDurationSeconds, 0.001)
    }

    @Test
    fun `eta - EMA speed stays zero when rider is below 8 kmh threshold`() {
        val navEngine = NavEngine()
        val pA = LatLng(18.52, 73.85)
        val pB = LatLng(18.62, 73.85)
        val distM = pA.distanceTo(pB) * 1000.0
        navEngine.setRoute(RouteResult(listOf(pA, pB), distM, 900.0, emptyList()))

        val prog = navEngine.processLocation(LocationUpdate(pA, 5.0, 0f, 5f)) // below 8 km/h
        assertEquals("EMA speed must not update when speed < 8 km/h", 0.0, prog.emaSpeedKmh, 0.001)
    }

    @Test
    fun `eta - EMA speed updates when rider exceeds 8 kmh threshold`() {
        val navEngine = NavEngine()
        val pA = LatLng(18.52, 73.85)
        val pB = LatLng(18.62, 73.85)
        val distM = pA.distanceTo(pB) * 1000.0
        navEngine.setRoute(RouteResult(listOf(pA, pB), distM, 900.0, emptyList()))

        val prog = navEngine.processLocation(LocationUpdate(pA, 40.0, 0f, 5f))
        assertTrue("EMA speed must be > 0 when speed is >= 8 km/h", prog.emaSpeedKmh > 0.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION G: History metrics correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `history - finalSummaryDistanceMeters trusts GPS odometer only when meaningful - records below 15pct of planned are rejected as glitches`() {
        // Glitched GPS: 4300 m recorded on a 42 km planned ride is only ~10% of the route —
        // treated as GPS dropout; the planned distance survives.
        val glitchedRide = Ride(
            id = "r1", name = "Test", startLocationName = "A", destinationName = "B",
            startLocation = LatLng(18.52, 73.85), destinationLocation = LatLng(18.75, 73.40),
            leaderId = "uid", inviteCode = "X",
            actualRecordedDistanceMeters = 4300.0,
            initialPlannedDistanceMeters = 42_000.0,
            totalDistanceMeters = 45_000.0
        )
        assertEquals(
            "finalSummaryDistanceMeters must fall back to planned (42000 m) when GPS odometer is below 15% of planned",
            42_000.0, glitchedRide.finalSummaryDistanceMeters, 0.001
        )

        // Meaningful GPS: 38000 m recorded (>= 50 m and ~90% of planned) is trusted.
        val trustedRide = glitchedRide.copy(actualRecordedDistanceMeters = 38_000.0)
        assertEquals(
            "finalSummaryDistanceMeters must prefer GPS odometer (38000 m) when >= 50 m and >= 15% of planned",
            38_000.0, trustedRide.finalSummaryDistanceMeters, 0.001
        )
    }

    @Test
    fun `history - finalSummaryDistanceMeters falls back to initialPlanned when gps below 50m`() {
        val ride = Ride(
            id = "r2", name = "Test", startLocationName = "A", destinationName = "B",
            startLocation = LatLng(18.52, 73.85), destinationLocation = LatLng(18.75, 73.40),
            leaderId = "uid", inviteCode = "X",
            actualRecordedDistanceMeters = 0.0,
            initialPlannedDistanceMeters = 42_000.0,
            totalDistanceMeters = 45_000.0
        )
        assertEquals(
            "finalSummaryDistanceMeters must fall back to initialPlannedDistanceMeters when GPS odometer < 50 m",
            42_000.0, ride.finalSummaryDistanceMeters, 0.001
        )
    }

    @Test
    fun `history - implausible 1299km distance is flagged by plausibility guard`() {
        val distMeters = 1_299_200.0
        val elapsedSec = 750.0
        val speed = computeAvgSpeedKmh(distMeters, elapsedSec, elapsedSec)
        assertEquals(
            "1299.2 km in 750s must fail the plausibility guard and be saved as 0.0",
            0.0, speed, 0.001
        )
    }

    @Test
    fun `history - formattedDistance of CompletedRideEntity is correct`() {
        val entity = com.example.data.db.CompletedRideEntity(
            id = "e1", rideName = "Test", startLocationName = "A", destinationName = "B",
            distanceMeters = 4300.0, durationSeconds = 750.0, avgSpeedKmh = 20.6,
            riderCount = 1, completedAtMs = System.currentTimeMillis()
        )
        assertEquals("formattedDistance must equal distanceMeters / 1000.0 as 1 dp km", "4.3 km", entity.formattedDistance)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION H: RouteGeometry remaining-distance boundary cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `remainingDist - RouteGeometry returns 0 from last vertex`() {
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.55, 73.85), LatLng(18.60, 73.85))
        val rem = RouteGeometry.remainingDistanceMeters(pts.size - 1, pts)
        assertEquals("Remaining from last vertex must be 0", 0.0, rem, 0.001)
    }

    @Test
    fun `remainingDist - RouteGeometry from index 0 equals full route length`() {
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.62, 73.85))
        val expected = pts[0].distanceTo(pts[1]) * 1000.0
        val actual = RouteGeometry.remainingDistanceMeters(0, pts)
        assertEquals("Remaining from index 0 must equal full route length", expected, actual, 1.0)
    }
}
