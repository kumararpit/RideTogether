package com.example.ui.map

/**
 * The selection model of the amenity layer.
 *
 * The reference implementation selects a place on the map by tapping its
 * marker, which opens a place sheet; the sheet's primary action then turns
 * the place into a **stop of the route** ("Add stop"). A tapped *cluster*
 * expands into the places it contains.
 *
 * This is the RideTogether-original equivalent of that interaction, expressed
 * as a pure decision function so it can be verified without a device:
 *
 *  - a tap resolves to the **topmost** marker under it (the one the rider
 *    actually sees), not to the first one in the data list;
 *  - a cluster resolves to an **expansion**, not to a selection;
 *  - when something is selected, the other markers are **dimmed** so the
 *    selection reads at a glance.
 */
object AmenitySelection {

    /**
     * A marker the rider can see and tap.
     *
     * @param id the amenity id, or the cluster's representative id.
     * @param x screen x of the marker's head.
     * @param y screen y of the marker's head.
     * @param isCluster true when the marker stands for several amenities.
     * @param memberIds the amenities the marker stands for.
     * @param drawIndex the position in the draw order; a **larger** index is
     *   drawn later and therefore on top.
     */
    data class Marker(
        val id: Long,
        val x: Float,
        val y: Float,
        val isCluster: Boolean = false,
        val memberIds: List<Long> = emptyList(),
        val drawIndex: Int = 0
    )

    /** What a tap on the amenity layer did. */
    sealed interface TapOutcome {
        /** An individual amenity was picked. */
        data class Picked(val amenityId: Long) : TapOutcome

        /** A cluster was picked: its members are expanded into single markers. */
        data class Expanded(val memberIds: List<Long>) : TapOutcome

        /** Nothing was under the tap. */
        data object Nothing : TapOutcome
    }

    /**
     * Resolves a tap to the amenity layer.
     *
     * The markers are tested from the **top of the draw order downwards**, so
     * the marker the rider sees on top is the one that answers the tap. A
     * cluster answers with an expansion; a single marker answers with a
     * selection.
     *
     * @param tapX the tap position in screen pixels.
     * @param tapY the tap position in screen pixels.
     * @param markers the visible markers in draw order (bottom first).
     * @param radiusPx the touch target radius.
     */
    fun resolveTap(
        tapX: Float,
        tapY: Float,
        markers: List<Marker>,
        radiusPx: Float
    ): TapOutcome {
        val ordered = markers.sortedByDescending { it.drawIndex }
        for (marker in ordered) {
            val dx = tapX - marker.x
            val dy = tapY - marker.y
            if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                return if (marker.isCluster) {
                    TapOutcome.Expanded(marker.memberIds)
                } else {
                    TapOutcome.Picked(marker.id)
                }
            }
        }
        return TapOutcome.Nothing
    }

    /**
     * The opacity of a marker.
     *
     * When nothing is selected every marker is fully opaque. When something
     * is selected the selected marker stays fully opaque and the others are
     * dimmed — the selection has to read at a glance, and the rider must be
     * able to see what else is around it.
     */
    fun markerAlpha(isSelected: Boolean, hasSelection: Boolean): Float = when {
        !hasSelection -> 1f
        isSelected -> 1f
        else -> 0.4f
    }
}
