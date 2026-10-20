package com.example.data.location

import com.example.data.model.LatLng
import com.example.data.model.RouteOption
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop

/**
 * Route-calculation seam so the ride ViewModel is testable without live OSRM.
 * The production implementation delegates to [OsrmRoutingService]; deterministic
 * unit tests inject fakes that satisfy the same multi-waypoint contract.
 */
interface RouteProvider {
    suspend fun fetchRoute(
        start: LatLng,
        destination: LatLng,
        intermediateStops: List<LatLng> = emptyList(),
        stopNames: List<String> = emptyList(),
        originName: String? = null,
        destinationName: String? = null,
        planVersion: Long = 0L,
        structuredStops: List<RouteStop>? = null
    ): RouteResult

    /**
     * Candidate routes for the same ordered waypoint plan.
     *
     * The engine can return several road paths for one ordered set of stops
     * (the reference implementation presents them as candidate routes with a
     * "best route" preset). Every candidate is a complete route with its own
     * geometry, legs and steps.
     *
     * The default implementation returns exactly one candidate - the primary
     * route - so a provider that cannot offer alternatives stays compatible
     * with the whole plan-selection flow.
     */
    suspend fun fetchRouteOptions(
        start: LatLng,
        destination: LatLng,
        intermediateStops: List<LatLng> = emptyList(),
        stopNames: List<String> = emptyList(),
        originName: String? = null,
        destinationName: String? = null,
        planVersion: Long = 0L,
        structuredStops: List<RouteStop>? = null
    ): List<RouteOption> = listOf(
        RouteOption(
            index = 0,
            route = fetchRoute(
                start = start,
                destination = destination,
                intermediateStops = intermediateStops,
                stopNames = stopNames,
                originName = originName,
                destinationName = destinationName,
                planVersion = planVersion,
                structuredStops = structuredStops
            )
        )
    )
}
