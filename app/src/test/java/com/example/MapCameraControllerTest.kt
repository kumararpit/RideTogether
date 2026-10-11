package com.example

import com.example.data.location.LocationUpdate
import com.example.data.map.MapCameraController
import com.example.data.map.MapCameraMode
import com.example.data.map.OrientationMode
import com.example.data.map.TileMath
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MapCameraControllerTest {

    @Test
    fun `test shortest path angle delta math across North boundary`() {
        // 359 deg to 1 deg should be +2 deg, not -358 deg
        val deltaNorth = MapCameraController.shortestPathAngleDelta(359f, 1f)
        assertEquals(2f, deltaNorth, 0.01f)

        // 1 deg to 359 deg should be -2 deg
        val deltaNorthBack = MapCameraController.shortestPathAngleDelta(1f, 359f)
        assertEquals(-2f, deltaNorthBack, 0.01f)

        // 90 deg to 180 deg should be +90 deg
        val deltaEastSouth = MapCameraController.shortestPathAngleDelta(90f, 180f)
        assertEquals(90f, deltaEastSouth, 0.01f)

        // Interpolation across North boundary
        val interpolated = MapCameraController.interpolateHeading(359f, 1f, 0.5f)
        assertEquals(0f, interpolated, 0.01f)
    }

    @Test
    fun `test camera state machine transitions`() {
        val controller = MapCameraController()
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)

        // User drag gesture transitions to FREE_EXPLORE
        controller.onUserGesture()
        assertEquals(MapCameraMode.FREE_EXPLORE, controller.state.value.mode)

        // Recenter restores FOLLOWING mode
        val newLoc = LatLng(18.5204, 73.8567)
        controller.recenter(newLoc, 90f)
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)
        assertEquals(newLoc, controller.state.value.center)
        assertEquals(90f, controller.state.value.heading, 0.01f)

        // Show overview transitions to OVERVIEW
        controller.showOverview(listOf(LatLng(18.50, 73.80), LatLng(18.60, 73.90)))
        assertEquals(MapCameraMode.OVERVIEW, controller.state.value.mode)
        assertEquals(12, controller.state.value.zoom)
    }

    @Test
    fun `test stationary speed filtering preserves last heading`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        // Moving at 45 km/h with heading 180 deg
        val movingUpdate = LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = 180f, accuracyMeters = 5f)
        controller.processLocation(movingUpdate)
        assertTrue("Heading should update toward 180 when moving", controller.state.value.heading > 0f)

        val headingBeforeStop = controller.state.value.heading

        // Stopped at red light (0 km/h) with noisy compass heading 45 deg
        val stoppedUpdate = LocationUpdate(startLoc, speedKmh = 0.0, headingDeg = 45f, accuracyMeters = 15f)
        controller.processLocation(stoppedUpdate)

        assertEquals("Stationary fix (< 3 km/h) must preserve last reliable heading", headingBeforeStop, controller.state.value.heading, 0.01f)
    }

    @Test
    fun `test compass orientation mode toggle and effective heading`() {
        val controller = MapCameraController()
        assertEquals(OrientationMode.HEADING_UP, controller.state.value.orientationMode)

        // Update GPS heading
        val loc = LocationUpdate(LatLng(18.5204, 73.8567), speedKmh = 50.0, headingDeg = 120f, accuracyMeters = 5f)
        controller.processLocation(loc)
        val headingVal = controller.state.value.heading
        assertTrue(headingVal > 0f)
        assertEquals(headingVal, controller.state.value.effectiveHeading, 0.01f)

        // Switch to NORTH_UP
        controller.setOrientationMode(OrientationMode.NORTH_UP)
        assertEquals(OrientationMode.NORTH_UP, controller.state.value.orientationMode)
        assertEquals(0f, controller.state.value.effectiveHeading, 0.01f)
        assertEquals("Raw heading state must preserve GPS heading", headingVal, controller.state.value.heading, 0.01f)

        // Toggle back to HEADING_UP
        controller.toggleOrientationMode()
        assertEquals(OrientationMode.HEADING_UP, controller.state.value.orientationMode)
        assertEquals(headingVal, controller.state.value.effectiveHeading, 0.01f)
    }

    @Test
    fun `test adaptive zoom calculation rules and safeguards`() {
        // Turn within 150m -> zoom 17
        val zoomTurn = MapCameraController.calculateAdaptiveZoom(speedKmh = 80.0, distanceToNextManeuverMeters = 120.0)
        assertEquals(17, zoomTurn)

        // High speed (>= 70 km/h), no turn within 150m -> zoom 15
        val zoomFast = MapCameraController.calculateAdaptiveZoom(speedKmh = 75.0, distanceToNextManeuverMeters = 500.0)
        assertEquals(15, zoomFast)

        // Medium speed (30 to <70 km/h) -> zoom 16
        val zoomMed = MapCameraController.calculateAdaptiveZoom(speedKmh = 50.0, distanceToNextManeuverMeters = null)
        assertEquals(16, zoomMed)

        // Low speed (<30 km/h) -> zoom 17
        val zoomSlow = MapCameraController.calculateAdaptiveZoom(speedKmh = 15.0, distanceToNextManeuverMeters = null)
        assertEquals(17, zoomSlow)

        // Safeguard: adaptive zoom only applies in FOLLOWING mode
        val controller = MapCameraController()
        controller.onUserGesture() // FREE_EXPLORE
        controller.setZoom(14)
        val locFast = LocationUpdate(LatLng(18.5204, 73.8567), speedKmh = 90.0, headingDeg = 0f, accuracyMeters = 5f)
        controller.processLocation(locFast, distanceToNextManeuverMeters = 100.0)
        assertEquals("Manual zoom in FREE_EXPLORE mode must not be overridden", 14, controller.state.value.zoom)
    }

    @Test
    fun `test calculateVisibleTileRange for rotated viewports`() {
        val widthPx = 1080f
        val heightPx = 2400f
        val zoomLevel = 16
        val tileSizePx = 256f * 2.75f // ~704px

        val centerLat = 18.5204
        val centerLng = 73.8567
        val centerTileX = TileMath.lonToTileX(centerLng, zoomLevel)
        val centerTileY = TileMath.latToTileY(centerLat, zoomLevel)

        val anchorX = widthPx / 2f
        val anchorY = heightPx * 0.65f

        for (headingDeg in listOf(0f, 45f, 90f, 180f, 270f)) {
            val tileRange = TileMath.calculateVisibleTileRange(
                widthPx = widthPx,
                heightPx = heightPx,
                anchorX = anchorX,
                anchorY = anchorY,
                panOffsetX = 0f,
                panOffsetY = 0f,
                centerTileXDouble = centerTileX,
                centerTileYDouble = centerTileY,
                zoomLevel = zoomLevel,
                tileSizePx = tileSizePx,
                effectiveHeadingDeg = headingDeg,
                tileMargin = 1
            )

            assertTrue("minX must be <= maxX", tileRange.minX <= tileRange.maxX)
            assertTrue("minY must be <= maxY", tileRange.minY <= tileRange.maxY)

            // Verify every screen corner falls inside tileRange when unrotated
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
                    "Corner ($sx, $sy) tileX ($cornerTileX) must be within range [${tileRange.minX}, ${tileRange.maxX}] at $headingDeg deg",
                    cornerTileX >= tileRange.minX && cornerTileX <= tileRange.maxX
                )
                assertTrue(
                    "Corner ($sx, $sy) tileY ($cornerTileY) must be within range [${tileRange.minY}, ${tileRange.maxY}] at $headingDeg deg",
                    cornerTileY >= tileRange.minY && cornerTileY <= tileRange.maxY
                )
            }
        }
    }

    @Test
    fun `test tile math conversions and cardinal rotation alignment`() {
        val lat = 18.5204
        val lon = 73.8567
        val zoom = 16

        val tileX = TileMath.lonToTileX(lon, zoom)
        val tileY = TileMath.latToTileY(lat, zoom)

        val restoredLon = TileMath.tileXToLon(tileX, zoom)
        val restoredLat = TileMath.tileYToLat(tileY, zoom)

        assertEquals(lon, restoredLon, 0.0001)
        assertEquals(lat, restoredLat, 0.0001)

        // Test cardinal rotations math: 0 deg, 90 deg, 180 deg, 270 deg
        val pivotX = 540f
        val pivotY = 1560f
        val unrotatedX = 540f
        val unrotatedY = 1460f // Point 100px North (UP) of pivot

        for (heading in listOf(0f, 90f, 180f, 270f)) {
            val rad = Math.toRadians(-heading.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            val dx = unrotatedX - pivotX
            val dy = unrotatedY - pivotY
            val rx = (pivotX + dx * cosA - dy * sinA).toFloat()
            val ry = (pivotY + dx * sinA + dy * cosA).toFloat()

            when (heading) {
                0f -> {
                    assertEquals(540f, rx, 0.01f)
                    assertEquals(1460f, ry, 0.01f) // UP on screen
                }
                90f -> {
                    assertEquals(440f, rx, 0.01f) // LEFT on screen
                    assertEquals(1560f, ry, 0.01f)
                }
                180f -> {
                    assertEquals(540f, rx, 0.01f)
                    assertEquals(1660f, ry, 0.01f) // DOWN on screen
                }
                270f -> {
                    assertEquals(640f, rx, 0.01f) // RIGHT on screen
                    assertEquals(1560f, ry, 0.01f)
                }
            }
        }
    }
}
