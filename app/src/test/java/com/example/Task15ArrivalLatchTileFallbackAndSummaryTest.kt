package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.Ride
import com.example.data.model.RideStatus
import com.example.data.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 15 — Production-readiness regression tests:
 * 1. Arrival state latch in NavEngine (no post-destination reroute loop).
 * 2. Multi-level ancestor tile fallback math (tile rendering fallback chain).
 * 3. finalSummaryDistanceMeters heuristic (GPS dropout protection).
 */
class Task15ArrivalLatchTileFallbackAndSummaryTest {

    // ------------------------------------------------------------------
    // 1. Arrival Latch — NavEngine
    // ------------------------------------------------------------------
    @Test
    fun `arrival latch - once within 35m of destination later fixes 100m past pin stay arrived`() {
        val navEngine = NavEngine()

        // Straight ~1.11 km segments heading north; last point is the destination
        val p1 = LatLng(18.5000, 73.8500)
        val p2 = LatLng(18.5100, 73.8500)
        val p3 = LatLng(18.5200, 73.8500)
        val p4 = LatLng(18.5300, 73.8500)
        val dest = LatLng(18.5400, 73.8500)
        val points = listOf(p1, p2, p3, p4, dest)
        val totalDist = RouteGeometry.remainingDistanceMeters(0, points)

        val route = RouteResult(
            coordinates = points,
            totalDistanceMeters = totalDist,
            totalDurationSeconds = 900.0,
            steps = listOf(
                NavigationStep("Head north", "Road A", totalDist * 0.5, 450.0, "depart", "straight"),
                NavigationStep("Continue north", "Road A", totalDist * 0.5, 450.0, "turn", "straight")
            )
        )
        navEngine.setRoute(route)

        // Ride along the route mid-way
        val midProgress = navEngine.processLocation(
            LocationUpdate(p3, speedKmh = 40.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertFalse("Mid-ride the rider must not yet be arrived", midProgress.isArrived)

        // Arrive: fix ~20 m past the destination pin along the same road bearing
        val pastPin = LatLng(dest.latitude + 0.00018, dest.longitude)
        val arrivedProgress = navEngine.processLocation(
            LocationUpdate(pastPin, speedKmh = 30.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("Rider within 35 m of destination must latch arrival", arrivedProgress.isArrived)

        // GPS jitter: fix 100 m past the pin must NOT un-latch arrival or fire off-route
        val pastPinFar = LatLng(dest.latitude + 0.0009, dest.longitude)
        val postArrivalProgress = navEngine.processLocation(
            LocationUpdate(pastPinFar, speedKmh = 25.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue("Arrival latch must remain locked", postArrivalProgress.isArrived)
        assertFalse("Arrived rider must never be flagged off-route", postArrivalProgress.isOffRoute)
        assertEquals(
            "Remaining route distance must be clamped to 0 after arrival",
            0.0,
            postArrivalProgress.remainingRouteDistanceMeters,
            0.0001
        )
        assertEquals(
            "Distance to next maneuver must be clamped to 0 after arrival",
            0.0,
            postArrivalProgress.distanceToNextManeuverMeters,
            0.0001
        )

        // Even a wildly off-course fix 400 m laterally cannot escape the latch
        val lateralFix = LatLng(dest.latitude, dest.longitude + 0.0036)
        val lateralProgress = navEngine.processLocation(
            LocationUpdate(lateralFix, speedKmh = 20.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertTrue("Latch survives large lateral GPS drift", lateralProgress.isArrived)
        assertFalse(lateralProgress.isOffRoute)
        assertEquals(0.0, lateralProgress.remainingRouteDistanceMeters, 0.0001)
    }

    @Test
    fun `arrival latch - resets only on reset() allowing a fresh ride session`() {
        val navEngine = NavEngine()

        val p1 = LatLng(18.5000, 73.8500)
        val dest = LatLng(18.5400, 73.8500)
        val points = listOf(p1, LatLng(18.5200, 73.8500), dest)
        val totalDist = RouteGeometry.remainingDistanceMeters(0, points)

        val route = RouteResult(
            coordinates = points,
            totalDistanceMeters = totalDist,
            totalDurationSeconds = 600.0,
            steps = listOf(NavigationStep("Head north", "Road A", totalDist, 600.0, "depart", "straight"))
        )
        navEngine.setRoute(route)

        val near = LatLng(dest.latitude + 0.0002, dest.longitude)
        val arrived = navEngine.processLocation(
            LocationUpdate(near, speedKmh = 30.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue(arrived.isArrived)

        // New ride session
        navEngine.reset()
        navEngine.setRoute(route)

        val freshSession = navEngine.processLocation(
            LocationUpdate(p1, speedKmh = 40.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertFalse("After reset() a new session must start un-arrived", freshSession.isArrived)
    }

    // ------------------------------------------------------------------
    // 2. Multi-level ancestor tile fallback math
    // ------------------------------------------------------------------
    @Test
    fun `multi-level ancestor tile math - quadrants and source scaling for deltaZ 1 to 4`() {
        val tileZoom = 17
        val x = 42 // 0b00101010
        val y = 7  // 0b00000111

        // deltaZ = 1
        val a1 = TileMath.ancestorTile(tileZoom, x, y, 1)!!
        assertEquals(16, a1.zoom)
        assertEquals(x shr 1, a1.x)   // 21
        assertEquals(y shr 1, a1.y)   // 3
        assertEquals(x and 1, a1.subX)      // 0
        assertEquals(y and 1, a1.subY)      // 1
        assertEquals(128, a1.srcSize)
        assertEquals(0, a1.srcOffsetX)      // subX * srcSize
        assertEquals(128, a1.srcOffsetY)

        // deltaZ = 2
        val a2 = TileMath.ancestorTile(tileZoom, x, y, 2)!!
        assertEquals(15, a2.zoom)
        assertEquals(x shr 2, a2.x)   // 10
        assertEquals(y shr 2, a2.y)   // 1
        assertEquals(2, a2.subX)      // x and 0b11
        assertEquals(3, a2.subY)      // y and 0b11
        assertEquals(64, a2.srcSize)
        assertEquals(128, a2.srcOffsetX)
        assertEquals(192, a2.srcOffsetY)

        // deltaZ = 3
        val a3 = TileMath.ancestorTile(tileZoom, x, y, 3)!!
        assertEquals(14, a3.zoom)
        assertEquals(x shr 3, a3.x)   // 5
        assertEquals(y shr 3, a3.y)   // 0
        assertEquals(2, a3.subX)      // x and 0b111
        assertEquals(7, a3.subY)
        assertEquals(32, a3.srcSize)
        assertEquals(64, a3.srcOffsetX)
        assertEquals(224, a3.srcOffsetY)

        // deltaZ = 4
        val a4 = TileMath.ancestorTile(tileZoom, x, y, 4)!!
        assertEquals(13, a4.zoom)
        assertEquals(x shr 4, a4.x)   // 2
        assertEquals(0, a4.y)
        assertEquals(10, a4.subX)     // x and 0b1111
        assertEquals(7, a4.subY)
        assertEquals(16, a4.srcSize)
        assertEquals(160, a4.srcOffsetX)
        assertEquals(112, a4.srcOffsetY)

        // deltaZ beyond the min zoom floor resolves to null
        assertEquals(null, TileMath.ancestorTile(3, x, y, 3, minZoom = 3))
    }

    @Test
    fun `ancestor fallback chain - coarsest first and stops below minZoom`() {
        // Chain for zoom 17 always includes ancestors down to zoom 13 (default minZoom = 3)
        val chain = TileMath.ancestorFallbackChain(17, 42, 7)
        assertEquals(listOf(4, 3, 2, 1), chain.map { it.deltaZ })
        assertEquals(listOf(13, 14, 15, 16), chain.map { it.zoom })

        // Near the minimum zoom, deltas that would go below minZoom are dropped
        val lowChain = TileMath.ancestorFallbackChain(4, 2, 2, minZoom = 3)
        assertEquals(listOf(1), lowChain.map { it.deltaZ })
        assertEquals(3, lowChain.first().zoom)
    }

    // ------------------------------------------------------------------
    // 3. finalSummaryDistanceMeters heuristic
    // ------------------------------------------------------------------
    private fun rideWith(planned: Double, recorded: Double): Ride = Ride(
        name = "Test Ride",
        startLocationName = "Pune",
        destinationName = "Lonavala",
        startLocation = LatLng(18.5204, 73.8567),
        destinationLocation = LatLng(18.7459, 73.6753),
        leaderId = "leader",
        inviteCode = "ABC123",
        status = RideStatus.COMPLETED,
        initialPlannedDistanceMeters = planned,
        totalDistanceMeters = 0.0,
        actualRecordedDistanceMeters = recorded
    )

    @Test
    fun `summary distance - glitched 100m GPS recording on 7_5km planned ride falls back to planned`() {
        val ride = rideWith(planned = 7500.0, recorded = 100.0)
        assertEquals("GPS dropout must not overwrite planned distance", 7500.0, ride.finalSummaryDistanceMeters, 0.0001)
    }

    @Test
    fun `summary distance - meaningful recorded 6900m GPS is trusted over planned`() {
        val ride = rideWith(planned = 7500.0, recorded = 6900.0)
        assertEquals(6900.0, ride.finalSummaryDistanceMeters, 0.0001)
    }

    @Test
    fun `summary distance - over-recorded 7700m GPS is trusted over planned`() {
        val ride = rideWith(planned = 7500.0, recorded = 7700.0)
        assertEquals(7700.0, ride.finalSummaryDistanceMeters, 0.0001)
    }

    @Test
    fun `summary distance - 15 percent threshold boundary`() {
        // Exactly 15% of planned (1125 m): accepted recorded odometer
        val atThreshold = rideWith(planned = 7500.0, recorded = 1125.0)
        assertEquals(1125.0, atThreshold.finalSummaryDistanceMeters, 0.0001)

        // Just below the threshold (1000 m < 1125 m): planned distance wins
        val belowThreshold = rideWith(planned = 7500.0, recorded = 1000.0)
        assertEquals(7500.0, belowThreshold.finalSummaryDistanceMeters, 0.0001)
    }

    @Test
    fun `summary distance - no planned route falls back to recorded odometer`() {
        val ride = rideWith(planned = 0.0, recorded = 6200.0)
        assertEquals(6200.0, ride.finalSummaryDistanceMeters, 0.0001)
    }
}
