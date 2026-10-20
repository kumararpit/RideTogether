package com.example

import com.example.data.model.RouteStop
import com.example.data.model.RouteStopCodec
import com.example.data.model.RouteStopRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Firestore serialization contract of the leader-authoritative ordered route
 * plan, plus the stale-snapshot merge guard.
 */
class RouteStopCodecTest {

    private fun plan(): List<RouteStop> = listOf(
        RouteStop(id = "origin", name = "Home", address = "Pune", latitude = 18.5204, longitude = 73.8567, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s1", name = "Cafe A", latitude = 18.5300, longitude = 73.8600, role = RouteStopRole.INTERMEDIATE),
        RouteStop(id = "s2", name = "Fuel Point", latitude = 18.5400, longitude = 73.8700, role = RouteStopRole.INTERMEDIATE),
        RouteStop(id = "destination", name = "Work", latitude = 18.5500, longitude = 73.8800, role = RouteStopRole.DESTINATION)
    )

    @Test
    fun `stops serialize and deserialize in stable order with all fields`() {
        val original = plan()
        val encoded = RouteStopCodec.toFirestore(original)
        assertEquals(4, encoded.size)
        // explicit array order + "order" index agree and survive the round trip
        encoded.forEachIndexed { index, map ->
            assertEquals(index, map["order"])
        }
        val decoded = RouteStopCodec.fromFirestore(encoded)
        assertEquals(original.map { it.id }, decoded.map { it.id })
        assertEquals(original.map { it.name }, decoded.map { it.name })
        assertEquals(original.map { it.role }, decoded.map { it.role })
        original.forEachIndexed { i, stop ->
            assertEquals(stop.latitude, decoded[i].latitude, 1e-9)
            assertEquals(stop.longitude, decoded[i].longitude, 1e-9)
        }
        assertEquals("Pune", decoded[0].address)
    }

    @Test
    fun `legacy rides without routeStops parse to an empty plan`() {
        assertTrue(RouteStopCodec.fromFirestore(null).isEmpty())
        assertTrue(RouteStopCodec.fromFirestore(emptyList<Any>()).isEmpty())
    }

    @Test
    fun `malformed entries are skipped without crashing the snapshot listener`() {
        val encoded = listOf(
            mapOf("id" to "s1", "name" to "Cafe A", "role" to "INTERMEDIATE", "lat" to 18.53, "lng" to 73.86),
            "not-a-map",                       // wrong type -> skipped
            mapOf("name" to "no-coordinates"), // missing lat/lng -> skipped
            mapOf("id" to "s2", "name" to "Weird Role", "role" to "NOT_A_ROLE", "lat" to 19.0, "lng" to 74.0)
        )
        val decoded = RouteStopCodec.fromFirestore(encoded)
        assertEquals(2, decoded.size)
        assertEquals("s1", decoded[0].id)
        assertEquals("s2", decoded[1].id)
        // unrecognized role falls back to INTERMEDIATE, never crashes
        assertEquals(RouteStopRole.INTERMEDIATE, decoded[1].role)
    }

    @Test
    fun `older route plan versions are rejected by the merge guard`() {
        val local = plan()
        val stale = listOf(RouteStop(id = "old", name = "Old Plan", latitude = 1.0, longitude = 1.0))
        val merged = RouteStopCodec.mergeRoutePlan(
            localVersion = 3L, localStops = local,
            incomingVersion = 2L, incomingStops = stale
        )
        assertEquals(Triple(3L, local, false), merged)
    }

    @Test
    fun `strictly newer route plans are adopted`() {
        val newer = listOf(RouteStop(id = "new", name = "New Plan", latitude = 2.0, longitude = 2.0))
        val merged = RouteStopCodec.mergeRoutePlan(
            localVersion = 3L, localStops = plan(),
            incomingVersion = 4L, incomingStops = newer
        )
        assertEquals(4L, merged.first)
        assertEquals(newer, merged.second)
        assertTrue(merged.third)
    }

    @Test
    fun `equal versions keep the local state without churn`() {
        val local = plan()
        val same = plan()
        val merged = RouteStopCodec.mergeRoutePlan(
            localVersion = 5L, localStops = local,
            incomingVersion = 5L, incomingStops = same
        )
        assertEquals(5L, merged.first)
        assertEquals(local, merged.second)
        assertFalse(merged.third)
    }
}
