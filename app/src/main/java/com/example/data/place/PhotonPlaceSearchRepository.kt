package com.example.data.place

import com.example.data.db.RecentPlaceDao
import com.example.data.db.RecentPlaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Photon (https://photon.komoot.io) place-search repository.
 *
 * Uses the project's existing OkHttp + org.json conventions, builds requests
 * with typed query parameters (never string-concatenated user input), and keeps
 * the base URL configurable. The public Photon service is a community endpoint:
 * bounded `limit`, debouncing upstream, cancellation and no automatic retries are
 * deliberate usage constraints of this client.
 */
class PhotonPlaceSearchRepository(
    private val recentPlaceDao: RecentPlaceDao? = null,
    private val baseUrl: String = DEFAULT_PHOTON_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val searchLanguage: String = "en",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PlaceSearchRepository {

    override suspend fun searchPlaces(
        query: String,
        bias: SearchLocationBias?,
        limit: Int
    ): PlaceSearchOutcome = withContext(ioDispatcher) {
        val trimmed = query.trim()
        if (trimmed.length < PlaceSearchDefaults.MIN_QUERY_LENGTH || limit <= 0) {
            return@withContext PlaceSearchOutcome.Empty
        }

        val url = buildSearchQueryUrl(baseUrl, trimmed, limit, searchLanguage, bias)
        if (url == null) {
            return@withContext PlaceSearchOutcome.Failure(PlaceSearchFailure.SERVER_ERROR)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", com.example.data.map.TileMath.OSM_USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    android.util.Log.w(TAG, "photon rate-limited (HTTP 429)")
                    return@withContext PlaceSearchOutcome.Failure(PlaceSearchFailure.RATE_LIMITED)
                }
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "photon HTTP ${response.code}")
                    return@withContext PlaceSearchOutcome.Failure(PlaceSearchFailure.SERVER_ERROR)
                }
                val bodyString = response.body?.string()
                if (bodyString.isNullOrBlank()) {
                    return@withContext PlaceSearchOutcome.Failure(PlaceSearchFailure.MALFORMED_RESPONSE)
                }
                val features = PhotonPlaceParser.parseFeatureCollection(bodyString)
                    ?: return@withContext PlaceSearchOutcome.Failure(PlaceSearchFailure.MALFORMED_RESPONSE)
                if (features.isEmpty()) {
                    return@withContext PlaceSearchOutcome.Empty
                }
                val ranked = PlaceSearchRanking.rankAndDedup(features, trimmed, bias, limit)
                PlaceSearchOutcome.Success(ranked)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            android.util.Log.w(TAG, "photon DNS failure: ${e.message}")
            PlaceSearchOutcome.Failure(PlaceSearchFailure.NETWORK_UNAVAILABLE)
        } catch (e: IOException) {
            android.util.Log.w(TAG, "photon IO failure: ${e.javaClass.simpleName}: ${e.message}")
            PlaceSearchOutcome.Failure(PlaceSearchFailure.NETWORK_UNAVAILABLE)
        }
    }

    override suspend fun recentPlaces(limit: Int): List<SelectedPlace> {
        val dao = recentPlaceDao ?: return emptyList()
        return withContext(ioDispatcher) {
            dao.recentPlaces(limit.coerceAtLeast(0))
        }.mapNotNull { it.toSelectedPlace() }
    }

    override suspend fun recordRecentPlace(place: SelectedPlace) {
        val dao = recentPlaceDao ?: return
        if (!PhotonPlaceParser.isValidCoordinate(place.latitude, place.longitude)) return
        if (place.name.isBlank()) return
        withContext(ioDispatcher) {
            val existing = dao.get(place.id)
            dao.upsert(
                RecentPlaceEntity(
                    id = place.id,
                    name = place.name,
                    address = place.address,
                    lat = place.latitude,
                    lon = place.longitude,
                    source = place.source.name,
                    category = place.category,
                    lastUsedAtMs = System.currentTimeMillis(),
                    useCount = (existing?.useCount ?: 0) + 1
                )
            )
            dao.pruneToBound(PlaceSearchDefaults.RECENT_PLACE_BOUND)
        }
    }

    private fun RecentPlaceEntity.toSelectedPlace(): SelectedPlace? {
        val source = try {
            PlaceSource.valueOf(source)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!PhotonPlaceParser.isValidCoordinate(lat, lon)) return null
        if (name.isBlank()) return null
        return SelectedPlace(
            id = id,
            name = name,
            address = address,
            latitude = lat,
            longitude = lon,
            source = source,
            category = category
        )
    }

    companion object {
        private const val TAG = "PhotonSearch"

        /** Public Photon service endpoint (HTTPS, no API key required). */
        const val DEFAULT_PHOTON_BASE_URL = "https://photon.komoot.io/api/"

        /** Photon accepts `limit` up to 50; we cap lower for responsible usage. */
        const val MAX_HTTP_LIMIT = 15

        /**
         * Builds the typed search URL. Photon's location-bias parameters are
         * **`lat`/`lon`** — the Nominatim-style `ll` parameter is NOT part of the
         * Photon API and this public instance rejects such requests with HTTP 400
         * (mapped earlier to "service temporarily unavailable").
         * Package-visible for unit-test URL assertions.
         */
        internal fun buildSearchQueryUrl(
            baseUrl: String,
            query: String,
            limit: Int,
            language: String,
            bias: SearchLocationBias?
        ): okhttp3.HttpUrl? = try {
            baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.coerceIn(1, MAX_HTTP_LIMIT).toString())
                .addQueryParameter("lang", language)
                .apply {
                    if (bias != null) {
                        addQueryParameter("lat", String.format(Locale.US, "%f", bias.latitude))
                        addQueryParameter("lon", String.format(Locale.US, "%f", bias.longitude))
                    }
                }
                .build()
        } catch (_: IllegalArgumentException) {
            null
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
