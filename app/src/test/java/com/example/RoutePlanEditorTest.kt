package com.example

import com.example.data.model.LatLng
import com.example.data.model.RoutePlanEditor
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plan edits behind "remove a stop": the origin and destination are never
 * removable, the order of the survivors is preserved, and a stop that is
 * already behind the rider never reappears in the remaining sequence.
 */
class RoutePlanEditorTest {

    private val origin = RouteStop(
        id = "origin", name = "Home",
        latitude = 18.52, longitude = 73.85, role = RouteStopRole.ORIGIN
    )
    private val tea = RouteStop(
        id = "tea", name = "Tea Point",
        latitude = 18.56, longitude = 73.80, role = RouteStopRole.INTERMEDIATE
    )
    private val fuel = RouteStop(
        id = "fuel", name = "Fuel B",
        latitude = 18.64, longitude = 73.72, role = RouteStopRole.INTERMEDIATE
    )
    private val destination = RouteStop(
        id = "destination", name = "Work",
        latitude = 18.75, longitude = 73.40, role = RouteStopRole.DESTINATION
    )
    private val plan = listOf(origin, tea, fuel, destination)

    @Test
    fun `removing a stop preserves the order of the survivors`() {
        val edited = RoutePlanEditor.withoutIntermediate(plan, "tea")
        assertEquals(listOf("origin", "fuel", "destination"), edited.map { it.id })
    }

    @Test
    fun `the origin and destination can never be removed`() {
        assertEquals(plan, RoutePlanEditor.withoutIntermediate(plan, "origin"))
        assertEquals(plan, RoutePlanEditor.withoutIntermediate(plan, "destination"))
    }

    @Test
    fun `removing an unknown id is a no-op`() {
        assertEquals(plan, RoutePlanEditor.withoutIntermediate(plan, "missing"))
    }

    @Test
    fun `remaining plan drops completed legs and the origin but keeps the destination last`() {
        val remaining = RoutePlanEditor.remainingAfterLeg(plan, currentLegIndex = 1)
        assertEquals(listOf("fuel", "destination"), remaining.map { it.id })
        assertEquals(RouteStopRole.DESTINATION, remaining.last().role)
        assertEquals(
            "the leg in progress is not repeated in a reroute",
            emptyList<String>(),
            RoutePlanEditor.remainingAfterLeg(plan, currentLegIndex = 3).map { it.id }
        )
    }

    @Test
    fun `intermediates keeps plan order and never includes endpoints`() {
        assertEquals(listOf("tea", "fuel"), RoutePlanEditor.intermediates(plan).map { it.id })
        assertEquals(
            emptyList<String>(),
            RoutePlanEditor.intermediates(listOf(origin, destination)).map { it.id }
        )
        // A plan made only of endpoints still has a valid (empty) intermediate list.
        assertEquals(LatLng(18.52, 73.85), plan.first().latLng)
    }
}
