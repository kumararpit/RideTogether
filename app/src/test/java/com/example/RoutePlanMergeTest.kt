package com.example

import com.example.data.model.RouteOptionSummary
import com.example.data.model.RoutePlan
import com.example.data.model.RoutePlanCodec
import com.example.data.model.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route-plan merge guard.
 *
 * The ordered stops, the candidate list and the leader's selected candidate
 * are ONE plan: they travel together through the version guard, so a stale
 * snapshot can never pair new stops with an old selection (or vice versa).
 */
class RoutePlanMergeTest {

    private fun stop(id: String) = RouteStop(id = id, name = id, latitude = 18.5, longitude = 73.8)

    private val options = listOf(
        RouteOptionSummary(index = 0, distanceMeters = 8300.0, durationSeconds = 540.0),
        RouteOptionSummary(index = 1, distanceMeters = 10320.0, durationSeconds = 576.0)
    )

    @Test
    fun `a strictly newer plan is adopted in full`() {
        val local = RoutePlan(version = 1, stops = listOf(stop("a")), selectedRouteIndex = 0, options = options)
        val incoming = RoutePlan(version = 2, stops = listOf(stop("a"), stop("b")), selectedRouteIndex = 1, options = options)

        val merged = RoutePlanCodec.merge(local, incoming)

        assertEquals(2L, merged.version)
        assertEquals(listOf("a", "b"), merged.stops.map { it.id })
        assertEquals(1, merged.selectedRouteIndex)
        assertEquals(2, merged.options.size)
    }

    @Test
    fun `an older plan is rejected and the local selection survives`() {
        val local = RoutePlan(version = 5, stops = listOf(stop("a")), selectedRouteIndex = 1, options = options)
        val incoming = RoutePlan(version = 4, stops = listOf(stop("a"), stop("b")), selectedRouteIndex = 0, options = emptyList())

        val merged = RoutePlanCodec.merge(local, incoming)

        assertEquals(5L, merged.version)
        assertEquals(listOf("a"), merged.stops.map { it.id })
        assertEquals(1, merged.selectedRouteIndex)
        assertEquals(2, merged.options.size)
    }

    @Test
    fun `an equal version keeps the local state without churn`() {
        val local = RoutePlan(version = 3, stops = listOf(stop("a")), selectedRouteIndex = 0, options = options)
        val incoming = RoutePlan(version = 3, stops = listOf(stop("z")), selectedRouteIndex = 1, options = emptyList())

        val merged = RoutePlanCodec.merge(local, incoming)

        assertEquals(3L, merged.version)
        assertEquals(listOf("a"), merged.stops.map { it.id })
        assertEquals(0, merged.selectedRouteIndex)
    }

    @Test
    fun `candidate summaries survive a Firestore round trip`() {
        val encoded = RoutePlanCodec.optionsToFirestore(options)
        val decoded = RoutePlanCodec.optionsFromFirestore(encoded)
        assertEquals(options, decoded)
    }

    @Test
    fun `malformed candidate entries are skipped without crashing`() {
        val decoded = RoutePlanCodec.optionsFromFirestore(
            listOf(
                mapOf("index" to 0, "distanceMeters" to 100.0, "durationSeconds" to 60.0),
                mapOf("distanceMeters" to 100.0, "durationSeconds" to 60.0), // no index
                "not-a-map",
                mapOf("index" to 1, "durationSeconds" to 60.0) // no distance
            )
        )
        assertEquals(1, decoded.size)
        assertEquals(0, decoded.first().index)
    }

    @Test
    fun `an absent candidate list means a single route`() {
        assertTrue(RoutePlanCodec.optionsFromFirestore(null).isEmpty())
        assertTrue(RoutePlanCodec.optionsFromFirestore(emptyList<Any>()).isEmpty())
        assertFalse(RoutePlanCodec.optionsToFirestore(emptyList()).isNotEmpty())
    }
}
