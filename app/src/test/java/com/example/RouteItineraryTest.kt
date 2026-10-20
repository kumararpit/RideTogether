package com.example

import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteLeg
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import com.example.data.model.buildItinerary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop-by-stop itinerary derivation.
 *
 * The itinerary is what the group sees: one row per leg, from one stop to the
 * next, with that leg's distance, duration and maneuvers. It must follow the
 * plan's stop order exactly and never invent a leg.
 */
class RouteItineraryTest {

    private val stops = listOf(
        RouteStop(id = "origin", name = "Pune", latitude = 18.52, longitude = 73.84, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s1", name = "Lonavala Cafe", latitude = 18.75, longitude = 73.41, role = RouteStopRole.INTERMEDIATE),
        RouteStop(id = "destination", name = "Khandala", latitude = 18.78, longitude = 73.30, role = RouteStopRole.DESTINATION)
    )

    private fun route(): RouteResult {
        val legs = listOf(
            RouteLeg(
                startVertexIndex = 0,
                distanceMeters = 50000.0,
                durationSeconds = 3000.0,
                steps = listOf(NavigationStep("Head left onto Old Mumbai Hwy", "Old Mumbai Hwy", 25000.0, 1500.0, "turn", "left"))
            ),
            RouteLeg(
                startVertexIndex = 12,
                distanceMeters = 8000.0,
                durationSeconds = 600.0,
                steps = listOf(NavigationStep("Arrive at stop: Khandala", "", 0.0, 0.0, "arrive", "straight"))
            )
        )
        return RouteResult(
            coordinates = listOf(LatLng(18.52, 73.84), LatLng(18.75, 73.41), LatLng(18.78, 73.30)),
            totalDistanceMeters = 58000.0,
            totalDurationSeconds = 3600.0,
            steps = legs.flatMap { it.steps },
            waypoints = stops.map { it.latLng },
            stops = stops,
            legs = legs
        )
    }

    @Test
    fun `every leg maps to the consecutive stops of the plan`() {
        val itinerary = buildItinerary(route())
        assertEquals(2, itinerary.size)
        assertEquals("Pune", itinerary[0].fromName)
        assertEquals("Lonavala Cafe", itinerary[0].toName)
        assertEquals("Lonavala Cafe", itinerary[1].fromName)
        assertEquals("Khandala", itinerary[1].toName)
    }

    @Test
    fun `each leg keeps its own distance, duration and maneuvers`() {
        val itinerary = buildItinerary(route())
        assertEquals(50000.0, itinerary[0].distanceMeters, 1e-9)
        assertEquals(8000.0, itinerary[1].distanceMeters, 1e-9)
        assertEquals(1, itinerary[0].steps.size)
        assertEquals(1, itinerary[1].steps.size)
        assertTrue(itinerary[0].formattedDistance.contains("50.0 km"))
        assertTrue(itinerary[1].formattedDuration.contains("10 min"))
    }

    @Test
    fun `a route without legs has no itinerary`() {
        assertTrue(buildItinerary(null).isEmpty())
        assertTrue(buildItinerary(route().copy(legs = emptyList())).isEmpty())
    }

    @Test
    fun `a legacy single-destination route still yields one leg`() {
        val legacy = RouteResult(
            coordinates = listOf(LatLng(18.52, 73.84), LatLng(18.75, 73.41)),
            totalDistanceMeters = 50000.0,
            totalDurationSeconds = 3000.0,
            steps = emptyList(),
            legs = listOf(RouteLeg(startVertexIndex = 0, distanceMeters = 50000.0, durationSeconds = 3000.0))
        )
        val itinerary = buildItinerary(legacy)
        assertEquals(1, itinerary.size)
        assertEquals("Start", itinerary[0].fromName)
        assertEquals("Destination", itinerary[0].toName)
    }
}
