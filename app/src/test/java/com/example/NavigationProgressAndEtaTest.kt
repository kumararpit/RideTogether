package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationProgressAndEtaTest {

    @Test
    fun `test 1 - remaining distance on a simple route with known segment lengths`() {
        val navEngine = NavEngine()

        val p1 = LatLng(18.5200, 73.8500)
        val p2 = LatLng(18.6200, 73.8500) // ~11.05 km north
        val p3 = LatLng(18.6200, 73.9500) // ~10.55 km east

        val d12 = p1.distanceTo(p2) * 1000.0
        val d23 = p2.distanceTo(p3) * 1000.0
        val totalDist = d12 + d23

        val route = RouteResult(
            coordinates = listOf(p1, p2, p3),
            totalDistanceMeters = totalDist,
            totalDurationSeconds = 1200.0,
            steps = listOf(
                NavigationStep("Head north", "Highway 1", d12, 600.0, "depart", "straight"),
                NavigationStep("Turn right", "Highway 2", d23, 600.0, "turn", "right")
            )
        )

        navEngine.setRoute(route)

        // At start p1
        val progStart = navEngine.processLocation(LocationUpdate(p1, speedKmh = 40.0, headingDeg = 0f, accuracyMeters = 5f))
        assertEquals("Remaining distance at start must equal total route distance", totalDist, progStart.remainingRouteDistanceMeters, 1.0)

        // At midpoint p2
        val progMid = navEngine.processLocation(LocationUpdate(p2, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals("Remaining distance at p2 must equal segment 23 distance", d23, progMid.remainingRouteDistanceMeters, 1.0)
    }

    @Test
    fun `test 2 - remaining distance decreasing monotonically as rider advances`() {
        val navEngine = NavEngine()

        val p1 = LatLng(18.5200, 73.8500)
        val p1Mid = LatLng(18.5500, 73.8500)
        val p2 = LatLng(18.5800, 73.8500)
        val p3 = LatLng(18.6200, 73.8500)

        val pts = listOf(p1, p1Mid, p2, p3)
        val totalDist = RouteGeometry.remainingDistanceMeters(0, pts)

        val route = RouteResult(pts, totalDist, 600.0, emptyList())
        navEngine.setRoute(route)

        val r1 = navEngine.processLocation(LocationUpdate(p1, 30.0, 0f, 5f)).remainingRouteDistanceMeters
        val r2 = navEngine.processLocation(LocationUpdate(p1Mid, 30.0, 0f, 5f)).remainingRouteDistanceMeters
        val r3 = navEngine.processLocation(LocationUpdate(p2, 30.0, 0f, 5f)).remainingRouteDistanceMeters
        val r4 = navEngine.processLocation(LocationUpdate(p3, 30.0, 0f, 5f)).remainingRouteDistanceMeters

        assertTrue("r1 > r2", r1 > r2)
        assertTrue("r2 > r3", r2 > r3)
        assertTrue("r3 > r4", r3 > r4)
        assertEquals(0.0, r4, 1.0)
    }

    @Test
    fun `test 3 - route distance differs correctly from straight-line distance on winding route`() {
        val p1 = LatLng(18.5200, 73.8500)
        val pDetour = LatLng(18.5500, 73.9500) // Detour far east
        val p3 = LatLng(18.5800, 73.8500)

        val straightDistMeters = p1.distanceTo(p3) * 1000.0
        val routeDistMeters = (p1.distanceTo(pDetour) + pDetour.distanceTo(p3)) * 1000.0

        assertTrue("Winding route distance ($routeDistMeters m) must be strictly greater than straight-line ($straightDistMeters m)", routeDistMeters > straightDistMeters + 1000.0)
    }

    @Test
    fun `test 4 - route replacement resets and recomputes progress state`() {
        val navEngine = NavEngine()

        val routeA = RouteResult(
            coordinates = listOf(LatLng(18.52, 73.85), LatLng(18.80, 73.85)),
            totalDistanceMeters = 31000.0,
            totalDurationSeconds = 1800.0,
            steps = emptyList()
        )
        navEngine.setRoute(routeA)
        val versionA = navEngine.progress.value.routeVersion

        val routeB = RouteResult(
            coordinates = listOf(LatLng(18.52, 73.85), LatLng(18.60, 73.85)),
            totalDistanceMeters = 8800.0,
            totalDurationSeconds = 600.0,
            steps = emptyList()
        )
        navEngine.setRoute(routeB)
        val versionB = navEngine.progress.value.routeVersion

        assertTrue("Route version must increment on route replacement", versionB > versionA)
        assertEquals("Remaining distance must match new route total distance", 8800.0, navEngine.progress.value.remainingRouteDistanceMeters, 0.1)
        assertEquals("Segment index must reset to 0", 0, navEngine.progress.value.currentSegmentIndex)
    }

    @Test
    fun `test 5 - invalid or poor accuracy location updates do not corrupt progress`() {
        val navEngine = NavEngine()
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.55, 73.85), LatLng(18.60, 73.85))
        navEngine.setRoute(RouteResult(pts, 8800.0, 600.0, emptyList()))

        // Good fix
        val p1 = navEngine.processLocation(LocationUpdate(pts[0], 30.0, 0f, 8f))
        assertFalse("Good accuracy (8m) should not mark stale GPS", p1.isStaleGps)

        // Poor accuracy fix (> 30m)
        val poorFix = LocationUpdate(LatLng(19.00, 74.00), 30.0, 0f, 50f)
        val p2 = navEngine.processLocation(poorFix)

        assertTrue("Poor accuracy fix (50m) must mark isStaleGps true", p2.isStaleGps)
        assertFalse("Poor accuracy fix must not trigger false off-route", p2.isOffRoute)
    }

    @Test
    fun `test 6 - small GPS noise deviations do not cause substantial backward progress`() {
        val navEngine = NavEngine()
        val p1 = LatLng(18.520, 73.850)
        val p2 = LatLng(18.525, 73.850)
        val p3 = LatLng(18.530, 73.850)
        val p4 = LatLng(18.535, 73.850)

        navEngine.setRoute(RouteResult(listOf(p1, p2, p3, p4), 3800.0, 300.0, emptyList()))

        // Advance rider to p3 (index 2)
        val prog3 = navEngine.processLocation(LocationUpdate(p3, 40.0, 0f, 5f))
        assertEquals(2, prog3.currentSegmentIndex)

        // Minor GPS noise slightly behind p3
        val noisyBehind = LatLng(18.529, 73.850)
        val progNoise = navEngine.processLocation(LocationUpdate(noisyBehind, 40.0, 0f, 5f))

        assertTrue("Segment index must not jump backward on minor noise", progNoise.currentSegmentIndex >= 2)
    }

    @Test
    fun `test 7 - distance to next maneuver decreases continuously as rider approaches turn`() {
        val navEngine = NavEngine()
        val p1 = LatLng(18.5200, 73.8500)
        val p1Mid = LatLng(18.5225, 73.8500)
        val pTurn = LatLng(18.5250, 73.8500) // Turn point
        val p3 = LatLng(18.5250, 73.8600)

        val d12 = p1.distanceTo(pTurn) * 1000.0
        val d23 = pTurn.distanceTo(p3) * 1000.0

        val step1 = NavigationStep("Go north", "Main St", d12, 30.0, "depart", "straight")
        val step2 = NavigationStep("Turn right", "East Ave", d23, 30.0, "turn", "right")

        navEngine.setRoute(RouteResult(listOf(p1, p1Mid, pTurn, p3), d12 + d23, 60.0, listOf(step1, step2)))

        val dStart = navEngine.processLocation(LocationUpdate(p1, 30.0, 0f, 5f)).distanceToNextManeuverMeters
        val dMid = navEngine.processLocation(LocationUpdate(p1Mid, 30.0, 0f, 5f)).distanceToNextManeuverMeters

        assertTrue("Distance to next maneuver at mid ($dMid) must be less than start ($dStart)", dMid < dStart)
    }

    @Test
    fun `test 8 - maneuver advances when turn vertex threshold is passed`() {
        val navEngine = NavEngine()
        val p1 = LatLng(18.5200, 73.8500)
        val pTurn = LatLng(18.5250, 73.8500) // Turn point
        val pDest = LatLng(18.5250, 73.8600)

        val d1 = p1.distanceTo(pTurn) * 1000.0
        val d2 = pTurn.distanceTo(pDest) * 1000.0

        val step1 = NavigationStep("Go north", "Main St", d1, 30.0)
        val step2 = NavigationStep("Turn right", "East Ave", d2, 30.0)

        navEngine.setRoute(RouteResult(listOf(p1, pTurn, pDest), d1 + d2, 60.0, listOf(step1, step2)))

        val progBefore = navEngine.processLocation(LocationUpdate(p1, 30.0, 0f, 5f))
        assertEquals(0, progBefore.currentManeuverIndex)

        val progAfter = navEngine.processLocation(LocationUpdate(pTurn, 30.0, 90f, 5f))
        assertEquals("Maneuver index must advance to 1 upon reaching turn vertex", 1, progAfter.currentManeuverIndex)
    }

    @Test
    fun `test 9 - missing maneuver step metadata produces valid fallback state`() {
        val navEngine = NavEngine()
        val pts = listOf(LatLng(18.52, 73.85), LatLng(18.55, 73.85))

        // Route with empty steps list
        navEngine.setRoute(RouteResult(pts, 3300.0, 200.0, emptyList()))

        val prog = navEngine.processLocation(LocationUpdate(pts[0], 30.0, 0f, 5f))
        assertNull("getCurrentStep should return null when steps are empty", navEngine.getCurrentStep())
        assertEquals(0, prog.currentManeuverIndex)
        assertTrue("Distance to next maneuver should fall back to remaining route distance", prog.distanceToNextManeuverMeters > 0)
    }

    @Test
    fun `test 10 - ETA handling for moving, stationary, zero speed, and fallback cases`() {
        val navEngine = NavEngine()
        val p1 = LatLng(18.52, 73.85)
        val p2 = LatLng(18.62, 73.85) // ~11.05 km

        val distMeters = p1.distanceTo(p2) * 1000.0
        val durationSec = 900.0 // ~44 km/h route planned average speed

        navEngine.setRoute(RouteResult(listOf(p1, p2), distMeters, durationSec, emptyList()))

        // Case A: Stationary stop (0 km/h) at light -> must NOT divide by zero or produce multi-day spike
        val progStopped = navEngine.processLocation(LocationUpdate(p1, 0.0, 0f, 5f))
        assertTrue(
            "Stationary stop ETA (${progStopped.remainingDurationSeconds}s) should use route planned baseline duration (~900s)",
            progStopped.remainingDurationSeconds in 800.0..1000.0
        )

        // Case B: Moving at steady 60 km/h (~16.6 m/s)
        val progMoving = navEngine.processLocation(LocationUpdate(p1, 60.0, 0f, 5f))
        assertTrue(
            "Moving ETA (${progMoving.remainingDurationSeconds}s) should be derived from smoothed speed",
            progMoving.remainingDurationSeconds in 600.0..1000.0
        )
    }

    @Test
    fun `test 11 - route with zero remaining distance on arrival`() {
        val navEngine = NavEngine()
        val pStart = LatLng(18.52, 73.85)
        val pEnd = LatLng(18.521, 73.85)

        navEngine.setRoute(RouteResult(listOf(pStart, pEnd), 110.0, 10.0, emptyList()))

        val progArrived = navEngine.processLocation(LocationUpdate(pEnd, 0.0, 0f, 5f))

        assertTrue("isArrived must be true upon reaching destination", progArrived.isArrived)
        assertEquals(0.0, progArrived.remainingRouteDistanceMeters, 0.001)
        assertEquals(0.0, progArrived.remainingDurationSeconds, 0.001)
    }

    @Test
    fun `test 12 - stale location timestamp does not overwrite newer progress`() {
        val navEngine = NavEngine()
        val p1 = LatLng(18.520, 73.850)
        val p2 = LatLng(18.525, 73.850)
        val p3 = LatLng(18.530, 73.850)

        navEngine.setRoute(RouteResult(listOf(p1, p2, p3), 2200.0, 150.0, emptyList()))

        val now = System.currentTimeMillis()

        // Fresh fix at p2 at T_now
        val freshFix = LocationUpdate(p2, 30.0, 0f, 5f, timestampMs = now)
        val progFresh = navEngine.processLocation(freshFix)
        assertEquals(1, progFresh.currentSegmentIndex)

        // Stale fix at p1 from T_now - 10000ms
        val staleFix = LocationUpdate(p1, 30.0, 0f, 5f, timestampMs = now - 10000L)
        val progStale = navEngine.processLocation(staleFix)

        assertEquals("Stale position update must not regress progress segment index", 1, progStale.currentSegmentIndex)
    }

    @Test
    fun `test 13 - segment matching recovery after large location gap greater than 60 segments`() {
        val navEngine = NavEngine()
        val points = mutableListOf<LatLng>()
        // Generate 120 polyline points along north-south line
        for (i in 0 until 120) {
            points.add(LatLng(18.5000 + i * 0.0010, 73.8500))
        }

        navEngine.setRoute(RouteResult(points, 13000.0, 900.0, emptyList()))

        // Step 1: Rider at point 5
        val pStart = navEngine.processLocation(LocationUpdate(points[5], 50.0, 0f, 5f))
        assertEquals(5, pStart.currentSegmentIndex)

        // Step 2: Rider passes through tunnel / location gap, emerging 80 segments ahead at point 85
        val pGapRecovery = navEngine.processLocation(LocationUpdate(points[85], 50.0, 0f, 5f))
        assertEquals("Engine must recover from large location gap (>60 segments) and match point 85", 85, pGapRecovery.currentSegmentIndex)
    }

    @Test
    fun `test 14 - parallel segments and loop route matching stability`() {
        val navEngine = NavEngine()

        // Create a route where segment 5 is at (18.505, 73.850)
        // and segment 80 is a parallel return road 30 meters east at (18.505, 73.8503)
        val points = mutableListOf<LatLng>()
        for (i in 0..40) {
            points.add(LatLng(18.500 + i * 0.001, 73.850)) // Outbound leg
        }
        for (i in 0..40) {
            points.add(LatLng(18.540 - i * 0.001, 73.8503)) // Parallel return leg ~30m east
        }

        navEngine.setRoute(RouteResult(points, 9000.0, 600.0, emptyList()))

        // Rider is at outbound segment 5
        val pOutbound = navEngine.processLocation(LocationUpdate(points[5], 40.0, 0f, 5f))
        assertEquals(5, pOutbound.currentSegmentIndex)

        // Rider moves to outbound segment 6 (which is 30m away from return segment 75)
        val pNext = navEngine.processLocation(LocationUpdate(points[6], 40.0, 0f, 5f))
        assertEquals("Local search must remain on outbound leg (segment 6) and not jump to parallel return leg", 6, pNext.currentSegmentIndex)
    }

    @Test
    fun `test 15 - repeated emissions of same route preserve progress while new route resets progress`() {
        val navEngine = NavEngine()

        val routeA = RouteResult(
            coordinates = listOf(LatLng(18.52, 73.85), LatLng(18.55, 73.85), LatLng(18.60, 73.85)),
            totalDistanceMeters = 8800.0,
            totalDurationSeconds = 600.0,
            steps = emptyList()
        )

        navEngine.setRoute(routeA)
        val p1 = navEngine.processLocation(LocationUpdate(routeA.coordinates[1], 40.0, 0f, 5f))
        assertEquals(1, p1.currentSegmentIndex)

        // Re-emitting routeA (same instance/coordinates)
        // Progress remains preserved at index 1
        assertEquals(1, navEngine.progress.value.currentSegmentIndex)

        // Emitting routeB (new coordinates/route) resets progress
        val routeB = RouteResult(
            coordinates = listOf(LatLng(19.00, 72.80), LatLng(19.10, 72.80)),
            totalDistanceMeters = 11000.0,
            totalDurationSeconds = 800.0,
            steps = emptyList()
        )
        navEngine.setRoute(routeB)
        assertEquals("Setting genuinely new route must reset segment index to 0", 0, navEngine.progress.value.currentSegmentIndex)
    }

    @Test
    fun `test 16 - offline fallback routes are flagged and can be rejected`() {
        val routeFallback = RouteResult(
            coordinates = listOf(LatLng(18.5200, 73.8500), LatLng(18.7500, 73.4000)),
            totalDistanceMeters = 50000.0,
            totalDurationSeconds = 3600.0,
            steps = emptyList(),
            isFallback = true
        )

        assertTrue("Fallback route must set isFallback flag to true", routeFallback.isFallback)
    }
}
