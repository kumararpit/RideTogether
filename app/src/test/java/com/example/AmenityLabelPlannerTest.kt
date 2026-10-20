package com.example

import com.example.data.amenities.AmenityType
import com.example.ui.components.AmenityLabelPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic amenity label placement tests (Task 18, Part B): zoom policy,
 * hysteresis, priority ordering, collision suppression and the label cap.
 */
class AmenityLabelPlannerTest {

    private fun candidate(
        id: Long,
        x: Float,
        y: Float,
        isSelect: Boolean = false,
        type: AmenityType = AmenityType.FUEL
    ) = AmenityLabelPlanner.Candidate(
        id = id, xPx = x, yPx = y, isSelected = isSelect, type = type
    )

    @Test
    fun `zoom policy gates categories per the documented thresholds`() {
        val selected = candidate(1, 200f, 200f, isSelect = true, type = AmenityType.CAFE)
        val fuel = candidate(2, 400f, 200f, type = AmenityType.FUEL)
        val cafe = candidate(3, 600f, 200f, type = AmenityType.CAFE)

        // Far zoom: nothing.
        val far = AmenityLabelPlanner.plan(listOf(selected, fuel, cafe), 12.0f, emptySet(), 60f, 11f)
        assertTrue(far.isEmpty())

        // Selected only: selected visible even though others are not.
        val selectedOnly = AmenityLabelPlanner.plan(listOf(selected, fuel, cafe), 13.0f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L), selectedOnly)

        // Important categories: fuel visible, cafe not yet.
        val important = AmenityLabelPlanner.plan(listOf(selected, fuel, cafe), 14.3f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L, 2L), important)

        // Show-all: every named category eligible (subject to collision).
        val all = AmenityLabelPlanner.plan(listOf(selected, fuel, cafe), 15.7f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L, 2L, 3L), all)
    }

    @Test
    fun `hysteresis keeps previously visible labels across small zoom dips`() {
        // Cafe labels first appear at 15.6; 15.4 is below that threshold.
        val cafe = candidate(2, 400f, 200f, type = AmenityType.CAFE)
        // Without history: hidden.
        val notVisible = AmenityLabelPlanner.plan(
            listOf(cafe), 15.4f, emptySet(), 60f, 11f
        )
        assertTrue(notVisible.isEmpty())

        // With one-frame history: the 0.25 hysteresis keeps it visible.
        val wasVisible = AmenityLabelPlanner.plan(
            listOf(cafe), 15.4f, setOf(2L), 60f, 11f
        )
        assertEquals(listOf(2L), wasVisible)

        // Below the global hysteresis floor nothing survives.
        val floor = AmenityLabelPlanner.plan(
            listOf(cafe), 11.0f, setOf(2L), 60f, 11f
        )
        assertTrue(floor.isEmpty())
    }

    @Test
    fun `priority order is selected first then category importance then list order`() {
        val cafe = candidate(10, 200f, 200f, type = AmenityType.CAFE)
        val fuel = candidate(11, 200f, 200f, type = AmenityType.FUEL)
        val selectedCafe = candidate(12, 200f, 200f, isSelect = true, type = AmenityType.CAFE)

        val plan = AmenityLabelPlanner.plan(
            listOf(cafe, fuel, selectedCafe), 16.0f, emptySet(), 60f, 11f, maxLabels = 1
        )
        // One label fits the position: selected amenity beats category importance.
        assertEquals(listOf(12L), plan)

        val planNoSelected = AmenityLabelPlanner.plan(
            listOf(cafe, fuel), 16.0f, emptySet(), 60f, 11f, maxLabels = 1
        )
        // Fuel outranks cafe at the same location.
        assertEquals(listOf(11L), planNoSelected)
    }

    @Test
    fun `collision suppresses secondary labels without moving coordinates`() {
        val fuel = candidate(1, 300f, 300f, type = AmenityType.FUEL)
        val cafe = candidate(2, 305f, 305f, type = AmenityType.CAFE) // overlapping box
        val cafeFar = candidate(3, 800f, 900f, type = AmenityType.CAFE) // separate

        val plan = AmenityLabelPlanner.plan(listOf(fuel, cafe, cafeFar), 16.0f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L, 3L), plan)

        // Draw order stays stable across repeated calls (deterministic).
        val again = AmenityLabelPlanner.plan(listOf(fuel, cafe, cafeFar), 16.0f, emptySet(), 60f, 11f)
        assertEquals(plan, again)
    }

    @Test
    fun `cap honors maxLabels when boxes fit`() {
        val spread = (0 until 5).map { candidate(it.toLong() + 1, 100f + it * 400f, 100f) }
        val plan = AmenityLabelPlanner.plan(spread, 16.0f, emptySet(), 60f, 11f, maxLabels = 2)
        assertEquals(2, plan.size)
        assertEquals(listOf(1L, 2L), plan) // highest priority (list order) wins
    }

    @Test
    fun `empty input and below-floor zoom always produce empty plans`() {
        assertTrue(AmenityLabelPlanner.plan(emptyList(), 17f, emptySet(), 60f, 11f).isEmpty())
        val c = listOf(candidate(1, 100f, 100f))
        assertTrue(AmenityLabelPlanner.plan(c, 11.9f, setOf(1L), 60f, 11f).isEmpty())
    }

    @Test
    fun `category ranks are deterministic and fuel is the highest priority`() {
        assertTrue(
            AmenityLabelPlanner.categoryRank(AmenityType.FUEL) <
                AmenityLabelPlanner.categoryRank(AmenityType.REPAIR)
        )
    }

    // ---- Narrow per-label collision boxes (Maps-style: box fits the text) ----

    private fun candidateW(
        id: Long, x: Float, y: Float, fullWidth: Float,
        isSelect: Boolean = false, type: AmenityType = AmenityType.CAFE
    ) = AmenityLabelPlanner.Candidate(
        id = id, xPx = x, yPx = y, isSelected = isSelect, type = type,
        labelFullWidthPx = fullWidth
    )

    @Test
    fun `short-name estimate shrinks the box so dense rows admit more labels`() {
        // Original builder for regression guard: a CAFE far off-screen is fine either way.
        val a = candidateW(1, 100f, 100f, fullWidth = 60f)
            .copy(type = AmenityType.FUEL)
        val b = candidateW(2, 220f, 100f, fullWidth = 60f)
            .copy(type = AmenityType.FUEL)
        // Legacy fixed budget (half=60): A spans 40..160, B spans 160..280 -> boxes
        // touch, so B would be suppressed. Narrow halves (30) leave free space, so
        // both labels are admitted.
        val plan = AmenityLabelPlanner.plan(listOf(a, b), 16.0f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L, 2L), plan)
    }

    @Test
    fun `estimate is clamped to never exceed the fixed render budget`() {
        // A long-name estimate beyond the budget must not reserve more than legacy.
        val a = candidateW(1, 300f, 300f, fullWidth = 260f)
            .copy(type = AmenityType.FUEL)
        val b = candidateW(2, 305f, 300f, fullWidth = 260f).copy(type = AmenityType.CAFE)
        val plan = AmenityLabelPlanner.plan(listOf(a, b), 16.0f, emptySet(), 60f, 11f)
        // B still suppressed exactly like the legacy fixed-budget behavior.
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun `very short estimates cannot collapse below one third of the budget`() {
        // Tiny estimates must still reserve at least reserveHalfWidth/3 (=20 here);
        // at 40px apart the clamped boxes (Â±20) touch, so the second is suppressed.
        val a = candidateW(1, 100f, 100f, fullWidth = 4f).copy(type = AmenityType.FUEL)
        val b = candidateW(2, 139f, 100f, fullWidth = 4f).copy(type = AmenityType.FUEL)
        val plan = AmenityLabelPlanner.plan(listOf(a, b), 16.0f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L), plan)
    }

    // ---- Stability within rank (previously visible labels keep their boxes) ----

    @Test
    fun `previously visible label wins against same-rank newcomer at equal priority`() {
        // Same rank (FUEL), overlapping boxes; the newcomer is listed FIRST so list
        // order cannot mask the comparator change. Legacy: newcomer (first) wins.
        val newcomerB = candidate(9, 300f, 300f, type = AmenityType.FUEL)
        val visibleA = candidate(1, 305f, 300f, type = AmenityType.FUEL)
        val plan = AmenityLabelPlanner.plan(listOf(newcomerB, visibleA), 16.0f, setOf(1L), 60f, 11f)
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun `higher-rank newcomer still displaces a visible lower-rank label`() {
        // FUEL (rank 0, new) vs CAFE (rank 3, previously visible): priority wins.
        val fuelNew = candidate(7, 300f, 300f, type = AmenityType.FUEL)
        val cafeVisible = candidate(2, 305f, 300f, type = AmenityType.CAFE)
        val plan = AmenityLabelPlanner.plan(listOf(fuelNew, cafeVisible), 16.0f, setOf(2L), 60f, 11f)
        // Fuel reserves first (priority); coffee's previously-visible status does not
        // grant it the box over a higher-rank newcomer.
        assertEquals(listOf(7L), plan)
    }

    // ---- Icon avoidance (labels never paint over another POI's marker) ----
    // Icon geometry mirrors the renderer at density 3, zoom 15+ (28 dp pin):
    // footprint radius = pinRadius(42 px) + outline half-stroke(6.9 px) ≈ 49 px; the
    // teardrop tip and the drop shadow (tipY + 3 px, radius 0.45 x pinRadius) put the
    // painted bottom edge ≈ 22 px below the tip; the label band starts tipY + 9 px.

    private fun iconRect(id: Long, left: Float, top: Float, right: Float, bottom: Float) =
        AmenityLabelPlanner.IconRect(id, left, top, right, bottom)

    @Test
    fun `label is suppressed when it would paint over a neighboring POI icon`() {
        val a = candidate(1, 300f, 300f, type = AmenityType.FUEL)
        // Neighbor pin ~10 px right, ~49 px footprint radius: (310, 260) to (350, 350).
        val neighborIcon = iconRect(2, 310f, 260f, 350f, 350f)

        // Fail-before guard: with no iconRects the label is admitted.
        val legacy = AmenityLabelPlanner.plan(listOf(a), 16.0f, emptySet(), 60f, 11f)
        assertEquals(listOf(1L), legacy)

        val plan = AmenityLabelPlanner.plan(listOf(a), 16.0f, emptySet(), 60f, 11f, listOf(neighborIcon))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `own marker footprint does not block its own label`() {
        val a = candidate(1, 300f, 300f, type = AmenityType.CAFE)
        // A's own painted bounds extend below its band top (tipY + 9 px); the pill
        // renders below the tip, so the own footprint must be exempt.
        val own = iconRect(1, 250f, 250f, 350f, 310f)
        val plan = AmenityLabelPlanner.plan(listOf(a), 16.0f, emptySet(), 60f, 11f, listOf(own))
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun `icon of an unnamed POI suppresses nearby labels`() {
        val named = candidate(1, 300f, 300f, type = AmenityType.CAFE)
        val far = candidate(2, 900f, 900f, type = AmenityType.CAFE)
        // POI 77 has no name, hence no Candidate — its marker still paints.
        val unnamedIcon = iconRect(77, 310f, 260f, 350f, 350f)
        val plan = AmenityLabelPlanner.plan(listOf(named, far), 16.0f, emptySet(), 60f, 11f, listOf(unnamedIcon))
        assertEquals(listOf(2L), plan)
    }

    @Test
    fun `dense cluster where every label would cover another pin yields no labels`() {
        val fuel = candidate(1, 300f, 300f, type = AmenityType.FUEL)
        val cafe = candidate(2, 320f, 300f, type = AmenityType.CAFE)
        val icons = listOf(
            iconRect(1, 250f, 200f, 350f, 342f),
            iconRect(2, 270f, 200f, 370f, 342f)
        )
        // Even the top-priority fuel label paints over cafe's pin, and vice versa:
        // suppression (not repositioning) keeps every marker readable.
        val plan = AmenityLabelPlanner.plan(listOf(fuel, cafe), 16.0f, emptySet(), 60f, 11f, icons)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `icons clear of the label band do not suppress`() {
        val a = candidate(1, 300f, 300f, type = AmenityType.CAFE)
        val above = iconRect(2, 310f, 150f, 350f, 290f) // bottom above band top (300)
        val below = iconRect(3, 310f, 330f, 350f, 400f) // top below band bottom (322)
        val plan = AmenityLabelPlanner.plan(listOf(a), 16.0f, emptySet(), 60f, 11f, listOf(above, below))
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun `zoom gating is unchanged by icon rects`() {
        val a = candidate(1, 300f, 300f, type = AmenityType.CAFE)
        val plan = AmenityLabelPlanner.plan(
            listOf(a), 11.9f, emptySet(), 60f, 11f,
            listOf(iconRect(2, 310f, 260f, 350f, 350f))
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `short-name boxes clear icons that the legacy fixed budget would hit`() {
        val short = candidateW(1, 300f, 300f, fullWidth = 70f).copy(type = AmenityType.CAFE)
        // Pin at x>=350: outside the estimated half-width (35 px) but inside the
        // legacy 60 px budget.
        val plan = AmenityLabelPlanner.plan(
            listOf(short), 16.0f, emptySet(), 60f, 11f,
            listOf(iconRect(2, 350f, 260f, 380f, 350f))
        )
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun `long-name box also respects neighbor icons`() {
        val longName = candidateW(1, 300f, 300f, fullWidth = 100f).copy(type = AmenityType.FUEL)
        val plan = AmenityLabelPlanner.plan(
            listOf(longName), 16.0f, emptySet(), 60f, 11f,
            listOf(iconRect(2, 340f, 260f, 360f, 350f))
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `stability and priority rules survive when icon rects are present`() {
        val fuelNew = candidate(7, 300f, 300f, type = AmenityType.FUEL)
        val cafeVisible = candidate(2, 305f, 300f, type = AmenityType.CAFE)
        val unrelatedIcon = iconRect(99, 1000f, 1000f, 1050f, 1050f)
        val plan = AmenityLabelPlanner.plan(
            listOf(fuelNew, cafeVisible), 16.0f, setOf(2L), 60f, 11f, listOf(unrelatedIcon)
        )
        assertEquals(listOf(7L), plan)
    }
}
