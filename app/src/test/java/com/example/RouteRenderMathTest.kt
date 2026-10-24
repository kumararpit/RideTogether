package com.example

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.ui.map.RouteRenderMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Route render math (Phase B, audit §6 P2).
 *
 * Guards the two properties that must never regress:
 *  1. simplification never drops the route endpoints or a pinned leg boundary;
 *  2. the cached world→screen transform agrees with the canvas projection
 *     (`x = anchorX + (tileX(point) - tileX(center)) * tileSizePx`), so route
 *     geometry, markers, and hit-testing share one coordinate space.
 */
class RouteRenderMathTest {

    /** A polyline with a distinctive corner at each leg boundary. */
    private fun syntheticLegRoute(verticesPerLeg: Int = 40, legCount: Int = 3): List<LatLng> {
        val out = mutableListOf<LatLng>()
        for (leg in 0 until legCount) {
            val baseLat = 18.5 + leg * 0.05
            val baseLng = 73.8 + leg * 0.05
            for (i in 0 until verticesPerLeg) {
                val t = i.toDouble() / (verticesPerLeg - 1)
                // Straight run, then a sharp turn at the end of the leg.
                val lat = if (t < 0.85) baseLat + t * 0.01 else baseLat + 0.0085 + (t - 0.85) * 0.2
                val lng = baseLng + t * 0.01
                out.add(LatLng(lat, lng))
            }
        }
        return out
    }

    @Test
    fun `simplification always keeps first and last vertex`() {
        val route = syntheticLegRoute()
        val world = RouteRenderMath.toWorldVertices(route)
        val kept = RouteRenderMath.simplifyIndices(world, epsilonWorldUnits = 1e-4)

        assertEquals(0, kept.first())
        assertEquals(route.size - 1, kept.last())
        for (i in 1 until kept.size) {
            assertTrue("kept indices must be strictly ascending", kept[i - 1] < kept[i])
        }
    }

    @Test
    fun `simplification keeps pinned leg boundaries and never spans them`() {
        val verticesPerLeg = 40
        val legCount = 3
        val route = syntheticLegRoute(verticesPerLeg, legCount)
        val world = RouteRenderMath.toWorldVertices(route)

        // Leg boundaries sit at the first vertex of every leg after the first.
        val legStarts = (1 until legCount).map { it * verticesPerLeg }
        val pinned = legStarts.toIntArray()

        val kept = RouteRenderMath.simplifyIndices(world, epsilonWorldUnits = 2e-4, pinnedIndices = pinned)
        val keptSet = kept.toSet()

        for (boundary in legStarts) {
            assertTrue("leg boundary $boundary must survive simplification", keptSet.contains(boundary))
        }

        // No retained segment may span a leg boundary: because every boundary is
        // itself a retained vertex, no CONSECUTIVE kept pair may contain a
        // boundary strictly inside it.
        for (i in 1 until kept.size) {
            val a = kept[i - 1]
            val b = kept[i]
            for (boundary in legStarts) {
                assertTrue(
                    "retained segment $a→$b must not straddle leg boundary $boundary",
                    !(a < boundary && boundary < b)
                )
            }
        }
    }

    @Test
    fun `simplification reduces vertices on a smooth curve`() {
        val route = syntheticLegRoute(verticesPerLeg = 120, legCount = 1)
        val world = RouteRenderMath.toWorldVertices(route)
        val kept = RouteRenderMath.simplifyIndices(world, epsilonWorldUnits = 5e-5)

        assertTrue(
            "expected decimation on a near-straight run (kept=${kept.size}, total=${route.size})",
            kept.size < route.size
        )
        assertEquals(0, kept.first())
        assertEquals(route.size - 1, kept.last())
    }

    @Test
    fun `zero tolerance keeps every vertex`() {
        val route = syntheticLegRoute(verticesPerLeg = 12, legCount = 2)
        val world = RouteRenderMath.toWorldVertices(route)
        val kept = RouteRenderMath.simplifyIndices(world, epsilonWorldUnits = 0.0)
        assertEquals(route.size, kept.size)
    }

    @Test
    fun `world to screen transform matches the canvas projection exactly`() {
        val zoom = 16.7f
        val centerLat = 18.6042
        val centerLng = 73.7332
        val anchorX = 540f
        val anchorY = 1240f
        val tileSizePx = 256f * 3f // 256 dp on a 3× density screen

        val transform = RouteRenderMath.worldToScreenTransform(
            displayZoom = zoom,
            centerLat = centerLat,
            centerLng = centerLng,
            anchorX = anchorX,
            anchorY = anchorY,
            tileSizePx = tileSizePx
        )

        val points = listOf(
            LatLng(18.6042, 73.7332),
            LatLng(18.75, 73.40),
            LatLng(18.50, 73.90)
        )

        for (p in points) {
            val worldX = TileMath.lonToTileX(p.longitude, RouteRenderMath.WORLD_ZOOM.toFloat())
            val worldY = TileMath.latToTileY(p.latitude, RouteRenderMath.WORLD_ZOOM.toFloat())

            val expectedX = anchorX + ((TileMath.lonToTileX(p.longitude, zoom) -
                TileMath.lonToTileX(centerLng, zoom)) * tileSizePx)
            val expectedY = anchorY + ((TileMath.latToTileY(p.latitude, zoom) -
                TileMath.latToTileY(centerLat, zoom)) * tileSizePx)

            assertEquals(expectedX.toFloat(), transform.mapX(worldX), 0.5f)
            assertEquals(expectedY.toFloat(), transform.mapY(worldY), 0.5f)
        }
    }

    @Test
    fun `transform keeps sub-pixel accuracy for a long route`() {
        // Regression: the world vertices used to be stored in a FloatArray, whose
        // ~0.5-unit resolution at tile-17 magnitudes is ~600 screen px at street
        // zoom. The whole world→screen computation must stay in Double and only
        // the final screen coordinate may be narrowed.
        val zoom = 16.7f
        val centerLat = 18.6042
        val centerLng = 73.7332
        val anchorX = 540f
        val anchorY = 1240f
        val tileSizePx = 256f * 3f

        val transform = RouteRenderMath.worldToScreenTransform(
            displayZoom = zoom,
            centerLat = centerLat,
            centerLng = centerLng,
            anchorX = anchorX,
            anchorY = anchorY,
            tileSizePx = tileSizePx
        )

        // A point ~40 km away along the route.
        val farLat = 18.6042 + 0.36
        val farLng = 73.7332
        val worldX = TileMath.lonToTileX(farLng, RouteRenderMath.WORLD_ZOOM.toFloat())
        val expected = anchorX + ((TileMath.lonToTileX(farLng, zoom) -
            TileMath.lonToTileX(centerLng, zoom)) * tileSizePx)

        assertTrue(
            "the transform must stay sub-pixel accurate (expected=$expected, actual=${transform.mapX(worldX)})",
            abs(transform.mapX(worldX) - expected) < 0.5f
        )
    }

    @Test
    fun `tier selection clamps to supported range and changes only on integer steps`() {
        assertEquals(RouteRenderMath.MIN_TIER, RouteRenderMath.tierForZoom(3f))
        assertEquals(RouteRenderMath.MAX_TIER, RouteRenderMath.tierForZoom(25f))
        assertEquals(16, RouteRenderMath.tierForZoom(16.0f))
        assertEquals(16, RouteRenderMath.tierForZoom(16.99f))
        assertEquals(17, RouteRenderMath.tierForZoom(17.0f))
    }

    @Test
    fun `epsilon shrinks as the tier approaches world zoom`() {
        val tileSizePx = 768f
        val coarse = RouteRenderMath.epsilonForTier(12, tileSizePx)
        val fine = RouteRenderMath.epsilonForTier(17, tileSizePx)
        assertTrue("finer tier must use a smaller world epsilon", fine < coarse)
        assertEquals(0.0, RouteRenderMath.epsilonForTier(17, 0f), 1e-9)
    }

    @Test
    fun `core width decreases with zoom and clamps at both ends`() {
        val panorama = RouteRenderMath.coreWidthDp(4f)
        val mid = RouteRenderMath.coreWidthDp(14f)
        val street = RouteRenderMath.coreWidthDp(18f)

        assertTrue("width must not grow while zooming in", panorama >= mid && mid >= street)
        assertEquals(RouteRenderMath.CORE_WIDTH_NEAR_DP, street, 0.001f)
        assertEquals(
            "width must clamp (not cull) above street zoom",
            RouteRenderMath.CORE_WIDTH_NEAR_DP,
            RouteRenderMath.coreWidthDp(25f),
            0.001f
        )
        assertEquals(
            "width must clamp (not cull) below the panorama anchor",
            RouteRenderMath.coreWidthDp(8f),
            panorama,
            0.001f
        )

        // Casing always encloses the core by the fixed margin on both sides.
        for (zoom in listOf(3f, 8f, 13f, 16f, 17.5f, 18f, 25f)) {
            assertEquals(
                RouteRenderMath.coreWidthDp(zoom) + 2f * RouteRenderMath.CASING_MARGIN_DP,
                RouteRenderMath.casingWidthDp(zoom),
                0.001f
            )
        }
        // Shadow encloses the casing by the fixed margin on both sides.
        assertEquals(
            RouteRenderMath.casingWidthDp(16f) + 2f * RouteRenderMath.SHADOW_MARGIN_DP,
            RouteRenderMath.shadowWidthDp(16f),
            0.001f
        )
    }

    @Test
    fun `route band stays visible across the whole navigation zoom range`() {
        // Regression: the band used to be culled outside [13..16], which made the
        // route vanish at the follow-camera zoom (16.5–17.5) — i.e. for the whole
        // ride — and also at long-route overview zooms below 13.
        for (zoom in listOf(3f, 5f, 8f, 12.9f, 13f, 16f, 16.5f, 17f, 17.5f, 18f, 25f)) {
            assertTrue(
                "core must be drawable at zoom $zoom",
                RouteRenderMath.coreWidthDp(zoom).isFinite() && RouteRenderMath.coreWidthDp(zoom) > 0f
            )
            assertTrue(
                "casing must be drawable at zoom $zoom",
                RouteRenderMath.casingWidthDp(zoom).isFinite() && RouteRenderMath.casingWidthDp(zoom) > 0f
            )
            assertTrue(
                "streak must be drawable at zoom $zoom",
                RouteRenderMath.streakWidthDp(zoom).isFinite() && RouteRenderMath.streakWidthDp(zoom) > 0f
            )
        }
    }

    @Test
    fun `street route band is thin enough not to swallow the road`() {
        // Follow-camera zooms: the total painted band (shadow = casing + margin)
        // must stay in a readable range rather than the old ~25 dp slab.
        for (zoom in listOf(16.5f, 17f, 17.5f, 18f)) {
            val total = RouteRenderMath.shadowWidthDp(zoom)
            assertTrue("band $total dp at zoom $zoom is too thin", total >= 10f)
            assertTrue("band $total dp at zoom $zoom is too thick", total <= 18f)
        }
    }

    @Test
    fun `direction dot spacing stays within the readable band across zooms`() {
        for (step in 10..18) {
            val zoom = step.toFloat()
            val spacing = RouteRenderMath.directionDotSpacingPx(zoom)
            assertTrue("spacing $spacing at zoom $zoom must be >= 56", spacing >= 56f)
            assertTrue("spacing $spacing at zoom $zoom must be <= 320", spacing <= 320f)
        }
    }

    @Test
    fun `streak width is constant across zooms`() {
        for (zoom in listOf(3f, 13f, 16f, 17.5f, 25f)) {
            assertEquals(
                RouteRenderMath.STREAK_WIDTH_DP,
                RouteRenderMath.streakWidthDp(zoom),
                0.001f
            )
        }
    }
}
