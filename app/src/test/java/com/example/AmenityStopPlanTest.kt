package com.example

import com.example.data.model.AmenityStopPlan
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan arithmetic of "add this place to the ride as a stop".
 *
 * The stop order and the detour are what the rider relies on, so both are
 * pinned here without a device or a network.
 */
class AmenityStopPlanTest {

    private fun stop(
        id: String,
        lat: Double,
        lon: Double,
        role: RouteStopRole = RouteStopRole.INTERMEDIATE
    ) = RouteStop(id = id, name = id, latitude = lat, longitude = lon, role = role)

    @Test
    fun `the place is added as the last stop before the destination`() {
        val stops = listOf(
            stop("origin", 18.50, 73.80, RouteStopRole.ORIGIN),
            stop("cafe", 18.51, 73.81),
            stop("fuel", 18.52, 73.82),
            stop("dest", 18.60, 73.90, RouteStopRole.DESTINATION)
        )
        val result = AmenityStopPlan.insert(stops, stop("amenity", 18.55, 73.85))
        assertEquals(listOf("origin", "cafe", "fuel", "amenity", "dest"), result.map { it.id })
        assertEquals(RouteStopRole.DESTINATION, result.last().role)
    }

    @Test
    fun `the origin is never duplicated and the destination stays last`() {
        val stops = listOf(
            stop("origin", 18.50, 73.80, RouteStopRole.ORIGIN),
            stop("dest", 18.60, 73.90, RouteStopRole.DESTINATION)
        )
        val result = AmenityStopPlan.insert(stops, stop("amenity", 18.55, 73.85))
        assertEquals(1, result.count { it.role == RouteStopRole.ORIGIN })
        assertEquals("origin", result.first().id)
        assertEquals("dest", result.last().id)
        assertEquals(listOf("origin", "amenity", "dest"), result.map { it.id })
    }

    @Test
    fun `a place that is already a stop is not added twice`() {
        val stops = listOf(
            stop("origin", 18.50, 73.80, RouteStopRole.ORIGIN),
            stop("amenity", 18.55, 73.85),
            stop("dest", 18.60, 73.90, RouteStopRole.DESTINATION)
        )
        val result = AmenityStopPlan.insert(stops, stop("amenity-again", 18.55, 73.85))
        assertEquals(1, result.count { it.latitude == 18.55 && it.longitude == 73.85 })
    }

    @Test
    fun `the detour is the time the stop adds to the ride`() {
        assertEquals(180.0, AmenityStopPlan.detourSeconds(newRemainingSeconds = 1200.0, oldRemainingSeconds = 1020.0), 0.001)
        // A stop that shortens the ride is reported as free, never negative.
        assertEquals(0.0, AmenityStopPlan.detourSeconds(newRemainingSeconds = 900.0, oldRemainingSeconds = 1200.0), 0.001)
    }

    @Test
    fun `the detour reads the way a rider reads it`() {
        assertEquals("3 min", AmenityStopPlan.formatDetour(180.0))
        assertEquals("under a minute", AmenityStopPlan.formatDetour(40.0))
        assertEquals("1.5 h", AmenityStopPlan.formatDetour(5400.0))
    }

    @Test
    fun `a free stop carries no detour badge`() {
        assertNull(AmenityStopPlan.detourLabel(10.0))
        assertEquals("+3 min detour", AmenityStopPlan.detourLabel(180.0))
        assertTrue(AmenityStopPlan.detourLabel(0.0) == null)
    }
}
