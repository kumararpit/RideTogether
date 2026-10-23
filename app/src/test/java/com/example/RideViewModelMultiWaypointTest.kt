package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.location.LocationUpdate
import com.example.data.location.RouteProvider
import com.example.data.location.RouteRequestException
import com.example.data.model.LatLng
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop
import com.example.data.model.RouteStopRole
import com.example.ui.viewmodel.RideViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-waypoint route planning through the ride ViewModel with an injected
 * deterministic [RouteProvider]: ordered-stop requests, stale-response
 * rejection, remaining-stop-preserving reroutes, and legacy single-destination
 * compatibility. No live OSRM or Firestore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideViewModelMultiWaypointTest {

    /** Deterministic fake: records every request, answers via queued gates. */
    private class FakeRouteProvider : RouteProvider {
        val requestLog = mutableListOf<Triple<LatLng, LatLng, List<LatLng>>>() // start, dest, intermediates
        private val gates = ArrayDeque<CompletableDeferred<RouteResult>>()
        private val consumed = mutableListOf<CompletableDeferred<RouteResult>>()

        /** Pre-completed response gate (immediate answer). */
        fun enqueue(result: RouteResult) {
            gates.addLast(CompletableDeferred(result))
        }

        /** Pre-queued UNCOMPLETED gate: the next request suspends until [completePending]. */
        fun enqueueGate() {
            gates.addLast(CompletableDeferred())
        }

        /** Pre-queued failing outcome (explicit NoRoute). */
        fun enqueueThrow() {
            gates.addLast(
                CompletableDeferred<RouteResult>().apply {
                    completeExceptionally(RouteRequestException(null, "No road route connects these waypoints"))
                }
            )
        }

        /** Completes the oldest still-pending consumed gate (stale-response simulation). */
        fun completePending(result: RouteResult): Boolean =
            consumed.firstOrNull { !it.isCompleted }?.let { gate ->
                gate.complete(result)
                true
            } ?: false

        override suspend fun fetchRoute(
            start: LatLng,
            destination: LatLng,
            intermediateStops: List<LatLng>,
            stopNames: List<String>,
            originName: String?,
            destinationName: String?,
            planVersion: Long,
            structuredStops: List<RouteStop>?
        ): RouteResult {
            requestLog.add(Triple(start, destination, intermediateStops))
            val gate = if (gates.isNotEmpty()) gates.removeFirst() else CompletableDeferred()
            consumed.add(gate)
            return gate.await()
        }
    }

    private lateinit var context: Context
    private lateinit var provider: FakeRouteProvider
    private lateinit var viewModel: RideViewModel

    private val pointA = LatLng(18.5204, 73.8567) // Pune
    private val stop1 = LatLng(18.5600, 73.8000)
    private val stop2 = LatLng(18.6400, 73.7200)
    private val pointD = LatLng(18.7546, 73.4062) // Lonavala

    private fun multiLegResult(
        points: List<LatLng>,
        stops: List<RouteStop>
    ): RouteResult {
        val legs = stops.zipWithNext().mapIndexed { i, _ ->
            com.example.data.model.RouteLeg(startVertexIndex = i, distanceMeters = 8000.0, durationSeconds = 500.0)
        }
        return RouteResult(
            coordinates = points,
            totalDistanceMeters = 8000.0 * (points.size - 1),
            totalDurationSeconds = 500.0 * (points.size - 1),
            steps = emptyList(),
            isFallback = false,
            waypoints = points,
            stops = stops,
            legs = legs
        )
    }

    private fun places(vararg names: StopSpec) =
        names.map { spec ->
            com.example.data.place.SelectedPlace(
                id = spec.id,
                name = spec.name,
                address = null,
                latitude = spec.latLng.latitude,
                longitude = spec.latLng.longitude,
                source = com.example.data.place.PlaceSource.PHOTON
            )
        }

    private data class StopSpec(val id: String, val name: String, val latLng: LatLng)

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        provider = FakeRouteProvider()
        viewModel = RideViewModel(context as android.app.Application, provider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no intermediate stops preserves the single-destination workflow`() = runBlocking {
        val legacyResult = RouteResult(
            coordinates = listOf(pointA, pointD),
            totalDistanceMeters = 50000.0,
            totalDurationSeconds = 3000.0,
            steps = emptyList()
        )
        provider.enqueue(legacyResult)
        viewModel.createRide("Legacy Ride", "Pune", "Lonavala", pointA, pointD)

        assertEquals(1, provider.requestLog.size)
        val (requestedStart, requestedDest, intermediates) = provider.requestLog.single()
        assertEquals(pointA, requestedStart)
        assertEquals(pointD, requestedDest)
        assertTrue("no intermediates in legacy ride", intermediates.isEmpty())
        assertSame(legacyResult, viewModel.routeResult.value)
        // no interrupted navigation state: route applied to the engine
        assertEquals(50000.0, viewModel.navEngine.progress.value.remainingRouteDistanceMeters, 1.0)
    }

    @Test
    fun `one and two intermediate stops serialize in the user-defined order`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop1, stop2, pointD), planStops()))
        viewModel.createRide(
            "Via Stops", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("s1", "Tea Point", stop1),
                StopSpec("s2", "Fuel B", stop2)
            )
        )

        val (_, dest, intermediates) = provider.requestLog.single()
        assertEquals(pointD, dest)
        assertEquals(listOf(stop1, stop2), intermediates)

        // structured ordered plan persisted for the group ride
        val ride = viewModel.repository.currentRide.value
        assertNotNull(ride)
        assertEquals(
            listOf("origin", "s1", "s2", "destination"),
            ride!!.routeStops.map { it.id }
        )
        // The plan version is monotonic: it starts at 1 with the created plan
        // and advances again when the candidate route list is published.
        assertTrue("plan version must be monotonic", ride.routePlanVersion >= 1L)
        assertEquals(RouteStopRole.ORIGIN, ride.routeStops.first().role)
        assertEquals(RouteStopRole.DESTINATION, ride.routeStops.last().role)
    }

    @Test
    fun `invalid intermediate coordinates are rejected before routing`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, pointD), twoStopPlanStops()))
        viewModel.createRide(
            "Invalid Stop", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("bad", "Broken Place", LatLng(Double.NaN, 73.0)),
                StopSpec("s1", "Good Place", stop1)
            )
        )
        // the NaN intermediate was dropped; only the valid one reached routing
        val (_, _, intermediates) = provider.requestLog.single()
        assertEquals(listOf(stop1), intermediates)
        // the persisted plan only ever contains valid stops
        val ride = viewModel.repository.currentRide.value
        assertTrue(ride!!.routeStops.all { it.isValidCoordinate() })
    }

    @Test
    fun `reordering stops changes the routing order`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop2, stop1, pointD), planStopsReordered()))
        viewModel.createRide(
            "Reordered", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("s2", "Fuel B", stop2),
                StopSpec("s1", "Tea Point", stop1)
            )
        )
        val (_, _, intermediates) = provider.requestLog.single()
        assertEquals("new request order must match user order", listOf(stop2, stop1), intermediates)
    }

    @Test
    fun `rerouting preserves the remaining waypoint sequence through the original destination`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop1, stop2, pointD), planStops()))
        viewModel.createRide(
            "Via Stops", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("s1", "Tea Point", stop1),
                StopSpec("s2", "Fuel B", stop2)
            )
        )
        viewModel.startRide()

        // authoritative navigation progress: rider reached stop1 (leg 0 -> 1)
        viewModel.navEngine.processLocation(
            LocationUpdate(stop1, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(1, viewModel.navEngine.progress.value.currentLegIndex)

        // off-route reroute from a validated current position
        val offRoutePos = LatLng(18.57, 73.80)
        viewModel.locationTracker.updateLocationForTesting(
            LocationUpdate(offRoutePos, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 8f)
        )
        provider.enqueue(multiLegResult(listOf(offRoutePos, stop2, pointD), remainingStops()))
        viewModel.rerouteFromCurrentLocation()

        val secondRequest = provider.requestLog.last()
        assertEquals("reroute origin is the validated fix", offRoutePos, secondRequest.first)
        assertEquals("original destination is kept", pointD, secondRequest.second)
        assertEquals("remaining stops preserved in order, completed stop dropped", listOf(stop2), secondRequest.third)
    }

    @Test
    fun `failed reroute retains the last valid route and navigation state`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop1, pointD), twoStopPlanStops()))
        viewModel.createRide(
            "Via Stops", "Pune", "Lonavala", pointA, pointD,
            places(StopSpec("s1", "Good Place", stop1))
        )
        assertNotNull("route must be applied right after createRide", viewModel.routeResult.value)
        viewModel.startRide()
        assertNotNull("route must survive ride start", viewModel.routeResult.value)
        val validRoute = viewModel.routeResult.value
        val routeVersionBefore = viewModel.navEngine.progress.value.routeVersion

        viewModel.locationTracker.updateLocationForTesting(
            LocationUpdate(LatLng(18.57, 73.80), speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 8f)
        )
        // next planning attempt fails with an explicit NoRoute outcome
        provider.enqueueThrow()
        viewModel.rerouteFromCurrentLocation()

        assertSame("last valid route retained", validRoute, viewModel.routeResult.value)
        assertNotNull(viewModel.routePlanError.value)
        assertEquals("nav state must not reset on failure", routeVersionBefore, viewModel.navEngine.progress.value.routeVersion)
    }

    @Test
    fun `stale route response cannot overwrite a newer route plan`() = runBlocking {
        // request 1 gets a gate that is NOT completed yet; request 2 answers instantly
        provider.enqueueGate()
        viewModel.createRide(
            "Race", "Pune", "Lonavala", pointA, pointD,
            places(StopSpec("s1", "Tea Point", stop1))
        )

        viewModel.startRide()
        viewModel.locationTracker.updateLocationForTesting(
            LocationUpdate(LatLng(18.57, 73.80), speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 8f)
        )
        provider.enqueue(multiLegResult(listOf(LatLng(18.57, 73.80), pointD), legacyEndStops()))
        viewModel.rerouteFromCurrentLocation()

        val appliedAfterReroute = viewModel.routeResult.value
        assertEquals(2, provider.requestLog.size)

        // the stale first response completes NOW — it must never replace the newer route
        provider.completePending(
            multiLegResult(listOf(pointA, stop1, pointD), legacyStartStops())
        )
        assertSame("stale response rejected after newer plan applied", appliedAfterReroute, viewModel.routeResult.value)
    }

    @Test
    fun `removing a stop before the ride re-plans through the stops that remain`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop1, stop2, pointD), planStops()))
        viewModel.createRide(
            "Via Stops", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("s1", "Tea Point", stop1),
                StopSpec("s2", "Fuel B", stop2)
            )
        )

        // The edit route: origin -> Fuel B -> destination, bypassing Tea Point.
        provider.enqueue(
            multiLegResult(
                points = listOf(pointA, stop2, pointD),
                stops = listOf(
                    planStops()[0], planStops()[2], planStops()[3]
                )
            )
        )
        viewModel.removeRouteStop("s1")

        val (start, dest, intermediates) = provider.requestLog.last()
        assertEquals("planned origin is kept before the ride", pointA, start)
        assertEquals("destination is unchanged", pointD, dest)
        assertEquals("the removed stop is not requested", listOf(stop2), intermediates)
        assertEquals(
            listOf("origin", "s2", "destination"),
            viewModel.routePlanStops.value.map { it.id }
        )
        assertEquals(
            "the edited plan is what the group sees",
            listOf("origin", "s2", "destination"),
            viewModel.repository.currentRide.value!!.routeStops.map { it.id }
        )
    }

    @Test
    fun `removing a stop mid-ride bypasses it from the current position`() = runBlocking {
        provider.enqueue(multiLegResult(listOf(pointA, stop1, stop2, pointD), planStops()))
        viewModel.createRide(
            "Via Stops", "Pune", "Lonavala", pointA, pointD,
            places(
                StopSpec("s1", "Tea Point", stop1),
                StopSpec("s2", "Fuel B", stop2)
            )
        )
        viewModel.startRide()

        // The rider has reached the first stop: the next stop (s2) is ahead.
        viewModel.navEngine.processLocation(
            LocationUpdate(stop1, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 5f)
        )
        assertEquals(1, viewModel.navEngine.progress.value.currentLegIndex)
        viewModel.locationTracker.updateLocationForTesting(
            LocationUpdate(stop1, speedKmh = 40.0, headingDeg = 90f, accuracyMeters = 8f)
        )

        provider.enqueue(
            multiLegResult(
                points = listOf(stop1, pointD),
                stops = listOf(
                    RouteStop(
                        id = "place-0", name = "Current Position",
                        latitude = stop1.latitude, longitude = stop1.longitude,
                        role = RouteStopRole.ORIGIN
                    ),
                    planStops()[3]
                )
            )
        )
        viewModel.removeRouteStop("s2")

        val (start, dest, intermediates) = provider.requestLog.last()
        assertEquals("mid-ride re-plan starts at the validated fix", stop1, start)
        assertEquals("destination is unchanged", pointD, dest)
        assertTrue("the removed stop is skipped entirely", intermediates.isEmpty())
        assertEquals(
            "the plan the group receives no longer contains the stop",
            listOf("destination"),
            viewModel.routePlanStops.value.map { it.id }
        )
        assertTrue(
            "the rider is not routed back to the completed stop",
            viewModel.routePlanStops.value.none { it.id == "s1" || it.id == "s2" }
        )
    }

    private fun planStops() = listOf(
        RouteStop(id = "origin", name = "Home", latitude = pointA.latitude, longitude = pointA.longitude, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s1", name = "Tea Point", latitude = stop1.latitude, longitude = stop1.longitude),
        RouteStop(id = "s2", name = "Fuel B", latitude = stop2.latitude, longitude = stop2.longitude),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )

    private fun planStopsReordered() = listOf(
        RouteStop(id = "origin", name = "Home", latitude = pointA.latitude, longitude = pointA.longitude, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s2", name = "Fuel B", latitude = stop2.latitude, longitude = stop2.longitude),
        RouteStop(id = "s1", name = "Tea Point", latitude = stop1.latitude, longitude = stop1.longitude),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )

    private fun twoStopPlanStops() = listOf(
        RouteStop(id = "origin", name = "Home", latitude = pointA.latitude, longitude = pointA.longitude, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s1", name = "Good Place", latitude = stop1.latitude, longitude = stop1.longitude),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )

    private fun remainingStops() = listOf(
        RouteStop(id = "place-0", name = "Current Position", latitude = 18.57, longitude = 73.80, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s2", name = "Fuel B", latitude = stop2.latitude, longitude = stop2.longitude),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )

    private fun legacyEndStops() = listOf(
        RouteStop(id = "place-0", name = "Current Position", latitude = 18.57, longitude = 73.80, role = RouteStopRole.ORIGIN),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )

    private fun legacyStartStops() = listOf(
        RouteStop(id = "origin", name = "Home", latitude = pointA.latitude, longitude = pointA.longitude, role = RouteStopRole.ORIGIN),
        RouteStop(id = "s1", name = "Tea Point", latitude = stop1.latitude, longitude = stop1.longitude),
        RouteStop(id = "destination", name = "Work", latitude = pointD.latitude, longitude = pointD.longitude, role = RouteStopRole.DESTINATION)
    )
}
