package com.example.data.location

import com.example.data.map.TileMath
import com.example.data.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class PlaceSearchResult(
    val name: String,
    val displayName: String,
    val latLng: LatLng
)

class PlaceSearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Popular rider destinations & waypoints available offline
    private val popularDestinations = listOf(
        PlaceSearchResult("Lonavala", "Lonavala, Maharashtra, India", LatLng(18.7546, 73.4062)),
        PlaceSearchResult("Pune", "Pune, Maharashtra, India", LatLng(18.5204, 73.8567)),
        PlaceSearchResult("Mumbai", "Mumbai, Maharashtra, India", LatLng(19.0760, 72.8777)),
        PlaceSearchResult("Lavasa", "Lavasa, Mulshi, Pune, Maharashtra", LatLng(18.4088, 73.5076)),
        PlaceSearchResult("Mahabaleshwar", "Mahabaleshwar, Satara, Maharashtra", LatLng(17.9237, 73.6586)),
        PlaceSearchResult("Tamhini Ghat", "Tamhini Ghat, Mulshi, Maharashtra", LatLng(18.4735, 73.4283)),
        PlaceSearchResult("Malshej Ghat", "Malshej Ghat, Thane, Maharashtra", LatLng(19.3411, 73.7744)),
        PlaceSearchResult("Alibaug", "Alibaug, Raigad, Maharashtra", LatLng(18.6414, 72.8722)),
        PlaceSearchResult("Goa", "Panaji, Goa, India", LatLng(15.4909, 73.8278)),
        PlaceSearchResult("Khandala", "Khandala, Maharashtra, India", LatLng(18.7610, 73.3719))
    )

    /**
     * Searches OpenStreetMap Nominatim for places matching query,
     * falling back to curated motorcycle hotspots.
     */
    suspend fun searchPlaces(query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return@withContext popularDestinations
        }

        try {
            val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=6&addressdetails=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", TileMath.OSM_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val jsonArray = JSONArray(bodyString)
                        val results = mutableListOf<PlaceSearchResult>()

                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.getJSONObject(i)
                            val name = item.optString("name").ifBlank {
                                item.optString("display_name").split(",").firstOrNull() ?: trimmed
                            }
                            val displayName = item.optString("display_name")
                            val lat = item.optDouble("lat", 0.0)
                            val lon = item.optDouble("lon", 0.0)

                            if (lat != 0.0 || lon != 0.0) {
                                results.add(PlaceSearchResult(name, displayName, LatLng(lat, lon)))
                            }
                        }

                        if (results.isNotEmpty()) {
                            return@withContext results
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Network fallback to local presets matching query
        }

        // Offline / fallback filter
        popularDestinations.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
            it.displayName.contains(trimmed, ignoreCase = true)
        }
    }
}
