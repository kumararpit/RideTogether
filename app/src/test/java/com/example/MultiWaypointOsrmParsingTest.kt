package com.example

import com.example.data.location.OsrmRoutingService
import com.example.data.location.RouteRequestException
import com.example.data.model.LatLng
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic parser contract for multi-waypoint OSRM responses.
 * No network: JSON bodies are crafted to match the documented OSRM shape and
 * parsed through the same [OsrmRoutingService.parseRouteResponse] the network
 * path uses. Robolectric is required because org.json is an Android stub in
 * plain unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiWaypointOsrmParsingTest {

    private val service = OsrmRoutingService()

    // origin, stop A, stop B, destination on an east-west line
    private val wpOrigin = LatLng(18.52, 73.84)
    private val wpA = LatLng(18.52, 73.85)
    private val wpB = LatLng(18.52, 73.86)
    private val wpDest = LatLng(18.52, 73.87)
    private val waypoints = listOf(wpOrigin, wpA, wpB, wpDest)
    private val stopNames = listOf("Home", "Cafe A", "Cafe B", "Work")

    private fun okBody(legsJson: String, coordinates: String): String = """
        {
          "code": "Ok",
          "routes": [
            {
              "distance": 3000.0,
              "duration": 210.0,
              "geometry": { "coordinates": $coordinates },
              "legs": $legsJson
            }
          ]
        }
    """.trimIndent()

    private val lineCoordinates =
        """[[73.84,18.52],[73.85,18.52],[73.86,18.52],[73.87,18.52]]"""

    private val threeLegsJson = """
        [
          {
            "distance": 1000.0,
            "duration": 70.0,
            "steps": [
              { "distance": 600.0, "duration": 40.0, "name": "Hinjawadi Road",
                "maneuver": { "type": "depart", "modifier": "straight" } },
              { "distance": 400.0, "duration": 30.0, "name": "Marunji Road",
                "maneuver": { "type": "turn", "modifier": "right" } },
              { "distance": 0.0, "duration": 0.0, "name": "Cafe A",
                "maneuver": { "type": "arrive", "modifier": "left" } }
            ]
          },
          {
            "distance": 1000.0,
            "duration": 70.0,
            "steps": [
              { "distance": 700.0, "duration": 50.0, "name": "Marunji Road",
                "maneuver": { "type": "depart", "modifier": "straight" } },
              { "distance": 300.0, "duration": 20.0, "name": "Highway 1",
                "maneuver": { "type": "turn", "modifier": "left" } },
              { "distance": 0.0, "duration": 0.0, "name": "Cafe B",
                "maneuver": { "type": "arrive", "modifier": "left" } }
            ]
          },
          {
            "distance": 1000.0,
            "duration": 70.0,
            "steps": [
              { "distance": 1000.0, "duration": 70.0, "name": "Highway 1",
                "maneuver": { "type": "depart", "modifier": "straight" } },
              { "distance": 0.0, "duration": 0.0, "name": "Work",
                "maneuver": { "type": "arrive", "modifier": "none" } }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `multiple returned legs are preserved with per-leg data`() {
        val result = service.parseRouteResponse(okBody(threeLegsJson, lineCoordinates), waypoints, stopNames)!!
        assertEquals(3, result.legs.size)
        assertEquals(1000.0, result.legs[0].distanceMeters, 1e-9)
        assertEquals(1000.0, result.legs[1].distanceMeters, 1e-9)
        assertEquals(1000.0, result.legs[2].distanceMeters, 1e-9)
        assertEquals(3, result.legs[0].steps.size)
        assertEquals(2, result.legs[2].steps.size)
        // leg start vertices are located inside the merged overview polyline
        assertEquals(0, result.legs[0].startVertexIndex)
        assertEquals(1, result.legs[1].startVertexIndex)
        assertEquals(2, result.legs[2].startVertexIndex)
    }

    @Test
    fun `total distance and duration stay consistent with the response`() {
        val result = service.parseRouteResponse(okBody(threeLegsJson, lineCoordinates), waypoints, stopNames)!!
        assertEquals(3000.0, result.totalDistanceMeters, 1e-9)
        assertEquals(210.0, result.totalDurationSeconds, 1e-9)
        // per-leg distances sum to the total (consistency invariant)
        assertEquals(result.legs.sumOf { it.distanceMeters }, result.totalDistanceMeters, 1e-6)
    }

    @Test
    fun `per-leg maneuvers remain correctly associated with their legs in flattened order`() {
        val result = service.parseRouteResponse(okBody(threeLegsJson, lineCoordinates), waypoints, stopNames)!!
        // flattened steps preserve leg order and sequence within each leg
        assertEquals(8, result.steps.size)
        assertEquals("depart", result.steps[0].maneuverType)
        assertEquals("turn", result.steps[1].maneuverType)
        // intermediate leg arrivals name the upcoming stop
        assertEquals("Arrive at stop: Cafe A", result.steps[2].instruction)
        assertEquals("Arrive at stop: Cafe B", result.steps[5].instruction)
        assertEquals("Arrive at destination", result.steps.last().instruction)
    }

    @Test
    fun `missing or mismatched legs are rejected safely`() {
        // 4 waypoints require exactly 3 legs; a 2-leg payload is rejected
        val broken = okBody(
            """[
              {"distance": 1500.0, "duration": 105.0, "steps": []},
              {"distance": 1500.0, "duration": 105.0, "steps": []}
            ]""",
            lineCoordinates
        )
        val ex = assertThrows(RouteRequestException::class.java) {
            service.parseRouteResponse(broken, waypoints, stopNames)
        }
        assertTrue(ex.message!!.contains("leg count mismatch"))
    }

    @Test
    fun `NoRoute response is rejected with a friendly error code`() {
        val body = """{"code":"NoRoute","message":"No route found"}"""
        val ex = assertThrows(RouteRequestException::class.java) {
            service.parseRouteResponse(body, waypoints, stopNames)
        }
        assertEquals("NoRoute", ex.osrmCode)
        assertTrue(ex.message!!.contains("No road route"))
    }

    @Test
    fun `NoSegment response is rejected with a friendly error code`() {
        val body = """{"code":"NoSegment","message":"Could not find segment"}"""
        val ex = assertThrows(RouteRequestException::class.java) {
            service.parseRouteResponse(body, waypoints, stopNames)
        }
        assertEquals("NoSegment", ex.osrmCode)
        assertTrue(ex.message!!.contains("unreachable by road"))
    }

    @Test
    fun `malformed JSON is rejected instead of partially applied`() {
        val body = """{"code":"Ok","routes":[{"dist":}"""
        assertThrows(Exception::class.java) {
            service.parseRouteResponse(body, waypoints, stopNames)
        }
    }

    @Test
    fun `missing routes array or empty routes are rejected`() {
        val noRoutes = """{"code":"Ok"}"""
        assertThrows(RouteRequestException::class.java) {
            service.parseRouteResponse(noRoutes, waypoints, stopNames)
        }
        val emptyRoutes = """{"code":"Ok","routes":[]}"""
        assertThrows(RouteRequestException::class.java) {
            service.parseRouteResponse(emptyRoutes, waypoints, stopNames)
        }
    }

    @Test
    fun `geometry coordinates are parsed as lng,lat into LatLng lat,lng`() {
        val result = service.parseRouteResponse(okBody(threeLegsJson, lineCoordinates), waypoints, stopNames)!!
        assertEquals(4, result.coordinates.size)
        assertEquals(18.52, result.coordinates[1].latitude, 1e-9)
        assertEquals(73.85, result.coordinates[1].longitude, 1e-9)
    }

    @Test
    fun `caller structured stops replace synthesized stops when consistent in size`() {
        val planned = listOf(
            RouteStop(id = "origin-id", name = "Home", latitude = wpOrigin.latitude, longitude = wpOrigin.longitude, role = RouteStopRole.ORIGIN),
            RouteStop(id = "stable-a", name = "Cafe A", latitude = wpA.latitude, longitude = wpA.longitude),
            RouteStop(id = "stable-b", name = "Cafe B", latitude = wpB.latitude, longitude = wpB.longitude),
            RouteStop(id = "dest-id", name = "Work", latitude = wpDest.latitude, longitude = wpDest.longitude, role = RouteStopRole.DESTINATION)
        )
        val result = service.parseRouteResponse(
            okBody(threeLegsJson, lineCoordinates), waypoints, stopNames,
            planVersion = 7L, structuredStops = planned
        )!!
        assertEquals("stable-a", result.stops[1].id)
        assertEquals("dest-id", result.stops[3].id)
        assertEquals(7L, result.planVersion)
    }

    @Test
    fun `inconsistent structured stop list falls back to synthesized stops`() {
        val planned = listOf(
            RouteStop(id = "origin-id", name = "Home", latitude = wpOrigin.latitude, longitude = wpOrigin.longitude, role = RouteStopRole.ORIGIN)
        )
        val result = service.parseRouteResponse(
            okBody(threeLegsJson, lineCoordinates), waypoints, stopNames, structuredStops = planned
        )!!
        assertEquals(4, result.stops.size)
        assertNotNull(result.stops[1])
    }

    @Test
    fun `waypoints serialize as lng,lat in the exact caller order`() {
        val path = service.buildCoordinatePath(waypoints)
        assertEquals("73.84,18.52;73.85,18.52;73.86,18.52;73.87,18.52", path)
    }

    @Test
    fun `invalid coordinates are rejected before any request is built`() {
        val bad = listOf(wpOrigin, LatLng(120.0, 73.85), wpDest)
        assertTrue(service.validateWaypoints(waypoints) is Unit)
        assertThrows(IllegalArgumentException::class.java) {
            service.validateWaypoints(bad)
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.validateWaypoints(listOf(wpOrigin, LatLng(18.52, 999.0), wpDest))
        }
        // duplicate consecutive waypoints never reach OSRM
        assertThrows(IllegalArgumentException::class.java) {
            service.validateWaypoints(listOf(wpOrigin, wpA, wpA, wpDest))
        }
    }
}
