package com.example.data.amenities

import retrofit2.http.GET
import retrofit2.http.Query

interface OverpassApiService {
    @GET("api/interpreter")
    suspend fun query(@Query("data") query: String): OverpassResponse
}

data class OverpassResponse(
    val elements: List<OverpassElement>?
)

data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double?,
    val lon: Double?,
    val center: Center?,
    val tags: Map<String, String>?
)

data class Center(
    val lat: Double,
    val lon: Double
)
