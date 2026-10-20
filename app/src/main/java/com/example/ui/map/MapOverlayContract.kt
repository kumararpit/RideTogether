package com.example.ui.map

/**
 * Explicit map overlay contract (Phase B, backlog B-17).
 *
 * The map is composed of overlays that must draw in a **fixed, declared order**
 * — otherwise a new sheet or banner can accidentally render under the map, or
 * the rider can be painted over a label that belongs on top of it. The reference
 * product keeps an equivalent registry (25 named overlay layers, including
 * `TRAFFIC_INCIDENTS`, `FRIEND`, `FRIEND_CLUSTER`, `FRIEND_ACCURACY_CIRCLE`,
 * `PLACEMARK`, `PLACEMARK_LABELS`, `CALLOUT_LABEL_OVER_PLACEMARKS`); this is
 * the RideTogether-original equivalent — same intent, own names and order.
 */
enum class MapOverlayLayer {
    /** Raster base map. */
    TILES,

    /** The route line (and its start/destination markers). */
    ROUTE,

    /** The rider/group markers. */
    RIDERS,

    /** Amenity pins. */
    AMENITIES,

    /** Amenity name labels. */
    AMENITY_LABELS,

    /** Map-attached labels (waypoint captions). */
    WAYPOINT_LABELS,

    /** The top maneuver banner. */
    MANEUVER_BANNER,

    /** The bottom trip HUD. */
    TRIP_HUD,

    /** Floating map controls (compass, recenter, zoom, style). */
    MAP_CONTROLS,

    /** Modal surfaces (sheets, dialogs). */
    SHEETS
}

object MapOverlayContract {

    /** The declared draw order, bottom-most first. */
    val drawOrder: List<MapOverlayLayer> = listOf(
        MapOverlayLayer.TILES,
        MapOverlayLayer.ROUTE,
        MapOverlayLayer.RIDERS,
        MapOverlayLayer.AMENITIES,
        MapOverlayLayer.AMENITY_LABELS,
        MapOverlayLayer.WAYPOINT_LABELS,
        MapOverlayLayer.MANEUVER_BANNER,
        MapOverlayLayer.TRIP_HUD,
        MapOverlayLayer.MAP_CONTROLS,
        MapOverlayLayer.SHEETS
    )

    private val rank: Map<MapOverlayLayer, Int> =
        drawOrder.withIndex().associate { (index, layer) -> layer to index }

    /** True when [upper] is drawn above [lower]. */
    fun isAbove(upper: MapOverlayLayer, lower: MapOverlayLayer): Boolean =
        (rank[upper] ?: -1) > (rank[lower] ?: -1)

    /** The zero-based draw position of [layer]. */
    fun indexOf(layer: MapOverlayLayer): Int = rank[layer] ?: -1

    /**
     * True for the layers that are **map chrome** — the surfaces the map
     * content must keep clear of (the maneuver banner, the trip HUD and the
     * floating controls).
     */
    fun isChrome(layer: MapOverlayLayer): Boolean = when (layer) {
        MapOverlayLayer.MANEUVER_BANNER,
        MapOverlayLayer.TRIP_HUD,
        MapOverlayLayer.MAP_CONTROLS -> true
        else -> false
    }
}

/**
 * A rectangle of screen space occupied by map chrome (the maneuver banner, the
 * trip HUD, the control column).
 *
 * The chrome is *published* to the map so that map-attached content (labels)
 * can keep out of it — the reference does the same thing natively, passing the
 * banner height and the bottom-sheet exposure into the renderer every frame
 * (`turnCardBodyHeightPx`, `bottomSheetExposurePx`,
 * `isNavigationOverlayOccludingMap`).
 */
data class MapChromeInsets(
    val topPx: Float = 0f,
    val bottomPx: Float = 0f,
    val leftPx: Float = 0f,
    val rightPx: Float = 0f
) {
    /**
     * True when a label box at [x, y, width, height] would sit under the chrome.
     * [viewportWidth]/[viewportHeight] describe the map surface the chrome is
     * measured against.
     *
     * The top chrome occupies `y ∈ [0, topPx]`, the bottom chrome
     * `y ∈ [viewportHeight − bottomPx, viewportHeight]`, the left chrome
     * `x ∈ [0, leftPx]` and the right chrome
     * `x ∈ [viewportWidth − rightPx, viewportWidth]`.
     */
    fun overlaps(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): Boolean {
        val right = x + width
        val bottom = y + height

        val overlapsTop = y < topPx && bottom > 0f
        val overlapsBottom = bottom > viewportHeight - bottomPx && y < viewportHeight
        val overlapsLeft = x < leftPx && right > 0f
        val overlapsRight = right > viewportWidth - rightPx && x < viewportWidth

        return overlapsTop || overlapsBottom || overlapsLeft || overlapsRight
    }

    companion object {
        val None = MapChromeInsets()
    }
}
