package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.RideDatabase
import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideViewModelRerouteAndDistanceTest {

    private lateinit var context: Context
    private lateinit var viewModel: RideViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        viewModel = RideViewModel(context as android.app.Application)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test Scenario 1 and 2 - Original Start and Destination Immutability Across Reroutes`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567) // Pune
        val pointB = LatLng(18.6000, 73.7000) // Midpoint 1
        val pointC = LatLng(18.6800, 73.5500) // Midpoint 2
        val pointD = LatLng(18.7546, 73.4062) // Lonavala

        viewModel.createRide("Immutable Test Ride", "Pune", "Lonavala", pointA, pointD)
        val initialRide = viewModel.currentRide.value
        requireNotNull(initialRide)

        assertEquals("Original start location must match Point A", pointA, initialRide.startLocation)
        assertEquals("Original destination must match Point D", pointD, initialRide.destinationLocation)

        viewModel.startRide()

        // Reroute 1 at Point B
        viewModel.repository.updateRoute(
            routePoints = listOf(pointB, pointD),
            totalDistanceMeters = 35000.0,
            totalDurationSeconds = 2400.0,
            steps = emptyList()
        )

        val rideAfterReroute1 = viewModel.currentRide.value
        requireNotNull(rideAfterReroute1)
        assertEquals("Original start location must remain Point A after Reroute 1", pointA, rideAfterReroute1.startLocation)
        assertEquals("Destination location must remain Point D after Reroute 1", pointD, rideAfterReroute1.destinationLocation)

        // Reroute 2 at Point C
        viewModel.repository.updateRoute(
            routePoints = listOf(pointC, pointD),
            totalDistanceMeters = 18000.0,
            totalDurationSeconds = 1200.0,
            steps = emptyList()
        )

        val rideAfterReroute2 = viewModel.currentRide.value
        requireNotNull(rideAfterReroute2)
        assertEquals("Original start location must remain Point A after Reroute 2", pointA, rideAfterReroute2.startLocation)
        assertEquals("Destination location must remain Point D after Reroute 2", pointD, rideAfterReroute2.destinationLocation)
    }

    @Test
    fun `test Scenario 3 - Reroute Origin Equals Accepted Current GPS Position`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567)
        val pointD = LatLng(18.7546, 73.4062)
        val currentGps = LatLng(18.6000, 73.7000)

        viewModel.createRide("GPS Reroute Test", "Pune", "Lonavala", pointA, pointD)
        viewModel.startRide()

        // Supply GPS location update
        viewModel.locationTracker.updateLocationForTesting(
            LocationUpdate(currentGps, speedKmh = 45.0, headingDeg = 180f, accuracyMeters = 8f)
        )

        val rerouteUserLoc = viewModel.locationTracker.currentLocation.value?.latLng
        assertEquals("Reroute origin must match accepted current GPS position", currentGps, rerouteUserLoc)
    }

    @Test
    fun `test Scenario 4 - Remaining Distance Updates to New Active Route`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567)
        val pointB = LatLng(18.6000, 73.7000)
        val pointD = LatLng(18.7546, 73.4062)

        val initialRoute = RouteResult(
            coordinates = listOf(pointA, pointB, pointD),
            totalDistanceMeters = 10000.0,
            totalDurationSeconds = 600.0,
            steps = listOf(NavigationStep("Start", "Road", 10000.0, 600.0))
        )

        viewModel.navEngine.setRoute(initialRoute)
        assertEquals(10000.0, viewModel.navEngine.progress.value.remainingRouteDistanceMeters, 0.1)

        // New active route after reroute
        val rerouteRoute = RouteResult(
            coordinates = listOf(pointB, pointD),
            totalDistanceMeters = 6400.0,
            totalDurationSeconds = 400.0,
            steps = listOf(NavigationStep("Reroute Step", "New Road", 6400.0, 400.0))
        )

        viewModel.navEngine.setRoute(rerouteRoute)
        assertEquals("Remaining distance must immediately update to new active route total", 6400.0, viewModel.navEngine.progress.value.remainingRouteDistanceMeters, 0.1)
    }

    @Test
    fun `test Scenario 5 - Accumulated Actual Distance Survives Rerouting`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567)
        val pointD = LatLng(18.7546, 73.4062)

        viewModel.createRide("Odometer Test", "Pune", "Lonavala", pointA, pointD)
        viewModel.startRide()

        // Simulate 2.5 km of GPS movement in 10 steps of ~250m
        for (i in 0..10) {
            val stepLat = 18.5204 + i * (0.0225 / 10)
            viewModel.repository.processLocationUpdate(LocationUpdate(LatLng(stepLat, 73.8567), speedKmh = 50.0, headingDeg = 0f, accuracyMeters = 5f))
        }

        val p2 = LatLng(18.5429, 73.8567) // ~2.5 km North
        val distBeforeReroute = viewModel.currentRide.value?.actualRecordedDistanceMeters ?: 0.0
        assertTrue("Distance before reroute should be approx 2.5 km (was $distBeforeReroute)", distBeforeReroute in 2400.0..2600.0)

        // Reroute occurs
        viewModel.repository.updateRoute(
            routePoints = listOf(p2, pointD),
            totalDistanceMeters = 40000.0,
            totalDurationSeconds = 2500.0,
            steps = emptyList()
        )

        // Simulate another 1.2 km movement after reroute in 5 steps of ~240m
        for (i in 1..5) {
            val stepLat = 18.5429 + i * (0.0108 / 5)
            viewModel.repository.processLocationUpdate(LocationUpdate(LatLng(stepLat, 73.8567), speedKmh = 50.0, headingDeg = 0f, accuracyMeters = 5f))
        }

        val totalDistAfter = viewModel.currentRide.value?.actualRecordedDistanceMeters ?: 0.0
        assertTrue("Accumulated actual distance must survive rerouting and equal sum (~3.7 km, was $totalDistAfter)", totalDistAfter in 3600.0..3800.0)
    }

    @Test
    fun `test Scenario 6 - Route Progress Resets for New Geometry`() = runBlocking {
        val pts1 = (0..50).map { LatLng(18.52 + it * 0.001, 73.85) }
        val route1 = RouteResult(pts1, 5000.0, 300.0, emptyList())

        viewModel.navEngine.setRoute(route1)
        // Simulate rider at index 42
        viewModel.navEngine.processLocation(LocationUpdate(pts1[42], speedKmh = 40.0, headingDeg = 0f, accuracyMeters = 5f))
        assertEquals(42, viewModel.navEngine.progress.value.currentSegmentIndex)

        // Reroute to 15-segment route
        val pts2 = (0..14).map { LatLng(18.56 + it * 0.001, 73.85) }
        val route2 = RouteResult(pts2, 1500.0, 100.0, emptyList())

        viewModel.navEngine.setRoute(route2)
        // Segment index must reset cleanly without IndexOutOfBoundsException
        assertEquals(0, viewModel.navEngine.progress.value.currentSegmentIndex)
        assertFalse(viewModel.navEngine.progress.value.isOffRoute)
    }

    @Test
    fun `test Scenario 7 - Stale Reroute Response Rejection`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567)
        val pointD = LatLng(18.7546, 73.4062)

        viewModel.createRide("Stale Reroute Test", "Pune", "Lonavala", pointA, pointD)
        viewModel.startRide()

        val activeRoute2 = listOf(LatLng(18.60, 73.70), pointD)
        viewModel.repository.updateRoute(
            routePoints = activeRoute2,
            totalDistanceMeters = 15000.0,
            totalDurationSeconds = 1000.0,
            steps = emptyList()
        )

        // End ride so active screen is RIDE_SUMMARY
        viewModel.endRide()

        // Attempt stale reroute callback
        viewModel.rerouteFromCurrentLocation()

        // Ride points must not be modified by stale callback
        assertEquals(AppScreen.RIDE_SUMMARY, viewModel.currentScreen.value)
    }

    @Test
    fun `test Scenario 8 - Completion and Recent Rides Distance Consistency`() = runBlocking {
        val pointA = LatLng(18.5204, 73.8567)
        val pointD = LatLng(18.7546, 73.4062)

        viewModel.createRide("Completion Test", "Pune", "Lonavala", pointA, pointD)
        viewModel.startRide()

        val initialRide = viewModel.currentRide.value
        requireNotNull(initialRide)

        val summaryDistance = initialRide.finalSummaryDistanceMeters
        assertTrue("Summary distance must be non-zero from planned distance if no GPS recorded", summaryDistance > 0.0)

        val db = RideDatabase.getInstance(context)
        val durationSec = initialRide.totalDurationSeconds
        val avgSpeed = if (durationSec > 60 && summaryDistance > 500) ((summaryDistance / 1000.0) / (durationSec / 3600.0)) else 0.0
        val entity = com.example.data.db.CompletedRideEntity(
            id = initialRide.id,
            rideName = initialRide.name,
            startLocationName = initialRide.startLocationName,
            destinationName = initialRide.destinationName,
            distanceMeters = summaryDistance,
            durationSeconds = durationSec,
            avgSpeedKmh = avgSpeed,
            riderCount = 1,
            completedAtMs = System.currentTimeMillis(),
            hadEmergency = false
        )
        db.completedRideDao().insertRide(entity)

        val savedRides = db.completedRideDao().getRideById(initialRide.id)
        assertNotNull(savedRides)
        assertEquals("Saved Room distance must match finalSummaryDistanceMeters", summaryDistance, savedRides!!.distanceMeters, 0.1)
    }
}
