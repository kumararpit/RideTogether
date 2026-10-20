package com.example.data.amenities

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object OverpassApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // internal so unit tests can assert the timeout configuration survives refactors.
    internal val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        // HTTP/1.1 for Overpass: these queries wait 20–40 s server-side before one
        // large body; HTTP/2 multiplexing gains nothing and — measured on device
        // (2026-10-19) while the instance shed load — every call ended in
        // `StreamResetException: stream was reset: CANCEL` over h2, while the
        // identical request over h1/POST delivered HTTP 200. The 406/414 classes of
        // the public endpoint also behaved deterministically over h1.
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        // The public endpoint answers GETs without a User-Agent with HTTP 406
        // (device 2026-10-19); identify the app on every request.
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()

    val api: OverpassApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://overpass-api.de/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OverpassApiService::class.java)
    }

    /** Overpass accepts slow client connections; matches the historical 15 s budget. */
    const val CONNECT_TIMEOUT_SECONDS = 15

    /**
     * Measured 2026-10-19 against overpass-api.de: real 3–4 cell amenity queries
     * answered in 22–40 s on the day the instance shed load (ECONNREFUSED / stream
     * resets around the old 15 s wall). The query itself pins server compute at
     * [out:json][timeout:25], so the client read budget must comfortably exceed
     * 25 s — otherwise legitimate queries fail spuriously and the amenity cache
     * (amenity_queries) stays empty forever.
     */
    const val READ_TIMEOUT_SECONDS = 60

    /** Identifies this client to the public endpoint (missing UA ⇒ HTTP 406). */
    const val USER_AGENT = "RideTogether/1.0 (OpenStreetMap amenity loader)"
}
