package com.example

import com.example.data.model.LaneArrow
import com.example.ui.map.LaneArrowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane arrow geometry: the drawings are RideTogether-original, and the
 * left/right pair must always be exact mirrors of one another so a mirrored
 * arrow can never disagree with the manoeuvre it describes.
 */
class LaneArrowGeometryTest {

    @Test
    fun `every arrow is drawn inside the glyph box`() {
        for (arrow in LaneArrow.values()) {
            for (stroke in LaneArrowGeometry.strokesFor(arrow)) {
                for (point in stroke.points) {
                    assertTrue(
                        "${arrow.name} escapes the box at ${point.x},${point.y}",
                        point.x in 0f..LaneArrowGeometry.BOX && point.y in 0f..LaneArrowGeometry.BOX
                    )
                }
            }
        }
    }

    @Test
    fun `a left arrow is the exact mirror of its right counterpart`() {
        val pairs = listOf(
            LaneArrow.LEFT to LaneArrow.RIGHT,
            LaneArrow.SLIGHT_LEFT to LaneArrow.SLIGHT_RIGHT,
            LaneArrow.SHARP_LEFT to LaneArrow.SHARP_RIGHT
        )
        for ((left, right) in pairs) {
            val leftStrokes = LaneArrowGeometry.strokesFor(left)
            val rightStrokes = LaneArrowGeometry.strokesFor(right)
            assertEquals(rightStrokes.size, leftStrokes.size)
            rightStrokes.forEachIndexed { index, rightStroke ->
                val mirrored = rightStroke.points.map {
                    com.example.ui.map.GlyphPoint(LaneArrowGeometry.BOX - it.x, it.y)
                }
                assertEquals(
                    "${left.name} is not the mirror of ${right.name}",
                    mirrored,
                    leftStrokes[index].points
                )
            }
        }
    }

    @Test
    fun `an unmarked lane draws nothing`() {
        assertTrue(LaneArrowGeometry.strokesFor(LaneArrow.NONE).isEmpty())
        assertTrue(LaneArrowGeometry.isBlank(LaneArrow.NONE))
    }

    @Test
    fun `every arrow that is not unmarked has a shaft and a head`() {
        for (arrow in LaneArrow.values()) {
            if (arrow == LaneArrow.NONE) continue
            val strokes = LaneArrowGeometry.strokesFor(arrow)
            assertTrue("${arrow.name} has no strokes", strokes.isNotEmpty())
            assertTrue("${arrow.name} has no head", strokes.size >= 2)
        }
    }

    @Test
    fun `the u-turn is distinct from a turn`() {
        assertTrue(
            LaneArrowGeometry.strokesFor(LaneArrow.U_TURN) != LaneArrowGeometry.strokesFor(LaneArrow.LEFT)
        )
        assertTrue(
            LaneArrowGeometry.strokesFor(LaneArrow.U_TURN) != LaneArrowGeometry.strokesFor(LaneArrow.RIGHT)
        )
    }
}
