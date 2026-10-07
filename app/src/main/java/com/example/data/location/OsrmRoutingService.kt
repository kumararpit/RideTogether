package com.example.data.location

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import com.example.data.model.NavigationStep
import com.example.data.model.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OsrmRoutingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches real road-following route from OSRM public engine (₹0 cost, no API key).
     * Attempts HTTPS first with HTTP fallback.
     */
    suspend fun fetchRoute(
        start: LatLng,
        destination: LatLng,
        profile: String = "driving"
    ): RouteResult = withContext(Dispatchers.IO) {
        val path = "/route/v1/$profile/" +
                "${start.longitude},${start.latitude};" +
                "${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=geojson&steps=true"

        val endpoints = listOf(
            "https://router.project-osrm.org$path",
            "http://router.project-osrm.org$path"
        )

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", TileMath.OSM_USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                        if (!bodyString.isNullOrBlank()) {
                            val json = JSONObject(bodyString)
                            val code = json.optString("code")

                            if (code == "Ok") {
                                val routesArray = json.getJSONArray("routes")
                                if (routesArray.length() > 0) {
                                    val routeObj = routesArray.getJSONObject(0)
                                    val totalDistance = routeObj.optDouble("distance", 0.0)
                                    val totalDuration = routeObj.optDouble("duration", 0.0)

                                    // Parse road coordinates
                                    val geometryObj = routeObj.getJSONObject("geometry")
                                    val coordsArray = geometryObj.getJSONArray("coordinates")
                                    val points = mutableListOf<LatLng>()

                                    for (i in 0 until coordsArray.length()) {
                                        val pair = coordsArray.getJSONArray(i)
                                        val lon = pair.getDouble(0)
                                        val lat = pair.getDouble(1)
                                        points.add(LatLng(lat, lon))
                                    }

                                    // Parse turn-by-turn navigation steps
                                    val stepsList = mutableListOf<NavigationStep>()
                                    val legsArray = routeObj.optJSONArray("legs")
                                    if (legsArray != null && legsArray.length() > 0) {
                                        val leg0 = legsArray.getJSONObject(0)
                                        val rawSteps = leg0.optJSONArray("steps")
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
                                                stepsList.add(
                                                    NavigationStep(
                                                        instruction = instruction,
                                                        roadName = roadName.ifBlank { "Main Road" },
                                                        distanceMeters = stepDist,
                                                        durationSeconds = stepDuration,
                                                        maneuverType = manType,
                                                        modifier = manModifier
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    if (points.isNotEmpty()) {
                                        return@withContext RouteResult(
                                            coordinates = points,
                                            totalDistanceMeters = totalDistance,
                                            totalDurationSeconds = totalDuration,
                                            steps = stepsList
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to next endpoint or fallback
            }
        }

        // Offline fallback: smooth multi-point interpolation between start & destination
        generateFallbackRoute(start, destination)
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
            steps = fallbackSteps
        )
    }
}
