package com.example

import com.example.data.location.CourseOverGround
import com.example.data.location.LocationUpdate
import com.example.data.map.MapCameraController
import com.example.data.map.MapCameraMode
import com.example.data.map.OrientationMode
import com.example.data.map.RiderMarkerAnimator
import com.example.data.map.TileMath
import com.example.data.map.ZoomAnchorMath
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertTrue(controller.state.value.zoom > 3f)
    }

    @Test
    fun `test zoom in and zoom out buttons update camera state and handle limits`() {
        val controller = MapCameraController()
        val initialZoom = controller.state.value.zoom

        // Zoom In (+)
        controller.setZoom(initialZoom + 1f)
        assertEquals(initialZoom + 1f, controller.state.value.zoom, 0.01f)

        // Zoom Out (-)
        controller.setZoom(initialZoom)
        assertEquals(initialZoom, controller.state.value.zoom, 0.01f)

        // Max Zoom Limit Clamping (18.0)
        controller.setZoom(20f)
        assertEquals(18f, controller.state.value.zoom, 0.01f)

        // Min Zoom Limit Clamping (3.0)
        controller.setZoom(1f)
        assertEquals(3f, controller.state.value.zoom, 0.01f)
    }

    @Test
    fun `test user zoom override preserved during navigation follow mode until recentered`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        // 1. In FOLLOWING mode, location update sets adaptive zoom (e.g. 17.5f at low speed)
        val loc1 = LocationUpdate(startLoc, speedKmh = 10.0, headingDeg = 0f, accuracyMeters = 5f)
        controller.processLocation(loc1)
        assertEquals(17.5f, controller.state.value.zoom, 0.01f)

        // 2. User taps Zoom Out (-) button to zoom out to 14.0f
        controller.setZoom(14f)
        assertEquals(14f, controller.state.value.zoom, 0.01f)

        // 3. Next location tick in FOLLOWING mode should preserve user's zoom choice (14.0f)
        val loc2 = LocationUpdate(LatLng(18.5210, 73.8567), speedKmh = 45.0, headingDeg = 0f, accuracyMeters = 5f)
        controller.processLocation(loc2)
        assertEquals("User zoom override (14f) must be preserved across location ticks", 14f, controller.state.value.zoom, 0.01f)
        // GPS smoothing between rapid ticks means the center may not reach the new fix
        // in one frame — verify it hasn't jumped away and longitude tracks the fix.
        val center = controller.state.value.center
        assertTrue("Center should not jump backward from new fix", center.latitude >= 18.5204f)
        assertEquals(73.8567, center.longitude, 0.001)

        // 4. Tapping recenter resets the user zoom override and restores the ADAPTIVE
        //    follow framing for the latest motion context (45 km/h → 16.5f). D5 fix
        //    (INV-A): the old hardcoded `zoom = 16f` write is gone — recentering never
        //    pins an arbitrary constant zoom anymore.
        controller.recenter(startLoc, 0f)
        assertEquals("Recenter must restore adaptive follow framing, not hardcoded 16f", 16.5f, controller.state.value.zoom, 0.01f)
    }

    @Test
    fun `R1 recenter from free explore restores adaptive follow framing instead of hardcoded zoom`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        // Follow at 75 km/h (adaptive 15.5), pan away, pinch to zoom 9 (override),
        // feed another location tick to prove FREE-mode ticks still update the
        // framing context, then recenter: D5 fix restores 15.5 — never the old 16f.
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 75.0, headingDeg = 45f, accuracyMeters = 5f))
        assertEquals(15.5f, controller.state.value.zoom, 0.01f)
        controller.onUserGesture()
        controller.setZoom(9f)
        assertEquals(MapCameraMode.FREE_EXPLORE, controller.state.value.mode)
        controller.processLocation(
            LocationUpdate(LatLng(18.53, 73.86), speedKmh = 75.0, headingDeg = null, accuracyMeters = 5f)
        )
        controller.recenter(startLoc, null)
        assertEquals("Recenter restores adaptive follow framing (15.5 @ 75 km/h)", 15.5f, controller.state.value.zoom, 0.01f)
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)
        assertEquals(startLoc, controller.state.value.center)
        assertEquals("Recenter clears stale route bounds", null, controller.state.value.bounds)
    }

    @Test
    fun `R3 recenter with no motion context falls back to low-speed adaptive zoom`() {
        val controller = MapCameraController()
        controller.onUserGesture()
        controller.setZoom(8f)
        controller.recenter(LatLng(18.5204, 73.8567), null)
        assertEquals(17.5f, controller.state.value.zoom, 0.01f)
    }

    @Test
    fun `R2 recenter while following without override keeps zoom unchanged`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 50.0, headingDeg = 0f, accuracyMeters = 5f))
        assertEquals(16.5f, controller.state.value.zoom, 0.01f)
        // Tiny center drift + recenter tap while already following: no zoom pop.
        controller.recenter(LatLng(18.5205, 73.8568), 0f)
        assertEquals("Already-following recenter must not pop the zoom", 16.5f, controller.state.value.zoom, 0.01f)
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)
    }

    @Test
    fun `R6 boot recenter can pin the navigation zoom in a single write`() {
        val controller = MapCameraController()
        controller.recenter(LatLng(18.5204, 73.8567), null, 17f)
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)
        assertEquals(17f, controller.state.value.zoom, 0.01f)
        assertEquals(LatLng(18.5204, 73.8567), controller.state.value.center)
    }

    @Test
    fun `test stationary speed filtering preserves last heading`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        // Moving at 45 km/h with heading 180 deg
        val movingUpdate = LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = 180f, accuracyMeters = 5f)
        controller.processLocation(movingUpdate)
        assertEquals("Heading should update toward 180 when moving", 180f, controller.state.value.heading, 0.01f)

        val headingBeforeStop = controller.state.value.heading

        // Stopped at red light (0 km/h) with noisy compass heading 45 deg
        val stoppedUpdate = LocationUpdate(startLoc, speedKmh = 0.0, headingDeg = 45f, accuracyMeters = 15f)
        controller.processLocation(stoppedUpdate)

        assertEquals("Stationary fix (< 5.4 km/h) must preserve last reliable heading", headingBeforeStop, controller.state.value.heading, 0.01f)
    }

    // ------------------------------------------------------------------
    // Qualified Bearing regression tests
    // ------------------------------------------------------------------

    @Test
    fun `missing bearing while moving must not rotate the map to north`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        // Establish a reliable heading while moving
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = 180f, accuracyMeters = 5f))
        assertEquals(180f, controller.state.value.heading, 0.01f)

        // Next fix arrives WITHOUT bearing data (null) while still moving:
        // the fix must be treated as "no heading available", NOT as due north.
        val fixWithoutBearing = LocationUpdate(LatLng(18.5210, 73.8567), speedKmh = 45.0, headingDeg = null, accuracyMeters = 5f)
        controller.processLocation(fixWithoutBearing)

        assertEquals(
            "Missing bearing must preserve the last qualified heading (never 0°/north)",
            180f, controller.state.value.heading, 0.01f
        )
    }

    @Test
    fun `non-finite or out-of-range bearing is treated as missing bearing`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        controller.processLocation(LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = 180f, accuracyMeters = 5f))
        assertEquals(180f, controller.state.value.heading, 0.01f)

        // NaN bearing must be rejected like a missing bearing (never adopted)
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = Float.NaN, accuracyMeters = 5f))
        assertEquals("NaN bearing must not corrupt camera heading", 180f, controller.state.value.heading, 0.01f)

        // Negative bearing (-10°) is out of the 0..360 contract: reject like missing
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = -10f, accuracyMeters = 5f))
        assertEquals("Out-of-range bearing must not rotate the camera", 180f, controller.state.value.heading, 0.01f)

        // A subsequently valid bearing is still adopted
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 45.0, headingDeg = 45f, accuracyMeters = 5f))
        assertEquals("Valid bearing after missing one must still be adopted", 45f, controller.state.value.heading, 0.01f)
    }

    @Test
    fun `derived course over ground is one qualified source for camera and marker`() {
        val start = LatLng(18.5200, 73.8567)
        // ~95 m due east at this latitude: displacement well above the 3 m minimum
        val east = LatLng(18.5200, 73.8575)

        // Derivation must refuse useless pairs (same point, and pair too close/time)
        val samePoint = CourseOverGround.bearingBetween(start, start)
        val tooClose = CourseOverGround.derive(start, 0L, LatLng(18.5200, 73.8567), 100L)
        assertNull("No direction exists between identical fixes", samePoint)
        assertNull("Sub-3 m displacement must not produce a derived bearing", tooClose)

        val derived = CourseOverGround.derive(
            prev = start,
            prevTimestampMs = 0L,
            current = east,
            currentTimestampMs = 4_000L
        )
        assertNotNull("A 95 m eastward pair must yield a derived bearing", derived)
        assertEquals("Moving due east yields bearing 90", 90.0f, derived!!, 0.5f)

        // 1. Camera consumes the qualified bearing (same speed gate as GPS bearing)
        val camera = MapCameraController()
        camera.processLocation(LocationUpdate(east, speedKmh = 40.0, headingDeg = derived, accuracyMeters = 5f))
        assertEquals(90f, camera.state.value.heading, 0.5f)

        // 2. Marker animator consumes the SAME value (shared qualified source)
        val animator = RiderMarkerAnimator()
        animator.updateTarget("r1", east.latitude, east.longitude, derived.toDouble())
        repeat(60) { animator.advance(1.0 / 60.0) } // settle ~1 s of frames
        val renderedBearing = animator.renderedBearing("r1")!!
        assertEquals("Marker converges to the qualified bearing", 90.0, renderedBearing, 1.0)

        // 3. Camera target and marker rendered bearing agree (no drift between layers)
        kotlin.math.abs(camera.state.value.heading - renderedBearing.toFloat()).let { drift ->
            assertTrue("Camera heading and marker bearing must agree (drift=$drift)", drift < 1.0f)
        }
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
        assertEquals(17.5f, zoomTurn)

        // High speed (>= 70 km/h), no turn within 150m -> zoom 15
        val zoomFast = MapCameraController.calculateAdaptiveZoom(speedKmh = 75.0, distanceToNextManeuverMeters = 500.0)
        assertEquals(15.5f, zoomFast)

        // Medium speed (30 to <70 km/h) -> zoom 16
        val zoomMed = MapCameraController.calculateAdaptiveZoom(speedKmh = 50.0, distanceToNextManeuverMeters = null)
        assertEquals(16.5f, zoomMed)

        // Low speed (<30 km/h) -> zoom 17
        val zoomSlow = MapCameraController.calculateAdaptiveZoom(speedKmh = 15.0, distanceToNextManeuverMeters = null)
        assertEquals(17.5f, zoomSlow)

        // Safeguard: adaptive zoom only applies in FOLLOWING mode
        val controller = MapCameraController()
        controller.onUserGesture() // FREE_EXPLORE
        controller.setZoom(14f)
        val locFast = LocationUpdate(LatLng(18.5204, 73.8567), speedKmh = 90.0, headingDeg = 0f, accuracyMeters = 5f)
        controller.processLocation(locFast, distanceToNextManeuverMeters = 100.0)
        assertEquals("Manual zoom in FREE_EXPLORE mode must not be overridden", 14f, controller.state.value.zoom)
    }

    @Test
    fun `test calculateVisibleTileRange for rotated viewports`() {
        val widthPx = 1080f
        val heightPx = 2400f
        val zoomLevel = 16
        val tileSizePx = 256f * 2.75f // ~704px

        val centerLat = 18.5204
        val centerLng = 73.8567
        val centerTileX = TileMath.lonToTileX(centerLng, zoomLevel.toFloat())
        val centerTileY = TileMath.latToTileY(centerLat, zoomLevel.toFloat())

        val anchorX = widthPx / 2f
        val anchorY = heightPx * 0.65f

        for (headingDeg in listOf(0f, 45f, 90f, 180f, 270f)) {
            val tileRange = TileMath.calculateVisibleTileRange(
                widthPx = widthPx,
                heightPx = heightPx,
                anchorX = anchorX,
                anchorY = anchorY,
                centerTileXDouble = centerTileX,
                centerTileYDouble = centerTileY,
                zoomLevel = zoomLevel,
                tileSizePx = tileSizePx,
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

        val tileX = TileMath.lonToTileX(lon, zoom.toFloat())
        val tileY = TileMath.latToTileY(lat, zoom.toFloat())

        val restoredLon = TileMath.tileXToLon(tileX, zoom.toFloat())
        val restoredLat = TileMath.tileYToLat(tileY, zoom.toFloat())

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

    @Test
    fun `test showOverview with usable viewport and padding`() {
        val controller = MapCameraController()
        val p1 = LatLng(18.50, 73.80)
        val p2 = LatLng(18.60, 73.90)
        
        // Very tight padding -> less usable space -> zoom should be smaller (further out)
        controller.showOverview(listOf(p1, p2), widthPx = 1080f, heightPx = 2400f, paddingPx = 400f)
        val zoomWithLargePadding = controller.state.value.zoom
        
        // Small padding -> more usable space -> zoom should be larger (closer in)
        controller.showOverview(listOf(p1, p2), widthPx = 1080f, heightPx = 2400f, paddingPx = 0f)
        val zoomWithNoPadding = controller.state.value.zoom
        
        assertTrue("Zoom should be closer when there is more usable space", zoomWithNoPadding > zoomWithLargePadding)
    }

    // ------------------------------------------------------------------
    // Follow-preserving pinch + centroid-anchored free zoom
    // ------------------------------------------------------------------

    @Test
    fun `setZoom during FOLLOWING keeps follow mode and holds user zoom on next fix`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)

        controller.processLocation(LocationUpdate(startLoc, speedKmh = 50.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)

        // Rider pinches to zoom out while riding: follow mode must survive
        controller.setZoom(14f)
        assertEquals("Pinch-zoom must not kick out of FOLLOWING", MapCameraMode.FOLLOWING, controller.state.value.mode)
        assertEquals(14f, controller.state.value.zoom, 0.01f)

        // Next fix, faster speed would normally raise adaptive zoom to 15.5 —
        // the user's zoom choice must hold while following
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 80.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals("User zoom override must suppress adaptive zoom while following", 14f, controller.state.value.zoom, 0.01f)
        assertEquals(MapCameraMode.FOLLOWING, controller.state.value.mode)
    }

    @Test
    fun `centroid-anchored zoom keeps the map point under the gesture fingers`() {
        val anchorX = 540f
        val anchorY = 960f
        val tile = 256f
        val zoom1 = 16f
        val zoom2 = 17f
        val center1 = LatLng(18.5204, 73.8567)
        // Gesture centroid sits 128 px right / 64 px below the camera anchor
        val centroidX = anchorX + 128f
        val centroidY = anchorY + 64f

        // Unproject the gesture point into geography at the pre-zoom camera
        val anchorTileX = TileMath.lonToTileX(center1.longitude, zoom1) + (centroidX - anchorX) / tile
        val anchorTileY = TileMath.latToTileY(center1.latitude, zoom1) + (centroidY - anchorY) / tile
        val anchorLatLng = LatLng(
            TileMath.tileYToLat(anchorTileY, zoom1),
            TileMath.tileXToLon(anchorTileX, zoom1)
        )

        val newCenter = ZoomAnchorMath.anchoredCenter(
            currentCenterLat = center1.latitude,
            currentCenterLng = center1.longitude,
            currentZoom = zoom1,
            newZoom = zoom2,
            anchorLatLng = anchorLatLng,
            anchorUnrotatedX = centroidX,
            anchorUnrotatedY = centroidY,
            anchorX = anchorX,
            anchorY = anchorY,
            tileSizePx = tile
        )

        // Projection invariance: re-project the anchor point at the NEW zoom/center —
        // it must land back on the same screen position (under the fingers)
        val projX = (anchorX + (TileMath.lonToTileX(anchorLatLng.longitude, zoom2) - TileMath.lonToTileX(newCenter.longitude, zoom2)) * tile).toFloat()
        val projY = (anchorY + (TileMath.latToTileY(anchorLatLng.latitude, zoom2) - TileMath.latToTileY(newCenter.latitude, zoom2)) * tile).toFloat()
        assertEquals("Anchored point stays horizontally under the centroid", centroidX, projX, 0.01f)
        assertEquals("Anchored point stays vertically under the centroid", centroidY, projY, 0.01f)

        // And the anchor actually moved with the pinch: center must shift
        assertTrue("Centering shifted to keep the centroid fixed", newCenter.longitude != center1.longitude)

        // Degenerate inputs fall back to the unchanged center (never NaN)
        val fallback = ZoomAnchorMath.anchoredCenter(
            center1.latitude, center1.longitude, zoom1, zoom2,
            anchorLatLng = LatLng(Double.NaN, Double.NaN),
            anchorUnrotatedX = centroidX, anchorUnrotatedY = centroidY,
            anchorX = anchorX, anchorY = anchorY, tileSizePx = tile
        )
        assertEquals(center1.latitude, fallback.latitude, 1e-9)
        assertEquals(center1.longitude, fallback.longitude, 1e-9)
    }

    @Test
    fun `centroid-anchored zoom is invariant under 35 degree tilt`() {
        val anchorX = 540f
        val anchorY = 1560f
        val tile = 256f
        val tiltCos = cos(Math.toRadians(35.0)).toFloat()
        val zoom1 = 16f
        val zoom2 = 17f
        val center1 = LatLng(18.5204, 73.8567)
        val centroidX = anchorX + 128f
        val centroidY = anchorY + 192f

        // Matching the canvas projection: X uses tileSize, Y uses tileSize*cos(tilt).
        val anchorTileX = TileMath.lonToTileX(center1.longitude, zoom1) +
            (centroidX - anchorX) / tile
        val anchorTileY = TileMath.latToTileY(center1.latitude, zoom1) +
            (centroidY - anchorY) / (tile * tiltCos)
        val anchorLatLng = LatLng(
            TileMath.tileYToLat(anchorTileY, zoom1),
            TileMath.tileXToLon(anchorTileX, zoom1)
        )

        val newCenter = ZoomAnchorMath.anchoredCenter(
            currentCenterLat = center1.latitude,
            currentCenterLng = center1.longitude,
            currentZoom = zoom1,
            newZoom = zoom2,
            anchorLatLng = anchorLatLng,
            anchorUnrotatedX = centroidX,
            anchorUnrotatedY = centroidY,
            anchorX = anchorX,
            anchorY = anchorY,
            tileSizePx = tile,
            tiltCos = tiltCos
        )

        val projX = (anchorX +
            (TileMath.lonToTileX(anchorLatLng.longitude, zoom2) -
                TileMath.lonToTileX(newCenter.longitude, zoom2)) * tile).toFloat()
        val projY = (anchorY +
            (TileMath.latToTileY(anchorLatLng.latitude, zoom2) -
                TileMath.latToTileY(newCenter.latitude, zoom2)) * tile * tiltCos).toFloat()

        assertEquals("Tilted X anchor stays under the centroid", centroidX, projX, 0.02f)
        assertEquals("Tilted Y anchor stays under the centroid", centroidY, projY, 0.02f)
    }

    @Test
    fun `setZoomFree enters free explore and the follow feed stops moving the camera`() {
        val controller = MapCameraController()
        val startLoc = LatLng(18.5204, 73.8567)
        val freeCenter = LatLng(18.5300, 73.8700)

        controller.setZoomFree(14f, freeCenter)
        assertEquals(MapCameraMode.FREE_EXPLORE, controller.state.value.mode)
        assertEquals(14f, controller.state.value.zoom, 0.01f)
        assertEquals(freeCenter.latitude, controller.state.value.center.latitude, 1e-9)

        // While exploring, processLocation must be a no-op (guard already existed;
        // pinned here so the anchored center cannot be stomped by a late GPS fix)
        controller.processLocation(LocationUpdate(startLoc, speedKmh = 60.0, headingDeg = 90f, accuracyMeters = 5f))
        assertEquals(freeCenter.latitude, controller.state.value.center.latitude, 1e-9)
        assertEquals(14f, controller.state.value.zoom, 0.01f)
    }
}
