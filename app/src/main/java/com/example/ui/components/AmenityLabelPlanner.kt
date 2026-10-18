package com.example.ui.components

import com.example.data.amenities.AmenityType

/**
 * Deterministic screen-space label placement for amenity POIs (Task 18, Part B).
 *
 * The planner is pure: it consumes already-projected screen positions (produced by
 * the existing map projection in RideMapCanvas) and decides *which* amenities get a
 * text label this frame. Geographic coordinates are never modified.
 *
 * Visibility policy (display zoom is the continuous camera zoom from MapCameraController):
 *  - zoom < 12.2              → no labels at all (icons only)
 *  - zoom >= 12.2             → labels for the selected amenity (if it has a name)
 *  - zoom >= 14.2             → labels for rider-critical categories (FUEL, REPAIR)
 *  - zoom >= 15.6             → labels for all categories
 *  - hysteresis of 0.25 zoom: labels that were visible on the previous frame stay
 *    visible until zoom falls 0.25 below their threshold, preventing flicker.
 *
 * Collision: each label reserves a fixed screen-space box around its anchor; labels
 * are admitted in priority order and later candidates overlapping an already-reserved
 * box are suppressed (their icons remain visible). Priority: selected first, then
 * category importance rank, then the stable list order.
 */
object AmenityLabelPlanner {

    const val SHOW_ALL_ZOOM = 15.6f
    const val IMPORTANT_CATEGORY_ZOOM = 14.2f
    const val SELECTED_ONLY_ZOOM = 12.2f

    /** Zoom hysteresis: previously visible labels linger 0.25 zoom below their threshold. */
    const val HYSTERESIS_ZOOM = 0.25f

    /** Hard cap on labels drawn per frame to keep the overlay cheap. */
    const val MAX_LABELS = 16

    /** Category importance; lower = higher priority. Importance is category-specific. */
    fun categoryRank(type: AmenityType): Int = when (type) {
        AmenityType.FUEL -> 0
        AmenityType.REPAIR -> 1
        AmenityType.REST_STOP -> 2
        AmenityType.CAFE -> 3
        AmenityType.RESTAURANT -> 4
        AmenityType.CONVENIENCE_STORE -> 5
        AmenityType.HOTEL -> 6
        AmenityType.TOILET -> 7
        AmenityType.SCENIC_VIEWPOINT -> 8
        AmenityType.UNKNOWN -> 9
    }

    /** Zoom threshold at which this amenity may first show its label. */
    fun showThreshold(isSelected: Boolean, type: AmenityType): Float {
        if (isSelected) return SELECTED_ONLY_ZOOM
        return when (type) {
            AmenityType.FUEL, AmenityType.REPAIR -> IMPORTANT_CATEGORY_ZOOM
            else -> SHOW_ALL_ZOOM
        }
    }

    data class Candidate(
        val id: Long,
        /** Projected screen anchor (pin tip). */
        val xPx: Float,
        val yPx: Float,
        val isSelected: Boolean,
        val type: AmenityType
    )

    /**
     * Returns the IDs whose labels should be drawn this frame, in draw order.
     * [previouslyVisible] provides one-frame hysteresis against zoom flicker.
     * [reserveHalfWidthPx]/[reserveHalfHeightPx] define the deterministic collision
     * box (the rendered pill never draws beyond this budget).
     */
    fun plan(
        candidates: List<Candidate>,
        zoom: Float,
        previouslyVisible: Set<Long>,
        reserveHalfWidthPx: Float,
        reserveHalfHeightPx: Float,
        maxLabels: Int = MAX_LABELS
    ): List<Long> {
        if (candidates.isEmpty() || zoom < SELECTED_ONLY_ZOOM - HYSTERESIS_ZOOM) return emptyList()

        // Deterministic ordering: selected first, then category importance, then stable list order.
        val ordered = candidates
            .mapIndexed { index, candidate -> index to candidate }
            .sortedWith(
                compareBy(
                    { !it.second.isSelected },
                    {
                        when {
                            it.second.isSelected -> -1
                            else -> categoryRank(it.second.type)
                        }
                    },
                    { it.first }
                )
            )
            .map { it.second }

        val reservedTopYs = ArrayList<Float>(maxLabels)
        val reservedBottomYs = ArrayList<Float>(maxLabels)
        val reservedLeftXs = ArrayList<Float>(maxLabels)
        val reservedRightXs = ArrayList<Float>(maxLabels)

        val result = ArrayList<Long>(maxLabels)

        for (candidate in ordered) {
            if (result.size >= maxLabels) break

            val wasVisible = previouslyVisible.contains(candidate.id)
            val threshold = showThreshold(candidate.isSelected, candidate.type)
            val effectiveThreshold = if (wasVisible) threshold - HYSTERESIS_ZOOM else threshold
            val zoomOk = zoom >= effectiveThreshold
            if (!zoomOk) continue

            // Deterministic fixed-budget collision box centered on the anchor.
            val left = candidate.xPx - reserveHalfWidthPx
            val right = candidate.xPx + reserveHalfWidthPx
            val top = candidate.yPx
            val bottom = candidate.yPx + reserveHalfHeightPx * 2f

            var overlaps = false
            for (i in reservedTopYs.indices) {
                if (left < reservedRightXs[i] && right > reservedLeftXs[i] &&
                    top < reservedBottomYs[i] && bottom > reservedTopYs[i]
                ) {
                    overlaps = true
                    break
                }
            }
            if (overlaps) continue

            reservedTopYs.add(top)
            reservedBottomYs.add(bottom)
            reservedLeftXs.add(left)
            reservedRightXs.add(right)
            result.add(candidate.id)
        }

        return result
    }
}
