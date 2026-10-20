package com.example

import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteLeg
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-waypoint navigation progression: leg identification, stop advancement,
 * remaining distance/ETA continuity, and the safeguards against stale fixes
 * and closely spaced stops. No-stop routes stay regression-free.
 */
class NavEngineMultiLegTest {

    private fun legFix(leg: Map<String, Any>) = RouteLeg(
        startVertexIndex = (leg["startVertexIndex"] as Number).toInt(),
        distanceMeters = (leg["distanceMeters"] as Number).toDouble(),
        durationSeconds = (leg["durationSeconds"] as Number).toDouble()
    )

    // 1 km east per leg, vertices exactly at every waypoint.
    private val origin = LatLng(18.52, 73.84)
    private val stopA = LatLng(18.52, 73.85)
    private val stopB = LatLng(18.52, 73.86)
    private val destination = LatLng(18.52, 73.87)

    private fun multiLegRoute(): RouteResult {
        val points = listOf(origin, stopA, stopB, destination)
        val d1 = origin.distanceTo(stopA) * 1000.0
        val d2 = stopA.distanceTo(stopB) * 1000.0
        val d3 = stopB.distanceTo(destination) * 1000.0
        val total = d1 + d2 + d3
        return RouteResult(
            coordinates = points,
            totalDistanceMeters = total,
            totalDurationSeconds = total / 13.0,
            steps = listOf(
                NavigationStep("Depart from start", "Main Road", d1, d1 / 13.0, "depart", "straight"),
                NavigationStep("Arrive at stop: Cafe A", "Cafe A", d2, d2 / 13.0, "turn", "right"),
                NavigationStep("Arrive at destination", "Destination", d3, d3 / 13.0, "arrive", "none")
            ),
            waypoints = points,
            stops = listOf(
                RouteStop(id = "origin", name = "Home", latitude = origin.latitude, longitude = origin.longitude, role = RouteStopRole.ORIGIN),
                RouteStop(id = "s1", name = "Cafe A", latitude = stopA.latitude, longitude = stopA.longitude),
                RouteStop(id = "s2", name = "Cafe B", latitude = stopB.latitude, longitude = stopB.longitude),
                RouteStop(id = "destination", name = "Work", latitude = destination.latitude, longitude = destination.longitude, role = RouteStopRole.DESTINATION)
            ),
            legs = listOf(
                legFix(mapOf("startVertexIndex" to 0, "distanceMeters" to d1, "durationSeconds" to d1 / 13.0)),
                legFix(mapOf("startVertexIndex" to 1, "distanceMeters" to d2, "durationSeconds" to d2 / 13.0)),
                legFix(mapOf("startVertexIndex" to 2, "distanceMeters" to d3, "durationSeconds" to d3 / 13.0))
            )
        )
    }

    @Test
    fun `navigation starts on the origin-to-first-stop leg`() {
        val engine = NavEngine()
        engine.setRoute(multiLegRoute())
        val progress = engine.processLocation(
            LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(0, progress.currentLegIndex)
        assertEquals(3, progress.legCount)
        // next required stop is the first intermediate stop
        assertEquals("Cafe A", engine.getNextStop()?.name)
    }

    @Test
    fun `leg advances when the rider reaches the intermediate stop`() {
        val engine = NavEngine()
        engine.setRoute(multiLegRoute())
        engine.processLocation(LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        val atStop = engine.processLocation(
            LocationUpdate(stopA, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(1, atStop.currentLegIndex)
        // next required stop advances in the original order
        assertEquals("Cafe B", engine.getNextStop()?.name)
        val atStopB = engine.processLocation(
            LocationUpdate(stopB, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(2, atStopB.currentLegIndex)
    }

    @Test
    fun `remaining distance and ETA stay coherent across leg transitions`() {
        val engine = NavEngine()
        val route = multiLegRoute()
        val legTotal = route.totalDistanceMeters
        engine.setRoute(route)
        val start = engine.processLocation(
            LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(legTotal, start.remainingRouteDistanceMeters, 2.0)
        val d2 = stopA.distanceTo(stopB) * 1000.0
        val d3 = stopB.distanceTo(destination) * 1000.0
        val atStop = engine.processLocation(
            LocationUpdate(stopA, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        // the merged polyline means remaining distance counts through all future legs
        assertEquals(d2 + d3, atStop.remainingRouteDistanceMeters, 2.0)
        assertTrue("ETA must remain positive mid-route", atStop.remainingDurationSeconds > 60.0)
        assertTrue(
            "ETA must decrease after passing leg 1",
            atStop.remainingDurationSeconds < start.remainingDurationSeconds
        )
    }

    @Test
    fun `closely spaced stops are never skipped by a single fix`() {
        // stops A and A2 are only ~30 m apart along the route
        val a = LatLng(18.52, 73.85)
        val a2 = LatLng(18.52, 73.8503) // ~30 m east of A at this latitude
        val d = LatLng(18.52, 73.86)
        val points = listOf(origin, a, a2, d)

        val d12 = origin.distanceTo(a) * 1000.0
        val d23 = a.distanceTo(a2) * 1000.0
        val d34 = a2.distanceTo(d) * 1000.0

        val route = RouteResult(
            coordinates = points,
            totalDistanceMeters = d12 + d23 + d34,
            totalDurationSeconds = (d12 + d23 + d34) / 12.5,
            steps = emptyList(),
            waypoints = points,
            stops = listOf(
                RouteStop(id = "origin", name = "Home", latitude = origin.latitude, longitude = origin.longitude, role = RouteStopRole.ORIGIN),
                RouteStop(id = "s1", name = "Cafe A", latitude = a.latitude, longitude = a.longitude),
                RouteStop(id = "s2", name = "Snack A2", latitude = a2.latitude, longitude = a2.longitude),
                RouteStop(id = "destination", name = "End", latitude = d.latitude, longitude = d.longitude, role = RouteStopRole.DESTINATION)
            ),
            legs = listOf(
                RouteLeg(0, d12, d12 / 12.5),
                RouteLeg(1, d23, d23 / 12.5),
                RouteLeg(2, d34, d34 / 12.5)
            ),
            planVersion = 1L
        )
        val engine = NavEngine()
        engine.setRoute(route)

        val first = engine.processLocation(LocationUpdate(a, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals("advance exactly one leg per fix", 1, first.currentLegIndex)

        val second = engine.processLocation(LocationUpdate(a2, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals("second fix credits the second closely spaced stop", 2, second.currentLegIndex)
    }

    @Test
    fun `stale or inaccurate fixes cannot falsely advance stops`() {
        val engine = NavEngine()
        engine.setRoute(multiLegRoute())
        engine.processLocation(LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        // rider physically AT stop A but with a 60 m accuracy band: no credit
        val stale = engine.processLocation(
            LocationUpdate(stopA, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 60f)
        )
        assertEquals(0, stale.currentLegIndex)
        assertTrue(stale.isStaleGps)
    }

    @Test
    fun `off-route fixes never advance legs`() {
        val engine = NavEngine()
        engine.setRoute(multiLegRoute())
        engine.processLocation(LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        // 200 m north of any point on the route: off-route, no leg credit
        val offRouteFix = LatLng(18.5218, 73.85)
        val off = engine.processLocation(
            LocationUpdate(offRouteFix, speedKmh = 40.0, headingDeg = 0f, accuracyMeters = 5f)
        )
        assertTrue(off.isOffRoute)
        assertEquals(0, off.currentLegIndex)
    }

    @Test
    fun `no-stop navigation stays regression-free`() {
        val engine = NavEngine()
        val points = listOf(origin, stopB, destination)
        val total = RouteGeometry.remainingDistanceMeters(0, points)
        val route = RouteResult(points, total, 600.0, emptyList())
        engine.setRoute(route)

        val mid = engine.processLocation(
            LocationUpdate(stopB, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(1, mid.legCount)
        assertEquals("single-destination routes have no intermediate legs", 0, mid.currentLegIndex)
        assertNull(engine.getNextStop())

        val arrived = engine.processLocation(
            LocationUpdate(destination, speedKmh = 0.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertTrue(arrived.isArrived)
        assertEquals(0.0, arrived.remainingRouteDistanceMeters, 2.0)
    }

    @Test
    fun `next stop is null before any route and falls back to destination on final leg`() {
        val engine = NavEngine()
        assertNull(engine.getNextStop())
        engine.setRoute(multiLegRoute())
        // two fixes: start then stop A; rider now on final leg (2 more legs? no: on leg 1)
        engine.processLocation(LocationUpdate(origin, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        engine.processLocation(LocationUpdate(stopA, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        assertNotNull(engine.getNextStop())
        engine.processLocation(LocationUpdate(stopB, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f))
        // on the final leg the guided target is the destination itself
        assertEquals("Work", engine.getNextStop()?.name)
    }
}
