package com.example

import com.example.ui.map.RouteGeometryCache
import com.example.ui.map.RouteRenderMath
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route geometry cache semantics (Phase B, audit §6 P2): the cache may only be
 * recomputed when the route, the zoom tier, or the leg-boundary set changes —
 * never per frame.
 */
class RouteGeometryCacheTest {

    private fun route(n: Int, seed: Int): List<LatLng> = (0 until n).map {
        LatLng(18.5 + it * 0.0001, 73.8 + it * 0.0001 + seed * 0.00001)
    }

    /**
     * A CURVED route: a perfectly straight polyline would collapse to two
     * vertices at every tolerance, which could not distinguish the tiers.
     */
    private fun curvedRoute(n: Int, seed: Int): List<LatLng> = (0 until n).map {
        val t = it.toDouble() / (n - 1)
        LatLng(
            18.5 + t * 0.02 + 0.0004 * kotlin.math.sin(t * 12.0) * (1 + seed * 0.1),
            73.8 + t * 0.02 + 0.0004 * kotlin.math.cos(t * 9.0) * (1 + seed * 0.1)
        )
    }

    @Test
    fun `cache reuses geometry while route tier and boundaries are unchanged`() {
        val cache = RouteGeometryCache()
        val r = route(50, seed = 1)
        cache.ensure(r, tier = 16, tileSizePx = 768f, legStartIndices = listOf(25))
        val firstWorld = cache.worldVertices
        val firstIndices = cache.keptIndices

        // Same inputs → identical (not recomputed) results.
        cache.ensure(r, tier = 16, tileSizePx = 768f, legStartIndices = listOf(25))
        assertSame("world vertices must be reused", firstWorld, cache.worldVertices)
        assertSame("kept indices must be reused", firstIndices, cache.keptIndices)
    }

    @Test
    fun `changing the tier recomputes the simplified index set`() {
        val cache = RouteGeometryCache()
        val r = curvedRoute(400, seed = 2)
        cache.ensure(r, tier = 12, tileSizePx = 768f, legStartIndices = emptyList())
        val coarse = cache.keptIndices.size

        cache.ensure(r, tier = 17, tileSizePx = 768f, legStartIndices = emptyList())
        val fine = cache.keptIndices.size

        assertTrue(
            "a finer tier must keep more vertices (coarse=$coarse, fine=$fine)",
            fine > coarse
        )
    }

    @Test
    fun `changing the route replaces the cached world geometry`() {
        val cache = RouteGeometryCache()
        cache.ensure(route(50, seed = 1), tier = 16, tileSizePx = 768f, legStartIndices = emptyList())
        val first = cache.worldVertices

        cache.ensure(route(80, seed = 3), tier = 16, tileSizePx = 768f, legStartIndices = emptyList())
        assertNotEquals("a new route must replace the world geometry", first, cache.worldVertices)
        assertEquals(80 * 2, cache.worldVertices.size)
    }

    @Test
    fun `pinned leg boundaries survive the cached simplification`() {
        val cache = RouteGeometryCache()
        val r = route(300, seed = 4)
        val pinned = listOf(100, 200)
        cache.ensure(r, tier = 14, tileSizePx = 768f, legStartIndices = pinned)

        val kept = cache.keptIndices.toSet()
        for (p in pinned) {
            assertTrue("leg boundary $p must be kept", kept.contains(p))
        }
    }

    @Test
    fun `empty route yields empty geometry without throwing`() {
        val cache = RouteGeometryCache()
        cache.ensure(emptyList(), tier = 16, tileSizePx = 768f, legStartIndices = emptyList())
        assertEquals(0, cache.worldVertices.size)
        assertEquals(0, cache.keptIndices.size)
    }

    @Test
    fun `out of range pinned indices are ignored`() {
        val cache = RouteGeometryCache()
        val r = route(20, seed = 5)
        cache.ensure(r, tier = 16, tileSizePx = 768f, legStartIndices = listOf(999, -3))
        assertTrue("no crash and sane output", cache.keptIndices.isNotEmpty())
        assertEquals(0, cache.keptIndices.first())
    }

    @Test
    fun `kept indices reference real route vertices`() {
        val cache = RouteGeometryCache()
        val r = route(120, seed = 6)
        cache.ensure(r, tier = 15, tileSizePx = 768f, legStartIndices = listOf(60))
        for (idx in cache.keptIndices) {
            assertTrue("index $idx out of range", idx in r.indices)
        }
        assertTrue(
            "kept indices must be strictly ascending",
            cache.keptIndices.toList().windowed(2).all { (a, b) -> a < b }
        )
        assertEquals(0, cache.keptIndices.first())
        assertEquals(r.size - 1, cache.keptIndices.last())
    }

    @Test
    fun `world zoom is the fixed projection basis`() {
        assertEquals(17, RouteRenderMath.WORLD_ZOOM)
    }
}
