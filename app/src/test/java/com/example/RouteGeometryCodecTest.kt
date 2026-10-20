package com.example

import com.example.data.firebase.RouteGeometryCodec
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route geometry persistence codec (Phase B, backlog B-03; audit F12-D1).
 *
 * The codec is the unit-testable core of the geometry persistence: a restored
 * ride must be re-armed with the *same* polyline and the same maneuver steps
 * it had before the process died.
 */
class RouteGeometryCodecTest {

    private fun route(n: Int): List<LatLng> =
        (0 until n).map { LatLng(18.5 + it * 0.0001, 73.8 + it * 0.0001) }

    private val steps = listOf(
        NavigationStep("Head left", "Origin Road", 278.0, 60.0, "depart", "left"),
        NavigationStep("Turn right", "Cross Road", 1000.0, 90.0, "turn", "right")
    )

    @Test
    fun `geometry round trips exactly`() {
        val points = route(120)
        val encoded = RouteGeometryCodec.encode(points, steps, 52_800.0, 3_000.0)

        val decoded = RouteGeometryCodec.decode(
            rawPoints = encoded["points"],
            rawSteps = encoded["steps"],
            totalDistanceMeters = encoded["totalDistanceMeters"] as Double,
            totalDurationSeconds = encoded["totalDurationSeconds"] as Double
        )

        assertNotNull(decoded)
        assertEquals(points.size, decoded!!.points.size)
        for (i in points.indices) {
            assertEquals(points[i].latitude, decoded.points[i].latitude, 0.0)
            assertEquals(points[i].longitude, decoded.points[i].longitude, 0.0)
        }
        assertEquals(2, decoded.steps.size)
        assertEquals("Turn right", decoded.steps[1].instruction)
        assertEquals("right", decoded.steps[1].modifier)
        assertEquals(1000.0, decoded.steps[1].distanceMeters, 0.0)
        assertEquals(52_800.0, decoded.totalDistanceMeters, 0.0)
        assertEquals(3_000.0, decoded.totalDurationSeconds, 0.0)
    }

    @Test
    fun `a long route is truncated to the persisted cap and flagged`() {
        val points = route(RouteGeometryCodec.MAX_PERSISTED_ROUTE_POINTS + 500)
        val encoded = RouteGeometryCodec.encode(points, steps, 100_000.0, 5_000.0)

        @Suppress("UNCHECKED_CAST")
        val storedPoints = encoded["points"] as List<*>
        assertEquals(RouteGeometryCodec.MAX_PERSISTED_ROUTE_POINTS, storedPoints.size)
        assertEquals(true, encoded["truncated"])
        assertEquals(points.size, encoded["pointCount"])
    }

    @Test
    fun `a normal route is not truncated`() {
        val encoded = RouteGeometryCodec.encode(route(500), steps, 1.0, 1.0)
        assertEquals(false, encoded["truncated"])
    }

    @Test
    fun `malformed documents decode to null instead of crashing`() {
        assertNull(RouteGeometryCodec.decode(null, null, 0.0, 0.0))
        assertNull(RouteGeometryCodec.decode(emptyList<Any?>(), null, 0.0, 0.0))
        // A single vertex cannot describe a route.
        assertNull(
            RouteGeometryCodec.decode(
                listOf(listOf(18.5, 73.8)),
                null, 0.0, 0.0
            )
        )
        // Non-numeric coordinates are dropped, not accepted.
        val decoded = RouteGeometryCodec.decode(
            listOf(listOf("x", 73.8), listOf(18.5, 73.8), listOf(18.6, 73.9)),
            null, 0.0, 0.0
        )
        assertNotNull("the valid vertices survive", decoded)
        assertEquals(2, decoded!!.points.size)
    }

    @Test
    fun `out of range coordinates are rejected`() {
        val decoded = RouteGeometryCodec.decode(
            listOf(listOf(999.0, 73.8), listOf(18.5, 73.8), listOf(18.6, 73.9)),
            null, 0.0, 0.0
        )
        assertNotNull(decoded)
        assertTrue("the invalid latitude must be dropped", decoded!!.points.all { it.latitude in -90.0..90.0 })
    }

    @Test
    fun `the description is human readable`() {
        val encoded = RouteGeometryCodec.encode(route(10), steps, 52_800.0, 3_000.0)
        val decoded = RouteGeometryCodec.decode(
            encoded["points"], encoded["steps"], 52_800.0, 3_000.0
        )!!
        val text = RouteGeometryCodec.describe(decoded)
        assertTrue(text.contains("10 points"))
        assertTrue(text.contains("2 steps"))
        assertTrue(text.contains("52.8 km"))
    }
}
