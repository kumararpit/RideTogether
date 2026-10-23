package com.example

import com.example.ui.map.AmenityClustering
import com.example.ui.map.AmenitySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection model of the amenity layer.
 *
 * The rules under test are the ones the reference implementation's behaviour
 * implies: a tap answers to the marker the rider sees on top, a cluster
 * expands instead of selecting, and a selection dims the rest.
 */
class AmenitySelectionTest {

    private fun marker(
        id: Long,
        x: Float,
        y: Float,
        cluster: Boolean = false,
        members: List<Long> = emptyList(),
        drawIndex: Int = 0
    ) = AmenitySelection.Marker(
        id = id,
        x = x,
        y = y,
        isCluster = cluster,
        memberIds = members,
        drawIndex = drawIndex
    )

    @Test
    fun `the topmost marker under the tap answers the tap`() {
        val markers = listOf(
            marker(id = 1L, x = 100f, y = 100f, drawIndex = 0),
            marker(id = 2L, x = 104f, y = 104f, drawIndex = 1)
        )
        val outcome = AmenitySelection.resolveTap(102f, 102f, markers, radiusPx = 24f)
        assertTrue(outcome is AmenitySelection.TapOutcome.Picked)
        assertEquals(2L, (outcome as AmenitySelection.TapOutcome.Picked).amenityId)
    }

    @Test
    fun `a marker that is drawn under another cannot steal the tap`() {
        // The lower marker is listed FIRST (the data order), but it is drawn
        // under the upper one, so the upper one must answer.
        val markers = listOf(
            marker(id = 1L, x = 100f, y = 100f, drawIndex = 0),
            marker(id = 2L, x = 100f, y = 100f, drawIndex = 1)
        )
        val outcome = AmenitySelection.resolveTap(100f, 100f, markers, radiusPx = 24f)
        assertEquals(2L, (outcome as AmenitySelection.TapOutcome.Picked).amenityId)
    }

    @Test
    fun `a cluster expands instead of selecting`() {
        val markers = listOf(
            marker(id = 7L, x = 50f, y = 50f, cluster = true, members = listOf(3L, 7L, 9L), drawIndex = 0)
        )
        val outcome = AmenitySelection.resolveTap(50f, 50f, markers, radiusPx = 24f)
        assertTrue(outcome is AmenitySelection.TapOutcome.Expanded)
        assertEquals(listOf(3L, 7L, 9L), (outcome as AmenitySelection.TapOutcome.Expanded).memberIds)
    }

    @Test
    fun `a tap on empty map selects nothing`() {
        val outcome = AmenitySelection.resolveTap(500f, 500f, emptyList(), radiusPx = 24f)
        assertTrue(outcome is AmenitySelection.TapOutcome.Nothing)
    }

    @Test
    fun `a tap outside the touch target selects nothing`() {
        val markers = listOf(marker(id = 1L, x = 100f, y = 100f, drawIndex = 0))
        val outcome = AmenitySelection.resolveTap(140f, 100f, markers, radiusPx = 24f)
        assertTrue(outcome is AmenitySelection.TapOutcome.Nothing)
    }

    @Test
    fun `a selection dims the other markers but keeps itself bright`() {
        assertEquals(1f, AmenitySelection.markerAlpha(isSelected = true, hasSelection = true), 0.001f)
        assertEquals(0.4f, AmenitySelection.markerAlpha(isSelected = false, hasSelection = true), 0.001f)
        assertEquals(1f, AmenitySelection.markerAlpha(isSelected = false, hasSelection = false), 0.001f)
    }

    @Test
    fun `an expanded amenity is never merged into a cluster again`() {
        val points = listOf(
            1L to (100f to 100f),
            2L to (110f to 100f),
            3L to (120f to 100f)
        )
        val clustered = AmenityClustering.cluster(points, maxDistancePx = 30f)
        assertEquals(1, clustered.size)
        assertEquals(3, clustered.first().count)

        // The rider taps the cluster: its members are expanded and stay
        // separate from then on.
        val expanded = AmenityClustering.cluster(
            points = points,
            maxDistancePx = 30f,
            expandedIds = setOf(1L, 2L, 3L)
        )
        assertEquals(3, expanded.size)
        assertTrue(expanded.all { it.count == 1 })
    }

    @Test
    fun `expanding one member leaves the others clustered`() {
        val points = listOf(
            1L to (100f to 100f),
            2L to (110f to 100f),
            3L to (400f to 400f)
        )
        val expanded = AmenityClustering.cluster(
            points = points,
            maxDistancePx = 30f,
            expandedIds = setOf(1L)
        )
        val single = expanded.first { it.memberIds == listOf(1L) }
        assertEquals(1, single.count)
        val pair = expanded.first { it.memberIds.containsAll(listOf(2L, 3L)) || it.memberIds.contains(2L) }
        assertTrue(pair.memberIds.contains(2L))
    }
}
