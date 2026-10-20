package com.example.data.amenities

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface OverpassApiService {

    /**
     * The QL text goes in a urlencoded `data` POST body — the canonical Overpass
     * interface. The previous @GET variant embedded the QL in the URL, and a
     * 4-cell batch reached ~9 kB, past the public endpoint's ~8 kB request-header
     * cap: the server answered HTTP 414 "Request-URI Too Large" (device logcat,
     * 2026-10-19) and structurally rejected every larger viewport.
     */
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun query(@Field("data") query: String): OverpassResponse
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
