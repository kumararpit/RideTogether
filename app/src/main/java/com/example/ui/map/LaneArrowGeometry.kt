package com.example.ui.map

import com.example.data.model.LaneArrow

/**
 * A point in the arrow glyph's own coordinate space.
 *
 * The glyphs live in a 24×24 unit box (the same box the reference
 * implementation uses for its lane glyphs, which is a generic icon-geometry
 * convention) and are scaled to the drawn size by the renderer.
 */
data class GlyphPoint(val x: Float, val y: Float)

/**
 * One stroke of an arrow glyph: a polyline with round caps and joins.
 */
data class GlyphStroke(val points: List<GlyphPoint>)

/**
 * The geometry of the lane arrow glyphs.
 *
 * These are **RideTogether-original** drawings: simple, high-contrast arrows
 * built from a shaft and a head, in the same spirit as the lane arrows a
 * driver expects (a straight arrow, a bear arrow, a turn arrow, a hard-turn
 * arrow and a U-turn arrow).
 *
 * The left/right pairs are produced by mirroring a single right-hand glyph
 * about the vertical axis, so the two can never drift apart. The renderer
 * only has to know how to stroke a polyline.
 */
object LaneArrowGeometry {

    /** The glyph box; every stroke stays inside it. */
    const val BOX = 24f

    /** The arrow head: two short lines forming a "V" at the tip. */
    private fun head(tip: GlyphPoint, left: GlyphPoint, right: GlyphPoint): List<GlyphStroke> = listOf(
        GlyphStroke(listOf(left, tip)),
        GlyphStroke(listOf(right, tip))
    )

    /** Mirrors a set of strokes about the vertical centre line. */
    fun mirror(strokes: List<GlyphStroke>): List<GlyphStroke> = strokes.map { stroke ->
        GlyphStroke(stroke.points.map { GlyphPoint(BOX - it.x, it.y) })
    }

    /** The straight-ahead arrow. */
    val STRAIGHT: List<GlyphStroke> = listOf(
        GlyphStroke(listOf(GlyphPoint(12f, 22f), GlyphPoint(12f, 8f))),
        GlyphStroke(listOf(GlyphPoint(7f, 13f), GlyphPoint(12f, 8f), GlyphPoint(17f, 13f)))
    )

    /** The right-turn arrow (the left one is its mirror). */
    val RIGHT: List<GlyphStroke> = listOf(
        GlyphStroke(listOf(GlyphPoint(4f, 21f), GlyphPoint(4f, 13f), GlyphPoint(15f, 13f))),
        GlyphStroke(listOf(GlyphPoint(10f, 8f), GlyphPoint(15f, 13f), GlyphPoint(10f, 18f)))
    )

    /** The gentle right arrow ("bear right"); the left one is its mirror. */
    val SLIGHT_RIGHT: List<GlyphStroke> = listOf(
        GlyphStroke(listOf(GlyphPoint(19f, 21f), GlyphPoint(19f, 15f), GlyphPoint(10f, 9f))),
        GlyphStroke(listOf(GlyphPoint(15f, 6f), GlyphPoint(9f, 9f), GlyphPoint(12f, 14f)))
    )

    /** The hard right arrow; the left one is its mirror. */
    val SHARP_RIGHT: List<GlyphStroke> = listOf(
        GlyphStroke(listOf(GlyphPoint(20f, 21f), GlyphPoint(20f, 16f), GlyphPoint(8f, 8f))),
        GlyphStroke(listOf(GlyphPoint(14f, 4f), GlyphPoint(8f, 8f), GlyphPoint(13f, 13f)))
    )

    /** The U-turn arrow. */
    val U_TURN: List<GlyphStroke> = listOf(
        GlyphStroke(
            listOf(
                GlyphPoint(9f, 22f),
                GlyphPoint(9f, 12f),
                GlyphPoint(9f, 8f),
                GlyphPoint(11f, 6f),
                GlyphPoint(14f, 6f),
                GlyphPoint(16f, 8f),
                GlyphPoint(16f, 11f)
            )
        ),
        GlyphStroke(listOf(GlyphPoint(13f, 9f), GlyphPoint(16f, 11f), GlyphPoint(13f, 13f)))
    )

    /** The branch of a lane that may also be used to go right. */
    val BRANCH_RIGHT: GlyphStroke = GlyphStroke(listOf(GlyphPoint(12f, 17f), GlyphPoint(19f, 11f)))

    /** The branch of a lane that may also be used to go left. */
    val BRANCH_LEFT: GlyphStroke = GlyphStroke(listOf(GlyphPoint(12f, 17f), GlyphPoint(5f, 11f)))

    /** The straight branch of a lane whose main movement is a right turn. */
    val BRANCH_STRAIGHT_RIGHT: GlyphStroke = GlyphStroke(listOf(GlyphPoint(19f, 19f), GlyphPoint(19f, 11f)))

    /** The straight branch of a lane whose main movement is a left turn. */
    val BRANCH_STRAIGHT_LEFT: GlyphStroke = GlyphStroke(listOf(GlyphPoint(5f, 19f), GlyphPoint(5f, 11f)))

    /** The strokes of an arrow, in glyph space. */
    fun strokesFor(arrow: LaneArrow): List<GlyphStroke> = when (arrow) {
        LaneArrow.NONE -> emptyList()
        LaneArrow.STRAIGHT -> STRAIGHT
        LaneArrow.U_TURN -> U_TURN
        LaneArrow.RIGHT -> RIGHT
        LaneArrow.LEFT -> mirror(RIGHT)
        LaneArrow.SLIGHT_RIGHT -> SLIGHT_RIGHT
        LaneArrow.SLIGHT_LEFT -> mirror(SLIGHT_RIGHT)
        LaneArrow.SHARP_RIGHT -> SHARP_RIGHT
        LaneArrow.SHARP_LEFT -> mirror(SHARP_RIGHT)
    }

    /**
     * The strokes of a whole **lane**: its main arrow plus a branch for every
     * further indication it carries.
     *
     * A lane marked "straight or right" is one lane that may be used for
     * either movement, so it is drawn as one arrow with a branch — never as
     * two arrows, which would read as two lanes.
     */
    fun strokesForLane(arrows: List<LaneArrow>): List<GlyphStroke> {
        val ordered = LaneArrow.canonicalOrder().filter { it in arrows }
        val primary = ordered.firstOrNull() ?: return emptyList()
        val result = strokesFor(primary).toMutableList()
        ordered.drop(1).forEach { secondary ->
            result.addAll(branchFor(primary, secondary))
        }
        return result
    }

    /**
     * The branch of a secondary indication.
     *
     * The branch is drawn off the main arrow's shaft, so the main movement
     * stays the one the eye lands on.
     */
    private fun branchFor(primary: LaneArrow, secondary: LaneArrow): List<GlyphStroke> = when (secondary) {
        LaneArrow.RIGHT, LaneArrow.SLIGHT_RIGHT, LaneArrow.SHARP_RIGHT -> listOf(BRANCH_RIGHT)
        LaneArrow.LEFT, LaneArrow.SLIGHT_LEFT, LaneArrow.SHARP_LEFT -> listOf(BRANCH_LEFT)
        LaneArrow.STRAIGHT -> when {
            primary.isRight -> listOf(BRANCH_STRAIGHT_RIGHT)
            primary.isLeft -> listOf(BRANCH_STRAIGHT_LEFT)
            else -> emptyList()
        }
        else -> emptyList()
    }

    /** True when the arrow draws nothing (an unmarked lane). */
    fun isBlank(arrow: LaneArrow): Boolean = strokesFor(arrow).isEmpty()
}
