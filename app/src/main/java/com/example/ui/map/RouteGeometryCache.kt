package com.example.ui.map

import com.example.data.model.LatLng

/**
 * Render-side cache for the route geometry (audit §6 P2).
 *
 * Holds the **world-space** projection of the route (tile-17 units) and the
 * per-tier simplified vertex index set. Both are recomputed only when the
 * route, the zoom tier, or the leg-boundary set changes — never per frame.
 *
 * The cache is intentionally mutable and confined to the map canvas: it is a
 * pure memoization of deterministic math (see [RouteRenderMath]).
 */
class RouteGeometryCache {

    private var cachedRoute: List<LatLng>? = null
    private var cachedWorld: DoubleArray = DoubleArray(0)
    private var cachedTier: Int = -1
    private var cachedPinned: List<Int> = emptyList()
    private var cachedIndices: IntArray = IntArray(0)

    /** World-space vertices (x, y pairs) of the cached route. */
    val worldVertices: DoubleArray get() = cachedWorld

    /** Indices (into the original route) of the vertices kept at the cached tier. */
    val keptIndices: IntArray get() = cachedIndices

    /**
     * Ensures the cache matches the requested route/tier/boundaries.
     *
     * @param route the full route polyline (any leg structure)
     * @param tier integer zoom bucket driving the simplification tolerance
     * @param tileSizePx on-screen size of one 256-dp tile at the current zoom
     * @param legStartIndices vertex indices where a new route leg begins; these
     *        are pinned so simplification never draws a chord across a leg
     *        boundary
     */
    fun ensure(
        route: List<LatLng>,
        tier: Int,
        tileSizePx: Float,
        legStartIndices: List<Int>
    ) {
        val routeChanged = cachedRoute !== route
        val tierChanged = cachedTier != tier
        val pinnedChanged = cachedPinned != legStartIndices

        if (routeChanged) {
            cachedRoute = route
            cachedWorld = RouteRenderMath.toWorldVertices(route)
        }
        if (routeChanged || tierChanged || pinnedChanged) {
            cachedTier = tier
            cachedPinned = legStartIndices
            val epsilon = RouteRenderMath.epsilonForTier(tier, tileSizePx)
            val pinned = legStartIndices
                .filter { it in 0 until (cachedWorld.size / 2) }
                .toIntArray()
            cachedIndices = RouteRenderMath.simplifyIndices(cachedWorld, epsilon, pinned)
        }
    }
}
