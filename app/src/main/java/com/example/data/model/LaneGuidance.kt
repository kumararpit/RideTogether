package com.example.data.model

/**
 * The arrow a single lane can carry.
 *
 * The vocabulary is the routing engine's own lane-indication vocabulary
 * (OSRM exposes the `turn:lanes` marking of the road as one or more
 * indications per lane), not an invented one. A lane may carry **several**
 * indications at once ("straight or right"), which is why a lane is modelled
 * as a set.
 */
enum class LaneArrow {
    /** The lane has no usable marking ("none"). */
    NONE,

    /** Straight ahead only. */
    STRAIGHT,

    /** A gentle left ("slight left"). */
    SLIGHT_LEFT,

    /** A left turn. */
    LEFT,

    /** A hard left ("sharp left"). */
    SHARP_LEFT,

    /** A U-turn. */
    U_TURN,

    /** A gentle right ("slight right"). */
    SLIGHT_RIGHT,

    /** A right turn. */
    RIGHT,

    /** A hard right ("sharp right"). */
    SHARP_RIGHT;

    /** True for the left-pointing arrows. */
    val isLeft: Boolean
        get() = this == SLIGHT_LEFT || this == LEFT || this == SHARP_LEFT

    /** True for the right-pointing arrows. */
    val isRight: Boolean
        get() = this == SLIGHT_RIGHT || this == RIGHT || this == SHARP_RIGHT

    /**
     * The arrow that is drawn for a lane carrying several indications.
     *
     * A deterministic order is used (the sharpest turn first) so the same
     * lane always renders the same way across frames and across restarts.
     */
    companion object {
        private val CANONICAL_ORDER = listOf(
            NONE, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT
        )

        /** The canonical ranking of the arrows. */
        fun canonicalOrder(): List<LaneArrow> = CANONICAL_ORDER

        /**
         * Parses one engine lane indication.
         *
         * Returns null for an indication this vocabulary does not model, so an
         * unknown marking is dropped instead of being displayed as something
         * it is not.
         */
        fun parse(raw: String?): LaneArrow? {
            return when (raw?.trim()?.lowercase()) {
                "none" -> NONE
                "straight" -> STRAIGHT
                "left" -> LEFT
                "right" -> RIGHT
                "slight left" -> SLIGHT_LEFT
                "slight right" -> SLIGHT_RIGHT
                "sharp left" -> SHARP_LEFT
                "sharp right" -> SHARP_RIGHT
                "uturn" -> U_TURN
                else -> null
            }
        }
    }
}

/**
 * One lane of the carriageway the rider is on, with the arrow(s) it carries
 * and whether the route may use it.
 *
 * @param arrows every indication the engine reported for this lane. Empty
 *   means the lane carries no modelled marking.
 * @param recommended the engine's own verdict that the route may use this
 *   lane. It is copied verbatim — RideTogether never decides this itself.
 */
data class LaneMark(
    val arrows: Set<LaneArrow> = emptySet(),
    val recommended: Boolean = false
) {
    /** The arrow drawn for this lane (deterministic, see [LaneArrow]). */
    val primaryArrow: LaneArrow
        get() = LaneArrow.canonicalOrder().firstOrNull { it in arrows } ?: LaneArrow.NONE

    /** True when the lane carries no modelled marking at all. */
    val isUnmarked: Boolean get() = arrows.isEmpty()
}

/**
 * The lane guidance for one manoeuvre: the lanes of the carriageway the rider
 * is on, and which of them the route may use.
 *
 * ## The no-invention rule
 *
 * A [LaneGuidance] only ever exists when the routing engine supplied lane
 * data for **that** manoeuvre. RideTogether never synthesises a lane layout,
 * never counts lanes, and never highlights a lane the engine did not mark as
 * valid. When the engine has no lane data for the manoeuvre — which is the
 * normal case on roads without `turn:lanes` mapping — the guidance is
 * `null` and the UI shows nothing at all.
 */
data class LaneGuidance(
    val lanes: List<LaneMark> = emptyList()
) {
    /** True when there is nothing to draw. */
    val isEmpty: Boolean get() = lanes.isEmpty()

    /** The lanes the engine marked as usable for the route. */
    val recommendedLanes: List<LaneMark> get() = lanes.filter { it.recommended }

    /** True when at least one lane is usable for the route. */
    val hasRecommendedLane: Boolean get() = lanes.any { it.recommended }

    /** Number of lanes in the carriageway. */
    val laneCount: Int get() = lanes.size

    /**
     * A spoken/announced description, e.g. "Use the left lane".
     *
     * Returns null when the guidance carries no usable lane, so a caller can
     * never announce a lane that does not exist.
     */
    fun laneAdvice(): String? {
        if (!hasRecommendedLane) return null
        val arrows = recommendedLanes.map { it.primaryArrow }.filter { it != LaneArrow.NONE }.toSet()
        if (arrows.isEmpty()) return null
        val distinct = arrows.toList().sortedBy { LaneArrow.canonicalOrder().indexOf(it) }
        return when (distinct.size) {
            1 -> when (distinct.first()) {
                LaneArrow.LEFT -> "Use the left lane"
                LaneArrow.RIGHT -> "Use the right lane"
                LaneArrow.SLIGHT_LEFT -> "Use the left lane"
                LaneArrow.SLIGHT_RIGHT -> "Use the right lane"
                LaneArrow.SHARP_LEFT -> "Use the far left lane"
                LaneArrow.SHARP_RIGHT -> "Use the far right lane"
                LaneArrow.U_TURN -> "Use the lane for a U-turn"
                LaneArrow.STRAIGHT -> "Use the lane ahead"
                else -> null
            }
            else -> "Use the marked lane"
        }
    }

    companion object {
        /** The empty guidance — no lane data was supplied. */
        val EMPTY = LaneGuidance(emptyList())
    }
}
