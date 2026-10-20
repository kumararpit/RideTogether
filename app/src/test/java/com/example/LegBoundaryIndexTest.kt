package com.example

import com.example.data.location.LegBoundaryMath
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact leg-boundary geometry.
 *
 * A leg boundary must land on the vertex the routing engine produced for that
 * waypoint. The previous nearest-point search could place the boundary on a
 * different pass of the same road (a hairpin or a duplicated service road),
 * which moves the arrival test off the stop.
 */
class LegBoundaryIndexTest {

    /** Builds a straight east-west line of [count] points, [spacingMeters] apart. */
    private fun line(count: Int, spacingMeters: Double, startLat: Double = 18.52, startLng: Double = 73.84): List<LatLng> {
        val metersPerDegreeLng = 111320.0 * Math.cos(Math.toRadians(startLat))
        return (0 until count).map { i ->
            LatLng(startLat, startLng + (i * spacingMeters / metersPerDegreeLng))
        }
    }

    @Test
    fun `leg boundaries follow the cumulative leg distance`() {
        val points = line(count = 11, spacingMeters = 1000.0)
        // Three legs of 3000 m each over a ~10 km line.
        val boundaries = LegBoundaryMath.legStartVertexIndices(
            points = points,
            legDistancesMeters = listOf(3000.0, 3000.0, 3000.0)
        )
        assertEquals(3, boundaries.size)
        assertEquals(0, boundaries[0])
        assertEquals(3, boundaries[1])
        assertEquals(6, boundaries[2])
    }

    @Test
    fun `a single leg starts at the first vertex`() {
        val points = line(count = 5, spacingMeters = 500.0)
        val boundaries = LegBoundaryMath.legStartVertexIndices(
            points = points,
            legDistancesMeters = listOf(2000.0)
        )
        assertEquals(listOf(0), boundaries)
    }

    @Test
    fun `boundaries are monotonic and inside the geometry`() {
        val points = line(count = 20, spacingMeters = 250.0)
        val boundaries = LegBoundaryMath.legStartVertexIndices(
            points = points,
            legDistancesMeters = listOf(1000.0, 1000.0, 1000.0, 1000.0)
        )
        assertEquals(4, boundaries.size)
        assertEquals(boundaries.sorted(), boundaries)
        assertTrue(boundaries.all { it in points.indices })
    }

    @Test
    fun `a hairpin does not pull the boundary onto the wrong pass`() {
        // The route leaves the waypoint, loops back close to it and continues.
        // A nearest-point search would match the second pass (the vertex right
        // next to the waypoint on the way back); the cumulative distance pins
        // the boundary to the pass the engine actually produced.
        val start = LatLng(18.52, 73.84)
        val waypoint = LatLng(18.52, 73.85)
        val points = listOf(
            start,
            LatLng(18.5250, 73.8450), // leaving the waypoint area
            LatLng(18.5300, 73.8600), // the loop
            LatLng(18.5250, 73.8550),
            LatLng(18.5205, 73.8505), // second pass, VERY close to the waypoint
            LatLng(18.5200, 73.8450)
        )
        val boundaries = LegBoundaryMath.legStartVertexIndices(
            points = points,
            legDistancesMeters = listOf(1500.0, 1500.0),
            waypoints = listOf(start, waypoint)
        )
        assertEquals(2, boundaries.size)
        assertEquals(0, boundaries[0])
        // The boundary must not be the vertex that only passes close to the
        // waypoint on the way back.
        assertTrue(
            "boundary must not be the near-duplicate vertex",
            boundaries[1] != 4
        )
    }

    @Test
    fun `inconsistent input yields no boundaries`() {
        assertTrue(LegBoundaryMath.legStartVertexIndices(emptyList(), listOf(100.0)).isEmpty())
        assertTrue(LegBoundaryMath.legStartVertexIndices(listOf(LatLng(18.0, 73.0)), listOf(100.0)).isEmpty())
        assertTrue(LegBoundaryMath.legStartVertexIndices(line(5, 100.0), emptyList()).isEmpty())
    }

    @Test
    fun `cumulative distances accumulate along the polyline`() {
        val points = line(count = 4, spacingMeters = 1000.0)
        val cumulative = LegBoundaryMath.cumulativeDistances(points)
        assertEquals(0.0, cumulative[0], 1e-6)
        assertEquals(1000.0, cumulative[1], 5.0)
        assertEquals(2000.0, cumulative[2], 5.0)
        assertEquals(3000.0, cumulative[3], 5.0)
    }
}
