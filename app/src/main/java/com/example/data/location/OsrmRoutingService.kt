package com.example.data.location

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteLeg
import com.example.data.model.RouteResult
import com.example.data.model.RouteStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Raised for any routing outcome that is NOT the legacy offline fallback:
 * OSRM error codes (NoRoute, NoSegment), malformed payloads, HTTP errors,
 * timeouts, or invalid caller-provided waypoints. The ViewModel turns these
 * into explicit no-route / planning-error states - a failed road route is
 * never silently replaced by a straight-line connection.
 */
class RouteRequestException(
    val osrmCode: String? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class OsrmRoutingService : RouteProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches a road-following route from the OSRM public engine (₹0 cost, no API key).
     * Attempts HTTPS first with HTTP fallback.
     *
     * Multi-waypoint: [intermediateStops] are inserted between start and destination
     * in the EXACT user-defined order (the request coordinate string is
     * origin, stop1, stop2, ..., destination). All coordinates are serialized as
     * longitude,latitude. The returned [RouteResult] preserves every leg, the full
     * overview geometry, totals, and the structured ordered stops. [originName] /
     * [destinationName] label the terminal stops in the structured plan.
     *
     * Behaviour is unchanged for zero intermediate stops (legacy single-destination
     * workflow, including the marked offline fallback).
     */
    override suspend fun fetchRoute(
        start: LatLng,
        destination: LatLng,
        intermediateStops: List<LatLng>,
        stopNames: List<String>,
        originName: String?,
        destinationName: String?,
        planVersion: Long,
        structuredStops: List<com.example.data.model.RouteStop>?
    ): RouteResult = withContext(Dispatchers.IO) {
        val waypoints = listOf(start) + intermediateStops + listOf(destination)
        try {
            validateWaypoints(waypoints)
        } catch (e: IllegalArgumentException) {
            throw RouteRequestException(null, e.message ?: "invalid waypoints", e)
        }

        val coords = buildCoordinatePath(waypoints)
        val path = "/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true"

        val endpoints = listOf(
            "https://router.project-osrm.org$path",
            "http://router.project-osrm.org$path"
        )

        var lastFailure: Exception? = null
        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", TileMath.OSM_USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastFailure = RouteRequestException(
                            "HTTP_${response.code}",
                            "OSRM HTTP error ${response.code}"
                        )
                        return@use
                    }
                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrBlank()) {
                        lastFailure = RouteRequestException(null, "OSRM returned an empty body")
                        return@use
                    }
                    parseRouteResponse(
                        bodyString,
                        waypoints,
                        stopNames = listOf(originName ?: "Origin") +
                            stopNames.take(intermediateStops.size) +
                            listOf(destinationName ?: "Destination"),
                        planVersion = planVersion,
                        structuredStops = structuredStops
                    )?.let { return@withContext it }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                // Continue to next endpoint or fallback
            }
        }

        if (intermediateStops.isEmpty()) {
            // Legacy fallback preserved for the single-destination workflow.
            return@withContext generateFallbackRoute(start, destination)
        }
        throw RouteRequestException(
            lastFailure?.let { (it as? RouteRequestException)?.osrmCode },
            "Route planning failed for ${waypoints.size} waypoints",
            lastFailure
        )
    }

    /**
     * OSRM waypoint serialization: strictly longitude,latitude pairs joined in
     * the caller's declared order (origin, intermediate stops in user order,
     * destination). Internal so unit tests can lock the wire format.
     */
    internal fun buildCoordinatePath(waypoints: List<LatLng>): String =
        waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }

    /** Structural request-side validation (never send unvalidated waypoints). */
    internal fun validateWaypoints(waypoints: List<LatLng>) {
        for (wp in waypoints) {
            if (!wp.latitude.isFinite() || !wp.longitude.isFinite() ||
                wp.latitude !in -90.0..90.0 || wp.longitude !in -180.0..180.0
            ) {
                throw IllegalArgumentException("Waypoint out of range: $wp")
            }
        }
        for (i in 1 until waypoints.size) {
            if (waypoints[i].distanceTo(waypoints[i - 1]) * 1000.0 < 1.0) {
                throw IllegalArgumentException("Duplicate consecutive waypoint at index $i")
            }
        }
        if (waypoints.size > 25) {
            throw IllegalArgumentException("Too many waypoints (max 25 + dedup): ${waypoints.size}")
        }
    }

    /**
     * Parses an OSRM response into a [RouteResult].
     *
     * Strict leg contract: `legs.size == waypoints.size - 1` must hold, or the
     * whole response is rejected (a silently re-associated maneuver would guide
     * the rider to the wrong stop). Visible for unit tests - no network needed.
     */
    internal fun parseRouteResponse(
        body: String,
        waypoints: List<LatLng>,
        stopNames: List<String> = emptyList(),
        planVersion: Long = 0L,
        structuredStops: List<com.example.data.model.RouteStop>? = null
    ): RouteResult? {
        val json = JSONObject(body)
        val code = json.optString("code")

        if (code != "Ok") {
            throw RouteRequestException(code, when (code) {
                "NoRoute" -> "No road route connects these waypoints"
                "NoSegment" -> "A waypoint is unreachable by road"
                else -> "OSRM error: $code"
            })
        }

        val routesArray = json.optJSONArray("routes") ?: throw RouteRequestException(
            code, "OSRM response missing routes"
        )
        if (routesArray.length() == 0) {
            throw RouteRequestException(code, "OSRM returned no routes")
        }
        val routeObj = routesArray.getJSONObject(0)
        val totalDistance = routeObj.optDouble("distance", 0.0)
        val totalDuration = routeObj.optDouble("duration", 0.0)

        // Parse full overview geometry (one continuous polyline).
        val geometryObj = routeObj.getJSONObject("geometry")
        val coordsArray = geometryObj.getJSONArray("coordinates")
        val points = mutableListOf<LatLng>()
        for (i in 0 until coordsArray.length()) {
            val pair = coordsArray.getJSONArray(i)
            points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
        }
        if (points.isEmpty()) {
            throw RouteRequestException(code, "OSRM route has empty geometry")
        }

        val legsArray = routeObj.optJSONArray("legs")
            ?: throw RouteRequestException(code, "OSRM response missing legs")
        val expectedLegs = waypoints.size - 1
        if (legsArray.length() != expectedLegs) {
            throw RouteRequestException(
                code,
                "OSRM leg count mismatch: ${legsArray.length()} for $expectedLegs waypoints"
            )
        }

        // Locate each leg's start vertex inside the merged overview polyline, in
        // order: waypoint k is matched to a vertex at-or-after waypoint k-1's
        // vertex so the walk stays ordered and unambiguous.
        val flatSteps = mutableListOf<NavigationStep>()
        val legs = mutableListOf<RouteLeg>()
        var searchFrom = 0
        for (k in 0 until legsArray.length()) {
            val legObj = legsArray.getJSONObject(k)
            val legDistance = legObj.optDouble("distance", 0.0)
            val legDuration = legObj.optDouble("duration", 0.0)

            val startVertex = if (k == 0) 0 else {
                val idx = RouteGeometry.findClosestPointIndex(
                    waypoints[k],
                    points.subList(searchFrom, points.size)
                ) + searchFrom
                idx
            }

            val legSteps = mutableListOf<NavigationStep>()
            val rawSteps = legObj.optJSONArray("steps")
            if (rawSteps != null) {
                for (j in 0 until rawSteps.length()) {
                    val stepObj = rawSteps.getJSONObject(j)
                    val stepDist = stepObj.optDouble("distance", 0.0)
                    val stepDuration = stepObj.optDouble("duration", 0.0)
                    val roadName = stepObj.optString("name", "Road")
                    val maneuverObj = stepObj.optJSONObject("maneuver")
                    val manType = maneuverObj?.optString("type", "turn") ?: "turn"
                    val manModifier = maneuverObj?.optString("modifier", "straight") ?: "straight"
                    val instruction = buildInstruction(manType, manModifier, roadName)
                    // Allow the caller to disambiguate "Arrive at destination"
                    // with the actual stop name for waypooint-leg arrivals.
                    val finalInstruction = if (manType == "arrive" && k < legsArray.length() - 1) {
                        buildArrivalInstruction(k + 1, stopNames)
                    } else if (manType == "arrive") {
                        "Arrive at destination"
                    } else {
                        instruction
                    }
                    legSteps.add(
                        NavigationStep(
                            instruction = finalInstruction,
                            roadName = roadName.ifBlank { "Main Road" },
                            distanceMeters = stepDist,
                            durationSeconds = stepDuration,
                            maneuverType = manType,
                            modifier = manModifier
                        )
                    )
                }
            }

            legs.add(
                RouteLeg(
                    startVertexIndex = startVertex,
                    distanceMeters = legDistance,
                    durationSeconds = legDuration,
                    steps = legSteps
                )
            )
            flatSteps.addAll(legSteps)
            if (startVertex > searchFrom) searchFrom = startVertex
        }

        // Structured stops synthesized by position for parser-level unit tests;
        // when the caller has a real plan its exact stop list (with stable ids)
        // is passed via [structuredStops] and wins over the synthesized one —
        // but only if it is consistent with the request's waypoint count.
        val stops = structuredStops?.takeIf { it.size == waypoints.size } ?: waypoints.mapIndexed { i, wp ->
            RouteStop(
                id = "wp_$i",
                name = stopNames.getOrNull(i) ?: "Waypoint $i",
                address = null,
                latitude = wp.latitude,
                longitude = wp.longitude,
                role = if (i == 0) com.example.data.model.RouteStopRole.ORIGIN
                else if (i == waypoints.size - 1) com.example.data.model.RouteStopRole.DESTINATION
                else com.example.data.model.RouteStopRole.INTERMEDIATE
            )
        }

        return RouteResult(
            coordinates = points,
            totalDistanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            steps = flatSteps,
            waypoints = waypoints,
            stops = stops,
            legs = legs,
            planVersion = planVersion
        )
    }

    private fun buildArrivalInstruction(nextStopIndex: Int, stopNames: List<String>): String {
        val name = stopNames.getOrNull(nextStopIndex)
        return if (!name.isNullOrBlank()) "Arrive at stop: $name" else "Arrive at intermediate stop ${nextStopIndex}"
    }

    private fun buildInstruction(type: String, modifier: String, road: String): String {
        val roadSuffix = if (road.isNotBlank()) " onto $road" else ""
        return when (type) {
            "depart" -> "Head $modifier$roadSuffix"
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn $modifier$roadSuffix"
            "new name" -> "Continue$roadSuffix"
            "roundabout", "rotary" -> "Take roundabout exit$roadSuffix"
            "fork" -> "Take $modifier fork$roadSuffix"
            else -> "Continue $modifier$roadSuffix"
        }
    }

    private fun generateFallbackRoute(start: LatLng, dest: LatLng): RouteResult {
        val count = 25
        val points = mutableListOf<LatLng>()
        for (i in 0..count) {
            val fraction = i.toDouble() / count.toDouble()
            // Slight curve deviation for realistic visual path
            val curveOffset = kotlin.math.sin(fraction * Math.PI) * 0.015
            val lat = start.latitude + (dest.latitude - start.latitude) * fraction + curveOffset
            val lon = start.longitude + (dest.longitude - start.longitude) * fraction
            points.add(LatLng(lat, lon))
        }

        val straightDistKm = start.distanceTo(dest)
        val roadDistMeters = straightDistKm * 1000.0 * 1.25 // road winding factor
        val durationSec = (roadDistMeters / 15.0) // ~54 km/h average motorcycle speed

        val fallbackSteps = listOf(
            NavigationStep("Depart from start", "Route", 500.0, 30.0, "depart", "straight"),
            NavigationStep("Continue along scenic route", "Main Highway", roadDistMeters - 1000.0, durationSec - 60.0, "continue", "straight"),
            NavigationStep("Arrive at destination", "Destination", 500.0, 30.0, "arrive", "straight")
        )

        return RouteResult(
            coordinates = points,
            totalDistanceMeters = roadDistMeters,
            totalDurationSeconds = durationSec,
            steps = fallbackSteps,
            isFallback = true
        )
    }
}
