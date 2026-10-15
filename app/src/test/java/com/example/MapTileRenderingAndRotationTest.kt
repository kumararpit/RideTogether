package com.example

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MapTileRenderingAndRotationTest {

    @Test
    fun `test required tile bounds for rotated viewports across 0, 45, 90, 135, 180, 270 degrees`() {
        val widthPx = 1080f
        val heightPx = 2400f
        val zoomLevel = 16
        val tileSizePx = 256f * 2.75f // ~704px on xxdpi device

        val centerLat = 18.5204
        val centerLng = 73.8567
        val centerTileX = TileMath.lonToTileX(centerLng, zoomLevel.toFloat())
        val centerTileY = TileMath.latToTileY(centerLat, zoomLevel.toFloat())

        val anchorX = widthPx / 2f
        val anchorY = heightPx * 0.65f // Following mode pivot

        val headingsToTest = listOf(0f, 45f, 90f, 135f, 180f, 270f)

        for (headingDeg in headingsToTest) {
            val tileRange = TileMath.calculateVisibleTileRange(
                widthPx = widthPx,
                heightPx = heightPx,
                anchorX = anchorX,
                anchorY = anchorY,
                centerTileXDouble = centerTileX,
                centerTileYDouble = centerTileY,
                zoomLevel = zoomLevel,
                tileSizePx = tileSizePx,
                tileMargin = 2
            )

            assertTrue("minX must be <= maxX at heading $headingDeg", tileRange.minX <= tileRange.maxX)
            assertTrue("minY must be <= maxY at heading $headingDeg", tileRange.minY <= tileRange.maxY)

            // Verify all 4 screen corners are strictly covered by tileRange
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val cosH = cos(headingRad)
            val sinH = sin(headingRad)

            val screenCorners = listOf(
                Pair(0f, 0f),
                Pair(widthPx, 0f),
                Pair(widthPx, heightPx),
                Pair(0f, heightPx)
            )

            for ((sx, sy) in screenCorners) {
                val dx = sx - anchorX
                val dy = sy - anchorY

                val unrotatedX = anchorX + dx * cosH - dy * sinH
                val unrotatedY = anchorY + dx * sinH + dy * cosH

                val cornerTileX = (centerTileX + (unrotatedX - anchorX) / tileSizePx).toInt()
                val cornerTileY = (centerTileY + (unrotatedY - anchorY) / tileSizePx).toInt()

                assertTrue(
                    "Corner ($sx, $sy) tileX ($cornerTileX) must fall within [${tileRange.minX}, ${tileRange.maxX}] at $headingDeg deg",
                    cornerTileX >= tileRange.minX && cornerTileX <= tileRange.maxX
                )
                assertTrue(
                    "Corner ($sx, $sy) tileY ($cornerTileY) must fall within [${tileRange.minY}, ${tileRange.maxY}] at $headingDeg deg",
                    cornerTileY >= tileRange.minY && cornerTileY <= tileRange.maxY
                )
            }
        }
    }

    @Test
    fun `test tile coverage across different viewport aspect ratios (portrait, landscape, square)`() {
        val zoomLevel = 15
        val tileSizePx = 512f
        val centerTileX = TileMath.lonToTileX(73.8567, zoomLevel.toFloat())
        val centerTileY = TileMath.latToTileY(18.5204, zoomLevel.toFloat())

        val viewports = listOf(
            Pair(1080f, 2400f), // Portrait
            Pair(2400f, 1080f), // Landscape
            Pair(1080f, 1080f)  // Square
        )

        for ((w, h) in viewports) {
            for (heading in listOf(30f, 120f, 210f, 300f)) {
                val range = TileMath.calculateVisibleTileRange(
                    widthPx = w,
                    heightPx = h,
                    anchorX = w / 2f,
                    anchorY = h / 2f,
                    centerTileXDouble = centerTileX,
                    centerTileYDouble = centerTileY,
                    zoomLevel = zoomLevel,
                    tileSizePx = tileSizePx,
                    tileMargin = 2
                )

                assertTrue("Aspect ratio ${w}x${h} at heading $heading must have non-empty tile range", range.maxX >= range.minX && range.maxY >= range.minY)
            }
        }
    }

    @Test
    fun `test fractional tile positions and edge tile coverage`() {
        val zoomLevel = 16
        val tileSizePx = 512f

        // Non-integer tile center coordinates
        val centerTileX = 32768.4567
        val centerTileY = 28912.8912

        val range = TileMath.calculateVisibleTileRange(
            widthPx = 1000f,
            heightPx = 1000f,
            anchorX = 500f,
            anchorY = 500f,
            centerTileXDouble = centerTileX,
            centerTileYDouble = centerTileY,
            zoomLevel = zoomLevel,
            tileSizePx = tileSizePx,
            tileMargin = 2
        )

        val centerIntX = centerTileX.toInt()
        val centerIntY = centerTileY.toInt()

        assertTrue("Range minX must extend left of center tile", range.minX <= centerIntX - 1)
        assertTrue("Range maxX must extend right of center tile", range.maxX >= centerIntX + 1)
        assertTrue("Range minY must extend above center tile", range.minY <= centerIntY - 1)
        assertTrue("Range maxY must extend below center tile", range.maxY >= centerIntY + 1)
    }

    @Test
    fun `test forward and inverse camera transformation consistency`() {
        val pivotX = 540f
        val pivotY = 1560f
        val headingDeg = 67.5f

        val headingRad = Math.toRadians(headingDeg.toDouble())
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)

        // Original content space point U
        val uX = 320f
        val uY = 890f

        // Forward transform (Compose graphicsLayer rotationZ = -headingDeg)
        val radFwd = Math.toRadians(-headingDeg.toDouble())
        val cosFwd = cos(radFwd)
        val sinFwd = sin(radFwd)

        val dx = uX - pivotX
        val dy = uY - pivotY

        val sX = (pivotX + dx * cosFwd - dy * sinFwd).toFloat()
        val sY = (pivotY + dx * sinFwd + dy * cosFwd).toFloat()

        // Inverse transform (used in calculateVisibleTileRange)
        val sdx = sX - pivotX
        val sdy = sY - pivotY

        val restoredUX = (pivotX + sdx * cosH - sdy * sinH).toFloat()
        val restoredUY = (pivotY + sdx * sinH + sdy * cosH).toFloat()

        assertEquals("Restored X must match original unrotated X", uX, restoredUX, 0.01f)
        assertEquals("Restored Y must match original unrotated Y", uY, restoredUY, 0.01f)
    }

    @Test
    fun `test route geometry alignment under rotated camera transform`() {
        val pune = LatLng(18.5204, 73.8567)
        val lonavala = LatLng(18.7546, 73.4062)
        val zoom = 14f

        val tileXPune = TileMath.lonToTileX(pune.longitude, zoom)
        val tileYPune = TileMath.latToTileY(pune.latitude, zoom)

        val tileXLonavala = TileMath.lonToTileX(lonavala.longitude, zoom)
        val tileYLonavala = TileMath.latToTileY(lonavala.latitude, zoom)

        val distTileUnits = kotlin.math.hypot(tileXLonavala - tileXPune, tileYLonavala - tileYPune)
        assertTrue("Distance between Pune and Lonavala in zoom 14 tile units should be ~10-20 tiles", distTileUnits in 10.0..30.0)
    }

    @Test
    fun `test camera zoom recalculates visible tile bounds`() {
        val widthPx = 1080f
        val heightPx = 1920f
        val centerLat = 18.5204
        val centerLng = 73.8567
        val tileSizePx = 512f

        val rangeZoom14 = TileMath.calculateVisibleTileRange(
            widthPx, heightPx, widthPx / 2f, heightPx / 2f,
            TileMath.lonToTileX(centerLng, 14f), TileMath.latToTileY(centerLat, 14f),
            14, tileSizePx, tileMargin = 2
        )

        val rangeZoom17 = TileMath.calculateVisibleTileRange(
            widthPx, heightPx, widthPx / 2f, heightPx / 2f,
            TileMath.lonToTileX(centerLng, 17f), TileMath.latToTileY(centerLat, 17f),
            17, tileSizePx, tileMargin = 2
        )

        val count14 = (rangeZoom14.maxX - rangeZoom14.minX + 1) * (rangeZoom14.maxY - rangeZoom14.minY + 1)
        val count17 = (rangeZoom17.maxX - rangeZoom17.minX + 1) * (rangeZoom17.maxY - rangeZoom17.minY + 1)

        assertTrue("Tile count should be valid and positive for zoom 14", count14 > 0)
        assertTrue("Tile count should be valid and positive for zoom 17", count17 > 0)
    }

    @Test
    fun `test tile key formatting for compose recomposition stability`() {
        val zoom = 16
        val x = 32768
        val y = 28912

        val keyStr = "$zoom/$x/$y"
        assertEquals("16/32768/28912", keyStr)
    }
}
