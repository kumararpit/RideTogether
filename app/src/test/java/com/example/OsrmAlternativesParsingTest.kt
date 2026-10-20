package com.example

import com.example.data.location.OsrmRoutingService
import com.example.data.location.RouteRequestException
import com.example.data.model.LatLng
import com.example.data.model.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Candidate-route parsing contract.
 *
 * The routing engine can return several road paths for one ordered waypoint
 * plan. Every candidate must be a COMPLETE route with its own geometry, legs
 * and steps - never a re-associated view of the primary route - and the primary
 * route must stay first. The strict leg contract applies to every candidate.
 *
 * No network: JSON bodies are crafted to match the documented engine shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsrmAlternativesParsingTest {

    private val service = OsrmRoutingService()

    private val wpOrigin = LatLng(18.52, 73.84)
    private val wpA = LatLng(18.52, 73.85)
    private val wpDest = LatLng(18.52, 73.87)
    private val waypoints = listOf(wpOrigin, wpA, wpDest)

    private fun legsJson(distance: Double, duration: Double): String = """
        [
          {
            "distance": $distance,
            "duration": $duration,
            "steps": [
              { "distance": $distance, "duration": $duration, "name": "Main Road",
                "maneuver": { "type": "depart", "modifier": "left" } },
              { "distance": 0.0, "duration": 0.0, "name": "Cafe A",
                "maneuver": { "type": "arrive", "modifier": "straight" } }
            ]
          },
          {
            "distance": 1000.0,
            "duration": 70.0,
            "steps": [
              { "distance": 1000.0, "duration": 70.0, "name": "Ridge Road",
                "maneuver": { "type": "turn", "modifier": "right" } },
              { "distance": 0.0, "duration": 0.0, "name": "Work",
                "maneuver": { "type": "arrive", "modifier": "straight" } }
            ]
          }
        ]
    """.trimIndent()

    /** Two candidates: the primary (GeoJSON geometry) and an alternative. */
    private val twoRouteBody = """
        {
          "code": "Ok",
          "routes": [
            {
              "distance": 3000.0,
              "duration": 210.0,
              "geometry": { "coordinates": [[73.84,18.52],[73.85,18.52],[73.87,18.52]] },
              "legs": ${legsJson(1500.0, 105.0)}
            },
            {
              "distance": 4200.0,
              "duration": 260.0,
              "geometry": { "coordinates": [[73.84,18.52],[73.845,18.525],[73.85,18.52],[73.87,18.52]] },
              "legs": ${legsJson(2500.0, 160.0)}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `every candidate route is parsed independently`() {
        val options = service.parseRouteOptions(twoRouteBody, waypoints, stopNames = listOf("Home", "Cafe A", "Work"))

        assertEquals(2, options!!.size)
        assertEquals(0, options[0].index)
        assertEquals(1, options[1].index)

        // Distinct geometry, distance and duration per candidate.
        assertEquals(3000.0, options[0].distanceMeters, 1e-9)
        assertEquals(4200.0, options[1].distanceMeters, 1e-9)
        assertEquals(210.0, options[0].durationSeconds, 1e-9)
        assertEquals(260.0, options[1].durationSeconds, 1e-9)
        assertNotEquals(options[0].route.coordinates.size, options[1].route.coordinates.size)
    }

    @Test
    fun `every candidate satisfies the strict leg contract`() {
        val options = service.parseRouteOptions(twoRouteBody, waypoints)!!
        options.forEach { option ->
            assertEquals("legs must match waypoints - 1", waypoints.size - 1, option.route.legs.size)
            assertEquals(option.route.legs.size, option.route.steps.size / 2)
        }
    }

    @Test
    fun `a candidate with the wrong leg count is rejected`() {
        val broken = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 3000.0,
                  "duration": 210.0,
                  "geometry": { "coordinates": [[73.84,18.52],[73.87,18.52]] },
                  "legs": [
                    { "distance": 3000.0, "duration": 210.0, "steps": [] }
                  ]
                }
              ]
            }
        """.trimIndent()
        val error = assertThrows(RouteRequestException::class.java) {
            service.parseRouteOptions(broken, waypoints)
        }
        assertTrue(error.message!!.contains("leg count mismatch"))
    }

    @Test
    fun `a single route response yields exactly one candidate`() {
        val single = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 3000.0,
                  "duration": 210.0,
                  "geometry": { "coordinates": [[73.84,18.52],[73.85,18.52],[73.87,18.52]] },
                  "legs": ${legsJson(1500.0, 105.0)}
                }
              ]
            }
        """.trimIndent()
        val options = service.parseRouteOptions(single, waypoints)!!
        assertEquals(1, options.size)
        assertEquals(0, options[0].index)
    }

    @Test
    fun `encoded polyline geometry is decoded`() {
        val encoded = com.example.data.location.PolylineCodec.encode(
            listOf(LatLng(18.52, 73.84), LatLng(18.52, 73.85), LatLng(18.52, 73.87))
        )
        val body = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 3000.0,
                  "duration": 210.0,
                  "geometry": "$encoded",
                  "legs": ${legsJson(1500.0, 105.0)}
                }
              ]
            }
        """.trimIndent()
        val options = service.parseRouteOptions(body, waypoints)!!
        val points = options[0].route.coordinates
        assertEquals(3, points.size)
        assertEquals(18.52, points[0].latitude, 1e-5)
        assertEquals(73.87, points[2].longitude, 1e-5)
    }

    @Test
    fun `no route is rejected with a friendly error`() {
        val error = assertThrows(RouteRequestException::class.java) {
            service.parseRouteOptions("""{"code":"NoRoute","routes":[]}""", waypoints)
        }
        assertEquals("NoRoute", error.osrmCode)
        assertTrue(error.message!!.contains("No road route"))
    }

    @Test
    fun `the primary route stays first and is what the legacy parser returns`() {
        val options = service.parseRouteOptions(twoRouteBody, waypoints)!!
        val primary = service.parseRouteResponse(twoRouteBody, waypoints)!!
        assertEquals(options[0].route.totalDistanceMeters, primary.totalDistanceMeters, 1e-9)
        assertEquals(options[0].route.coordinates.size, primary.coordinates.size)
    }

    @Test
    fun `structured stops are applied to every candidate`() {
        val stops = listOf(
            RouteStop(id = "origin", name = "Home", latitude = 18.52, longitude = 73.84),
            RouteStop(id = "s1", name = "Cafe A", latitude = 18.52, longitude = 73.85),
            RouteStop(id = "destination", name = "Work", latitude = 18.52, longitude = 73.87)
        )
        val options = service.parseRouteOptions(twoRouteBody, waypoints, structuredStops = stops)!!
        options.forEach { option ->
            assertEquals(listOf("origin", "s1", "destination"), option.route.stops.map { it.id })
        }
    }
}
