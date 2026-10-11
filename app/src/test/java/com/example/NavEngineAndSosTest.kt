package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.RideRepository
import com.example.data.location.LocationTracker
import com.example.data.location.LocationUpdate
import com.example.data.location.NavEngine
import com.example.data.location.RouteGeometry
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavEngineAndSosTest {

    @Test
    fun `test NavEngine route progress and step advancing`() {
        val navEngine = NavEngine()

        val p1 = LatLng(18.5204, 73.8567) // Start
        val p2 = LatLng(18.6000, 73.7000) // Midpoint
        val p3 = LatLng(18.7546, 73.4062) // Destination

        val d12 = p1.distanceTo(p2) * 1000.0
        val d23 = p2.distanceTo(p3) * 1000.0
        val totalDist = d12 + d23

        val step1 = NavigationStep("Head north", "Highway", d12, 900.0, "depart", "straight")
        val step2 = NavigationStep("Turn right onto Express", "Expressway", d23, 1200.0, "turn", "right")

        val routeResult = RouteResult(
            coordinates = listOf(p1, p2, p3),
            totalDistanceMeters = totalDist,
            totalDurationSeconds = 2100.0,
            steps = listOf(step1, step2)
        )

        navEngine.setRoute(routeResult)
        assertEquals(0, navEngine.progress.value.currentManeuverIndex)

        // Simulate rider location near start
        val locAtStart = LocationUpdate(p1, speedKmh = 45.0, headingDeg = 0f, accuracyMeters = 5f)
        val progressStart = navEngine.processLocation(locAtStart)

        assertEquals(0, progressStart.currentManeuverIndex)
        assertFalse(progressStart.isOffRoute)
        assertFalse(progressStart.isArrived)

        // Mark maneuver 0 announced
        navEngine.markManeuverAnnounced(0)
        assertEquals(0, navEngine.lastAnnouncedManeuverIndex.value)

        // Simulate rider progressing near midpoint
        val locAtMid = LocationUpdate(p2, speedKmh = 50.0, headingDeg = 45f, accuracyMeters = 8f)
        val progressMid = navEngine.processLocation(locAtMid)

        assertTrue("Remaining distance at midpoint should be less than start distance", progressMid.remainingRouteDistanceMeters < progressStart.remainingRouteDistanceMeters)
    }

    @Test
    fun `test adaptive rerouting accuracy filter and deviation thresholds`() {
        val p1 = LatLng(18.5204, 73.8567)
        val p2 = LatLng(18.7546, 73.4062)
        val route = listOf(p1, p2)

        // Point very far from route (> 5 km)
        val farPoint = LatLng(19.0000, 74.5000)

        // Fix with poor accuracy (> 30m) -> should NOT trigger off-route evaluation
        val inaccurateFix = LocationUpdate(farPoint, speedKmh = 60.0, headingDeg = 0f, accuracyMeters = 50f)
        val navEngine = NavEngine()
        navEngine.setRoute(RouteResult(route, 50000.0, 3000.0, emptyList()))

        val progInaccurate = navEngine.processLocation(inaccurateFix)
        assertFalse("Inaccurate fix (> 30m) must not mark off-route", progInaccurate.isOffRoute)

        // Reliable fix (accuracy <= 30m) far off route -> marks off-route true
        val reliableOffRouteFix = LocationUpdate(farPoint, speedKmh = 60.0, headingDeg = 0f, accuracyMeters = 10f)
        val progReliable = navEngine.processLocation(reliableOffRouteFix)
        assertTrue("Reliable fix (> 120m off polyline) must mark off-route true", progReliable.isOffRoute)
    }

    @Test
    fun `test SOS fresh location validation and idempotency`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            val options = com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId("1:13707892827:android:e982c3179342c585f0877e")
                .setApiKey("AIzaSyBwTrOliLZV_Z2UkyCVjOKq2JbxoxOj0Yg")
                .setProjectId("gen-lang-client-0349632077")
                .build()
            com.google.firebase.FirebaseApp.initializeApp(context, options)
        }

        val tracker = LocationTracker(context)
        val repo = RideRepository(context, tracker)
        repo.initCurrentUser("rider_sos_user", "Arpit Test")

        val ride = repo.createRide("Test SOS Convoy", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        assertNotNull(ride)

        // Trigger SOS
        repo.triggerSos()
        val sos1 = repo.activeSos.value
        assertNotNull(sos1)
        assertEquals("Arpit Test", sos1?.riderName)
        assertTrue("Location source must be valid", sos1?.locationSource in listOf("FRESH_LOCATION_FIX", "LAST_KNOWN_LOCATION", "LOCATION_UNAVAILABLE"))

        // Idempotency check: duplicate trigger should not overwrite or create new SOS event
        repo.triggerSos()
        val sos2 = repo.activeSos.value
        assertEquals("SOS ID must remain identical on duplicate trigger", sos1?.id, sos2?.id)

        // Resolve SOS
        repo.resolveSos()
        assertEquals(null, repo.activeSos.value)
        repo.cleanup()
    }

    @Test
    fun `test route geometry perpendicular segment distance`() {
        val a = LatLng(18.5200, 73.8500)
        val b = LatLng(18.5200, 73.8600)
        val pointOnSegment = LatLng(18.5200, 73.8550)

        val distToSegment = RouteGeometry.distanceToSegmentMeters(pointOnSegment, a, b)
        assertTrue("Distance to point on segment should be near 0 meters", distToSegment < 1.0)

        val pointOffset = LatLng(18.5210, 73.8550) // ~110m north
        val distOffset = RouteGeometry.distanceToSegmentMeters(pointOffset, a, b)
        assertTrue("Distance to offset point should be approximately 110 meters", distOffset in 100.0..120.0)
    }
}
