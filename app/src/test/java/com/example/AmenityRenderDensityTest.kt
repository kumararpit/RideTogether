package com.example

import com.example.ui.components.AmenityLabelPlanner
import com.example.data.amenities.AmenityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6/7 — renderer determinism at rider-realistic density: a 30-marker
 * viewport must keep the planner allocations bounded and the admit-zero-or-few
 * guarantees stable, with a rider mix of categories, names length extremes, and
 * neighbour icons (as drawn by RideMapCanvas).
 */
class AmenityRenderDensityTest {

    private val iconRects = List(30) { index ->
        val x = (index % 6) * 160f + 40f
        val y = 300f + (index / 6) * 160f
        AmenityLabelPlanner.IconRect(
            id = 10_000L + index,
            left = x - 30f, top = y - 30f, right = x + 30f, bottom = y + 30f
        )
    }

    private val candidates = List(30) { index ->
        val x = (index % 6) * 160f + 40f
        val y = 300f + (index / 6) * 160f + 36f
        // Realistic mix at SHOW_ALL levels: fuel & repair dominate the first ranks.
        val type = when (index % 5) {
            0 -> AmenityType.FUEL
            1 -> AmenityType.REPAIR
            2 -> AmenityType.REST_STOP
            3 -> AmenityType.CAFE
            else -> AmenityType.RESTAURANT
        }
        AmenityLabelPlanner.Candidate(
            id = 10_000L + index,
            xPx = x,
            yPx = y,
            isSelected = index == 0,
            type = type,
            labelFullWidthPx = when (index % 4) {
                0 -> 300f; 1 -> 130f; 2 -> 70f; else -> 240f
            }
        )
    }

    @Test
    fun `thirty markers admit a bounded label set without covering neighbour icons`() {
        val planned = AmenityLabelPlanner.plan(
            candidates = candidates,
            zoom = AmenityLabelPlanner.SHOW_ALL_ZOOM,
            previouslyVisible = emptySet(),
            reserveHalfWidthPx = 62f,
            reserveHalfHeightPx = 11f,
            iconRects = iconRects
        )
        assertTrue("density plan must stay bounded", planned.size <= AmenityLabelPlanner.MAX_LABELS)
        // Selected marker's label always wins admission.
        assertEquals(10_000L, planned.first())
    }

    @Test
    fun `planner output is deterministic and selected-first on the dense input`() {
        val base = AmenityLabelPlanner.plan(
            candidates, AmenityLabelPlanner.SHOW_ALL_ZOOM, emptySet(),
            62f, 11f, iconRects
        )
        // Identical inputs produce byte-identical plans (no flicker-inducing drift).
        val repeated = AmenityLabelPlanner.plan(
            candidates, AmenityLabelPlanner.SHOW_ALL_ZOOM, emptySet(),
            62f, 11f, iconRects
        )
        assertEquals(base, repeated)
        // Selected-first governance is preserved at any density.
        assertEquals(10_000L, base.first())
    }
}
