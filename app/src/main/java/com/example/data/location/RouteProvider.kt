package com.example.data.location

import com.example.data.model.LatLng
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
}
