package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.location.RouteProvider
import com.example.data.model.LatLng
import com.example.data.model.RouteLeg
import com.example.data.model.RouteOption
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop
import com.example.ui.viewmodel.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Candidate-route selection through the ride ViewModel.
 *
 * The leader picks one of the candidate routes for the shared plan; the choice
 * is published with a bumped plan version so every member adopts it. A stale
 * candidate response may never overwrite a newer choice.
 *
 * No live OSRM or Firestore: a deterministic fake provider answers with two
 * candidate routes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteCandidateSelectionTest {

    /** Fake provider that answers with a fixed candidate list. */
    private class FakeOptionsProvider : RouteProvider {
        var options: List<RouteOption> = emptyList()
        var requestCount = 0

        override suspend fun fetchRoute(
            start: LatLng,
            destination: LatLng,
            intermediateStops: List<LatLng>,
            stopNames: List<String>,
            originName: String?,
            destinationName: String?,
            planVersion: Long,
            structuredStops: List<RouteStop>?
        ): RouteResult = options.first().route

        override suspend fun fetchRouteOptions(
            start: LatLng,
            destination: LatLng,
            intermediateStops: List<LatLng>,
            stopNames: List<String>,
            originName: String?,
            destinationName: String?,
            planVersion: Long,
            structuredStops: List<RouteStop>?
        ): List<RouteOption> {
            requestCount++
            return options
        }
    }

    private lateinit var context: Context
    private lateinit var provider: FakeOptionsProvider
    private lateinit var viewModel: RideViewModel

    private val pointA = LatLng(18.5204, 73.8567)
    private val pointD = LatLng(18.7546, 73.4062)

    private fun candidate(index: Int, distanceMeters: Double, durationSeconds: Double): RouteOption {
        val points = listOf(pointA, LatLng(18.60, 73.70), pointD)
        return RouteOption(
            index = index,
            route = RouteResult(
                coordinates = points,
                totalDistanceMeters = distanceMeters,
                totalDurationSeconds = durationSeconds,
                steps = emptyList(),
                waypoints = listOf(pointA, pointD),
                stops = listOf(
                    RouteStop(id = "origin", name = "Pune", latitude = pointA.latitude, longitude = pointA.longitude),
                    RouteStop(id = "destination", name = "Lonavala", latitude = pointD.latitude, longitude = pointD.longitude)
                ),
                legs = listOf(RouteLeg(startVertexIndex = 0, distanceMeters = distanceMeters, durationSeconds = durationSeconds))
            )
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        provider = FakeOptionsProvider()
        provider.options = listOf(
            candidate(0, 50000.0, 3000.0),
            candidate(1, 58000.0, 3300.0)
        )
        viewModel = RideViewModel(context as android.app.Application, provider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the candidate list is published with the plan`() = runBlocking {
        viewModel.createRide("Ride", "Pune", "Lonavala", pointA, pointD)

        assertEquals(2, viewModel.routeOptions.value.size)
        val ride = viewModel.repository.currentRide.value
        assertNotNull(ride)
        assertEquals(2, ride!!.routeOptions.size)
        assertEquals(0, ride.selectedRouteIndex)
        // The plan version is bumped by the publication of the candidate list.
        assertTrue("plan version advanced", ride.routePlanVersion >= 1L)
    }

    @Test
    fun `selecting a candidate swaps the active route and the navigation state`() = runBlocking {
        viewModel.createRide("Ride", "Pune", "Lonavala", pointA, pointD)
        assertEquals(50000.0, viewModel.routeResult.value!!.totalDistanceMeters, 1.0)

        viewModel.selectRouteOption(1)

        assertEquals(58000.0, viewModel.routeResult.value!!.totalDistanceMeters, 1.0)
        assertEquals(
            58000.0,
            viewModel.navEngine.progress.value.remainingRouteDistanceMeters,
            1.0
        )
        val ride = viewModel.repository.currentRide.value!!
        assertEquals(1, ride.selectedRouteIndex)
        assertEquals(2, ride.routeOptions.size)
    }

    @Test
    fun `an out-of-range selection is clamped and never applied`() = runBlocking {
        viewModel.createRide("Ride", "Pune", "Lonavala", pointA, pointD)

        viewModel.selectRouteOption(99)

        assertEquals(58000.0, viewModel.routeResult.value!!.totalDistanceMeters, 1.0)
        assertEquals(1, viewModel.repository.currentRide.value!!.selectedRouteIndex)
    }

    @Test
    fun `selecting a candidate without candidates changes nothing`() = runBlocking {
        provider.options = emptyList()
        viewModel.createRide("Ride", "Pune", "Lonavala", pointA, pointD)

        viewModel.selectRouteOption(0)

        assertTrue(viewModel.routeOptions.value.isEmpty())
    }
}
