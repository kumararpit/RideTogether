package com.example

import com.example.data.amenities.AmenityType
import com.example.ui.map.AmenityClustering
import com.example.ui.map.AmenityPalette
import com.example.ui.map.FrameBudget
import com.example.ui.map.FrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame budget (backlog B-18) and amenity palette/clustering (backlog B-14).
 */
class FrameBudgetTest {

    private fun budgetAt(fps: Int, clock: () -> Long) = FrameBudget(fps, clock)

    @Test
    fun `the first frame always renders`() {
        var now = 1_000L
        val budget = budgetAt(60) { now }
        assertTrue(budget.shouldRender(now))
        assertEquals(1, budget.renderedFrames)
    }

    @Test
    fun `frames are limited to the target rate`() {
        var now = 0L
        val budget = budgetAt(60) { now }
        val interval = 1_000_000_000L / 60

        // 10 frames offered 1 ms apart: only the first may be drawn.
        var rendered = 0
        repeat(10) {
            now += 1_000_000L
            if (budget.shouldRender(now)) rendered++
        }
        assertEquals(1, rendered)
        assertEquals(9, budget.skippedFrames)

        // Once the interval has elapsed, the next frame is drawn.
        now += interval
        assertTrue(budget.shouldRender(now))
        assertEquals(2, budget.renderedFrames)
    }

    @Test
    fun `a 30 fps budget renders half as often as a 60 fps budget`() {
        val interval30 = 1_000_000_000L / 30
        var now = 0L
        val budget = budgetAt(30) { now }
        var rendered = 0
        repeat(10) {
            now += 10_000_000L // 10 ms apart
            if (budget.shouldRender(now)) rendered++
        }
        assertTrue("expected ~3 rendered frames in 100 ms at 30 fps, got $rendered", rendered in 3..4)
        assertTrue(budget.minIntervalNanos == interval30)
    }

    @Test
    fun `frame durations are counted and summarised`() {
        val budget = FrameBudget(60)
        repeat(10) { budget.recordFrameDuration(10_000_000L) }   // 10 ms
        repeat(5) { budget.recordFrameDuration(40_000_000L) }    // 40 ms (over budget)
        val stats = budget.stats()
        assertEquals(5, stats.framesOverBudget)
        assertTrue(stats.averageFrameMs > 10.0)
        assertTrue(stats.p95FrameMs >= 10.0)
    }

    @Test
    fun `the statistics summary is readable`() {
        val budget = FrameBudget(30)
        budget.shouldRender(0L)
        budget.recordFrameDuration(5_000_000L)
        val summary = budget.stats().summary()
        assertTrue(summary.contains("target=30fps"))
        assertTrue(summary.contains("rendered=1"))
    }

    @Test
    fun `the on-budget ratio is reported`() {
        val budget = FrameBudget(60)
        repeat(4) { budget.recordFrameDuration(5_000_000L) }
        assertEquals(1.0, budget.stats().onBudgetRatio(), 0.001)
    }

    @Test
    fun `reset clears the counters`() {
        val budget = FrameBudget(60)
        budget.shouldRender(0L)
        budget.recordFrameDuration(5_000_000L)
        budget.reset()
        assertEquals(0, budget.stats().totalFrames)
        assertEquals(0, budget.stats().framesOverBudget)
    }
}

class AmenityPaletteTest {

    @Test
    fun `every category colour is separable from the day map`() {
        AmenityType.entries.forEach { type ->
            val color = AmenityPalette.colorFor(type, AmenityPalette.Theme.DAY)
            assertTrue(
                "$type is not separable from the day raster",
                AmenityPalette.isSeparated(color, AmenityPalette.dayBackground)
            )
        }
    }

    @Test
    fun `every category colour is separable from the night map`() {
        AmenityType.entries.forEach { type ->
            val color = AmenityPalette.colorFor(type, AmenityPalette.Theme.NIGHT)
            assertTrue(
                "$type is not separable from the night raster",
                AmenityPalette.isSeparated(color, AmenityPalette.nightBackground)
            )
        }
    }

    @Test
    fun `the two themes differ for every category`() {
        AmenityType.entries.forEach { type ->
            assertNotEquals(
                "$type has the same colour in both themes",
                AmenityPalette.colorFor(type, AmenityPalette.Theme.DAY),
                AmenityPalette.colorFor(type, AmenityPalette.Theme.NIGHT)
            )
        }
    }

    @Test
    fun `the contrast ratio behaves like WCAG`() {
        assertEquals(
            "identical colours have a ratio of 1",
            1.0,
            AmenityPalette.contrastRatio(AmenityPalette.dayBackground, AmenityPalette.dayBackground),
            0.01
        )
        val black = androidx.compose.ui.graphics.Color(0xFF000000)
        val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        assertEquals(21.0, AmenityPalette.contrastRatio(black, white), 0.01)
    }
}

class AmenityClusteringTest {

    private fun clusterOf(ids: List<Long>, positions: Map<Long, Pair<Float, Float>>) =
        AmenityClustering.cluster(ids.map { it to positions.getValue(it) }, maxDistancePx = 30f)

    @Test
    fun `nearby amenities are grouped and distant ones are not`() {
        val ids = listOf(1L, 2L, 3L)
        val positions = mapOf(
            1L to (100f to 100f),
            2L to (110f to 108f),
            3L to (600f to 600f)
        )
        val clusters = clusterOf(ids, positions)
        assertEquals(2, clusters.size)
        val group = clusters.first { it.memberIds.contains(1L) }
        assertEquals(listOf(1L, 2L), group.memberIds)
        assertEquals(2, group.count)
        assertTrue(group.isGroup)
    }

    @Test
    fun `the representative is the smallest id and the result is order independent`() {
        val positions = mapOf(
            7L to (100f to 100f),
            3L to (104f to 102f),
            9L to (101f to 106f)
        )
        val forward = clusterOf(listOf(7L, 3L, 9L), positions)
        val reversed = clusterOf(listOf(9L, 3L, 7L), positions)
        assertEquals(1, forward.size)
        assertEquals(3L, forward.first().representativeId)
        assertEquals(forward.first().memberIds, reversed.first().memberIds)
    }

    @Test
    fun `a single amenity is its own cluster`() {
        val clusters = clusterOf(listOf(5L), mapOf(5L to (10f to 10f)))
        assertEquals(1, clusters.size)
        assertEquals(1, clusters.first().count)
        assertFalse(clusters.first().isGroup)
    }

    @Test
    fun `an empty input produces no clusters`() {
        assertTrue(AmenityClustering.cluster(emptyList(), 30f).isEmpty())
    }

    @Test
    fun `clusters are stable across repeated runs`() {
        val positions = mapOf(
            1L to (100f to 100f),
            2L to (108f to 104f),
            3L to (300f to 300f)
        )
        val first = clusterOf(listOf(1L, 2L, 3L), positions)
        val second = clusterOf(listOf(1L, 2L, 3L), positions)
        assertEquals(first.map { it.representativeId }, second.map { it.representativeId })
    }
}
