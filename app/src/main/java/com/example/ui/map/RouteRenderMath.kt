package com.example.ui.map

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Pure math for the route canvas renderer (Phase B, audit §6 P2).
 *
 * All geometry is projected ONCE per route into a fixed world space — Web-Mercator
 * **tile coordinates at zoom 17** — so the per-frame work in `RideMapCanvas` is a
 * single affine transform (scale + translate) plus three screen-space stroke passes
 * over a reusable [androidx.compose.ui.graphics.Path], never a per-point projection
 * of the route list.
 *
 * Simplification (Douglas–Peucker) is applied per **tier** = integer zoom bucket,
 * with a screen-space tolerance converted into tile-17 units, so the drawn geometry
 * stays stable while the user zooms and only re-simplifies when the tier changes.
 * Endpoints are always preserved, and **multi-leg boundaries are pinned**: a
 * simplification segment never spans a leg boundary, so the renderer can never
 * introduce a straight chord across a waypoint.
 */
object RouteRenderMath {

    /** Fixed world space: tile-coordinate units at this zoom. */
    const val WORLD_ZOOM = 17

    /** Supported render tiers (integer zoom buckets). */
    const val MIN_TIER = 8
    const val MAX_TIER = 18

    /** Screen-space tolerance (px) for vertex decimation at 1:1 tier scale. */
    const val SIMPLIFY_TOLERANCE_PX = 1.2f

    // Route band widths (dp).
    //
    // Widths are a smooth, monotonically *decreasing* function of zoom: the band
    // is proportionally wide at panorama zooms, where it has to survive map
    // clutter, and thin at street zooms, where the road itself must stay
    // legible beneath it.
    //
    // The previous implementation culled the whole route band outside a
    // [13..16] zoom window, which made the route disappear as soon as the rider
    // zoomed in past 16 — and the follow camera sits at 16.5–17.5, so the route
    // was effectively invisible for the entire ride. Widths are now clamped at
    // both ends and the band is always drawn.
    /** Core width (dp) at panorama zooms. */
    const val CORE_WIDTH_FAR_DP = 13.0f

    /** Core width (dp) at street-navigation zooms. */
    const val CORE_WIDTH_NEAR_DP = 8.0f

    /** Dark outline added on each side of the core. */
    const val CASING_MARGIN_DP = 1.5f

    /** Pale centre streak width (dp). */
    const val STREAK_WIDTH_DP = 1.5f

    /** Soft drop shadow added outside the casing (dp). */
    const val SHADOW_MARGIN_DP = 1.0f

    /**
     * (zoom, coreWidthDp) anchors, ascending. Linearly interpolated between
     * anchors and clamped outside the range, so the line never vanishes and
     * never grows without bound.
     */
    private val CORE_WIDTH_ANCHORS = listOf(
        8f to 14.0f,
        11f to CORE_WIDTH_FAR_DP,
        14f to 10.5f,
        16f to 9.0f,
        18f to CORE_WIDTH_NEAR_DP
    )

    /**
     * Projects a geographic polyline into tile-17 world coordinates.
     *
     * The vertices are stored as **Double**: tile-17 world coordinates are
     * ~7·10⁶, where a Float's 24-bit mantissa leaves a resolution of ~0.5 world
     * units — and one world unit is ~600 screen px at street zoom, so a Float
     * representation would make the route visibly jitter. (Proven by
     * `RouteRenderMathTest`.)
     */
    fun toWorldVertices(route: List<LatLng>): DoubleArray {
        val out = DoubleArray(route.size * 2)
        for (i in route.indices) {
            out[i * 2] = TileMath.lonToTileX(route[i].longitude, WORLD_ZOOM.toFloat())
            out[i * 2 + 1] = TileMath.latToTileY(route[i].latitude, WORLD_ZOOM.toFloat())
        }
        return out
    }

    /**
     * Douglas–Peucker in world space with epsilon expressed in tile-17 units.
     *
     * [pinnedIndices] are vertex indices that must survive simplification (multi-leg
     * boundaries). The polyline is split at every pinned index and each piece is
     * simplified independently, so no output segment ever spans a leg boundary.
     * The first and last vertices are always kept.
     *
     * @return the indices of kept vertices, ascending.
     */
    fun simplifyIndices(
        vertices: DoubleArray,
        epsilonWorldUnits: Double,
        pinnedIndices: IntArray = IntArray(0)
    ): IntArray {
        val n = vertices.size / 2
        if (n <= 2) return IntArray(n) { it }
        if (epsilonWorldUnits <= 0f && pinnedIndices.isEmpty()) return IntArray(n) { it }

        val boundaries = sortedSetOf(0, n - 1)
        for (p in pinnedIndices) if (p in 1 until n - 1) boundaries.add(p)
        val boundaryList = boundaries.toList()

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        for (b in boundaryList) keep[b] = true

        for (i in 0 until boundaryList.size - 1) {
            val start = boundaryList[i]
            val end = boundaryList[i + 1]
            if (end - start < 2) continue
            simplifySegment(vertices, start, end, epsilonWorldUnits, keep)
        }

        val count = keep.count { it }
        val out = IntArray(count)
        var w = 0
        for (i in keep.indices) if (keep[i]) out[w++] = i
        return out
    }

    private fun simplifySegment(
        vertices: DoubleArray,
        start: Int,
        end: Int,
        epsilonWorldUnits: Double,
        keep: BooleanArray
    ) {
        if (epsilonWorldUnits <= 0f) {
            for (i in start + 1 until end) keep[i] = true
            return
        }
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(start, end))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            var maxDist = 0.0
            var maxIdx = -1
            val ax = vertices[s * 2]
            val ay = vertices[s * 2 + 1]
            val bx = vertices[e * 2]
            val by = vertices[e * 2 + 1]
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            for (i in s + 1 until e) {
                val px = vertices[i * 2]
                val py = vertices[i * 2 + 1]
                val dist = if (lenSq == 0.0) {
                    hypot(px - ax, py - ay)
                } else {
                    val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
                    val clamped = t.coerceIn(0.0, 1.0)
                    hypot(px - (ax + clamped * dx), py - (ay + clamped * dy))
                }
                if (dist > maxDist) {
                    maxDist = dist
                    maxIdx = i
                }
            }
            if (maxIdx > 0 && maxDist > epsilonWorldUnits) {
                keep[maxIdx] = true
                stack.addLast(intArrayOf(s, maxIdx))
                stack.addLast(intArrayOf(maxIdx, e))
            }
        }
    }

    /**
     * Screen tolerance (px) → tile-17 epsilon for the given [tier].
     *
     * One tile at zoom `tier` is [tileSizePx] wide on screen and covers
     * `2^(WORLD_ZOOM - tier)` tile-17 units, hence
     * `screenPxPerWorldUnit = tileSizePx / 2^(WORLD_ZOOM - tier)`.
     */
    fun epsilonForTier(
        tier: Int,
        tileSizePx: Float,
        tolerancePx: Float = SIMPLIFY_TOLERANCE_PX
    ): Double {
        val screenPxPerUnit = tileSizePx / 2.0.pow((WORLD_ZOOM - tier).toDouble())
        return if (screenPxPerUnit > 0) tolerancePx / screenPxPerUnit else 0.0
    }

    /** Zoom bucket for render-tier selection, clamped to the supported range. */
    fun tierForZoom(displayZoom: Float): Int = displayZoom.toInt().coerceIn(MIN_TIER, MAX_TIER)

    /**
     * Affine draw parameters converting tile-17 world units to **unrotated** screen px
     * at [displayZoom]. Mirrors the canvas projection exactly:
     * `x = anchorX + (tileX - centerTileX) * tileSizePx`.
     */
    /**
     * Affine draw parameters converting tile-17 world units to **unrotated** screen px
     * at [displayZoom]. Mirrors the canvas projection exactly:
     * `x = anchorX + (tileX(point) - tileX(center)) * tileSizePx`.
     *
     * The centre is given **geographically** (lat/lng) so the world-space centre
     * is derived here — passing tile indices at the display zoom and re-projecting
     * them was a bug source (tile index ≠ longitude).
     */
    fun worldToScreenTransform(
        displayZoom: Float,
        centerLat: Double,
        centerLng: Double,
        anchorX: Float,
        anchorY: Float,
        tileSizePx: Float,
        tiltCos: Float = 1f
    ): Transform {
        val s = 2.0.pow((displayZoom - WORLD_ZOOM).toDouble())
        return Transform(
            centerWorldX = TileMath.lonToTileX(centerLng, WORLD_ZOOM.toFloat()),
            centerWorldY = TileMath.latToTileY(centerLat, WORLD_ZOOM.toFloat()),
            scaleX = s * tileSizePx,
            scaleY = s * tileSizePx * tiltCos,
            anchorX = anchorX,
            anchorY = anchorY
        )
    }

    /**
     * The world→screen difference is computed in **Double** and only the final
     * result is narrowed to Float: tile-17 world coordinates are ~7·10⁶, so
     * multiplying them by the scale (~600) *before* subtracting the centre would
     * reach ~4·10⁹ — beyond Float's 24-bit mantissa, which cost hundreds of
     * pixels of positional error (caught by `RouteRenderMathTest`).
     */
    data class Transform(
        val centerWorldX: Double,
        val centerWorldY: Double,
        val scaleX: Double,
        val scaleY: Double,
        val anchorX: Float,
        val anchorY: Float
    ) {
        fun mapX(worldX: Double): Float =
            ((worldX - centerWorldX) * scaleX + anchorX).toFloat()

        fun mapY(worldY: Double): Float =
            ((worldY - centerWorldY) * scaleY + anchorY).toFloat()
    }

    /**
     * Interpolates the width anchors at [zoom], clamped at both ends so the value
     * is defined for every zoom the map can reach (3..18).
     */
    private fun interpolateAnchors(anchors: List<Pair<Float, Float>>, zoom: Float): Float {
        if (!zoom.isFinite()) return anchors.first().second
        if (zoom <= anchors.first().first) return anchors.first().second
        if (zoom >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (z0, v0) = anchors[i]
            val (z1, v1) = anchors[i + 1]
            if (zoom <= z1) {
                val t = if (z1 - z0 <= 0f) 0f else (zoom - z0) / (z1 - z0)
                return v0 + t * (v1 - v0)
            }
        }
        return anchors.last().second
    }

    /**
     * Core stroke width (dp) as a function of zoom: ≈13 dp at panorama zooms,
     * ≈8 dp at street-navigation zoom, interpolated and clamped at both ends.
     */
    fun coreWidthDp(displayZoom: Float): Float =
        interpolateAnchors(CORE_WIDTH_ANCHORS, displayZoom)

    /** Casing band width (dp): the core plus a fixed margin on each side. */
    fun casingWidthDp(displayZoom: Float): Float = coreWidthDp(displayZoom) + 2f * CASING_MARGIN_DP

    /** Light accent streak width (dp) drawn down the centre of the core. */
    fun streakWidthDp(displayZoom: Float): Float = STREAK_WIDTH_DP

    /** Soft outer shadow width (dp): the casing plus a fixed margin on each side. */
    fun shadowWidthDp(displayZoom: Float): Float = casingWidthDp(displayZoom) + 2f * SHADOW_MARGIN_DP

    /**
     * Spacing (in screen px) between direction dots along the route, so the dot
     * cadence is zoom-independent in *screen* terms instead of vertex-count terms.
     */
    fun directionDotSpacingPx(displayZoom: Float): Float = (110f * 2.0.pow((16f - displayZoom).toDouble()).toFloat())
            .coerceIn(56f, 320f)
}
