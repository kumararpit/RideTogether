package com.example

import com.example.data.location.LocationUpdate
import com.example.data.map.MapCameraController
import com.example.data.map.MapCameraMode
import com.example.data.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
