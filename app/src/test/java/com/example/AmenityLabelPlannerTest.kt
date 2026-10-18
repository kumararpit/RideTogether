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
}
