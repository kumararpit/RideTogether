package com.example.data.location

/**
 * Circular heading arithmetic in the compass convention (0..360, clockwise from
 * north). Every consumer of headings must go through shortest-arc math: 359° → 1°
 * is a +2° change, never a -358° sweep.
 */
object HeadingMath {

    /** Normalize any finite angle into [0, 360). Non-finite input maps to 0. */
    fun normalize360(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var normalized = degrees % 360f
        if (normalized < 0f) normalized += 360f
        return if (normalized >= 360f) 0f else normalized
    }

    /**
     * Shortest signed angular difference from [fromDeg] to [toDeg], in (-180, 180].
     * `shortestDelta(359f, 1f) == +2f` and `shortestDelta(1f, 359f) == -2f`.
     */
    fun shortestDelta(fromDeg: Float, toDeg: Float): Float {
        if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0f
        var delta = (toDeg - fromDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f
        return delta
    }

    /** Wrap an angle into (-180, 180]. */
    fun wrap180(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var wrapped = degrees % 360f
        if (wrapped > 180f) wrapped -= 360f
        if (wrapped <= -180f) wrapped += 360f
        return wrapped
    }
}
