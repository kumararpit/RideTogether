package com.example.ui.map

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow

/**
 * Web-Mercator scale-bar math (audit §6 P4). Pure functions; replaces the
 * former static "500 m" bar whose width/caption no longer depended on zoom.
 *
 * Convention: one tile at zoom z spans `256` dp on screen, so the real-world
 * size of that tile at the given latitude is derived from the standard slippy
 * map formula committed to the same projection used by [com.example.data.map.TileMath].
 */
object ScaleBarMath {

    /**
     * Real-world meters covered by one **logical dp** of the map at [latDeg]/[zoom].
     *
     * The standard slippy-map formula gives meters per *tile pixel*; RideTogether
     * draws each 256-px tile across 256 **dp**, so one dp covers exactly the
     * geographic extent of one tile pixel ⇒ meters per dp = 156543.03392·cos(lat)/2^z.
     */
    fun metersPerPixel(latDeg: Double, zoom: Float): Double {
        val clampedLat = latDeg.coerceIn(-85.05112878, 85.05112878)
        // Meters per tile pixel (a 256-px tile spans 256 of these).
        return 156_543.03392 * cos(Math.toRadians(clampedLat)) / 2.0.pow(zoom.toDouble())
    }

    /** Meters per screen pixel (density-aware) for a canvas that draws real px. */
    fun metersPerScreenPixel(latDeg: Double, zoom: Float, pxPerDp: Float): Double =
        metersPerPixel(latDeg, zoom) / pxPerDp.toDouble()

    /**
     * Snapping set for the bar caption (meters). Powers-of-two-friendly values
     * users can divide mentally; spans 10 m … 5,000 m so a segment that fits the
     * bar budget exists across the whole supported zoom range (3 … 18).
     */
    val SEGMENT_METERS = intArrayOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000)

    /**
     * Chooses the caption segment and its on-screen pixel width so the bar fits
     * the [barWidthDp] budget. The LARGEST segment that fits is chosen (the
     * longest truthful bar). If even the smallest segment does not fit, the
     * smallest is returned — the bar is then drawn at its true width, never
     * truncated, because a truncated bar would misstate the scale.
     */
    fun chooseSegment(
        metersPerScreenPx: Double,
        barWidthDp: Float,
        maxOverRatio: Float = 1.0f
    ): Segment {
        require(barWidthDp > 0f)
        var best: Segment? = null
        for (meters in SEGMENT_METERS) {
            val widthPx = (meters / metersPerScreenPx).toFloat()
            if (widthPx <= barWidthDp * maxOverRatio) {
                best = Segment(meters, widthPx)
            } else break
        }
        val smallest = SEGMENT_METERS.first()
        return best ?: Segment(smallest, (smallest / metersPerScreenPx).toFloat())
    }

    /** Caption for a segment, using survey-standard abbreviations. */
    fun caption(segmentMeters: Int): String =
        if (segmentMeters >= 1_000) "${segmentMeters / 1_000} km" else "$segmentMeters m"

    data class Segment(val meters: Int, val widthPx: Float)
}
