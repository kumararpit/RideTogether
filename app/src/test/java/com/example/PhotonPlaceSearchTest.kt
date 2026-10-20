package com.example

import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import com.example.data.place.PlaceSearchRanking
import com.example.data.place.PhotonPlaceParser
import com.example.data.place.SearchLocationBias
import com.example.data.place.SelectedPlace
import com.example.data.place.PlaceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure (deterministic, offline) tests for the Photon GeoJSON parser, local
 * ranking/dedup, address formatting, stable id derivation and location-bias
 * validation. No live network is used anywhere in this suite. Runs under
 * Robolectric so org.json (Android JSON) behaves like on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotonPlaceSearchTest {

    private companion object {
        const val PUNE_LAT = 18.5196
        const val PUNE_LON = 73.8541
    }

    private fun featureJson(
        name: String?,
        lon: Double? = PUNE_LON,
        lat: Double? = PUNE_LAT,
        props: Map<String, Any?> = emptyMap()
    ): String {
        val propsJson = buildString {
            append("{")
            val pairs = buildList {
                if (name != null) add("\"name\":\"$name\"")
                for ((k, v) in props) {
                    when (v) {
                        null -> add("\"$k\":null")
                        is Long -> add("\"$k\":$v")
                        is Number -> add("\"$k\":$v")
                        is String -> add("\"$k\":\"$v\"")
                    }
                }
            }
            append(pairs.joinToString(","))
            append("}")
        }
        val coordsJson = when {
            lon == null && lat == null -> "null"
            else -> "[${lon},${lat}]"
        }
        return """
        {
          "geometry": { "type": "Point", "coordinates": $coordsJson },
          "properties": $propsJson
        }
        """.trimIndent()
    }

    private fun featureCollectionBody(vararg features: String): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun place(name: String, lat: Double, lon: Double, address: String? = null, id: String = "id-$name"):
            SelectedPlace = SelectedPlace(id, name, address, lat, lon, PlaceSource.PHOTON)

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    fun `full feature parses with converted coordinates and metadata`() {
        val body = featureCollectionBody(
            featureJson(
                "Shaniwar Wada",
                props = mapOf(
                    "city" to "Pune",
                    "state" to "Maharashtra",
                    "country" to "India",
                    "osm_key" to "historic",
                    "osm_value" to "fortress",
                    "osm_type" to "W",
                    "osm_id" to 123456L
                )
            )
        )

        val places = PhotonPlaceParser.parseFeatureCollection(body)
        assertEquals(1, places?.size)
        val place = places!!.first()

        // GeoJSON coordinates are [lon, lat]; app model is (lat, lon).
        assertEquals(PUNE_LAT, place.latitude, 1e-6)
        assertEquals(PUNE_LON, place.longitude, 1e-6)
        assertEquals("Shaniwar Wada", place.name)
        assertEquals(PlaceSource.PHOTON, place.source)
        assertEquals("fortress", place.category)
        assertEquals("osm:W:123456", place.id)
        assertEquals("Pune, Maharashtra, India", place.address)
    }

    @Test
    fun `feature without optional address fields still parses`() {
        val body = featureCollectionBody(featureJson("Lavasa"))
        val place = PhotonPlaceParser.parseFeatureCollection(body)!!.first()
        assertEquals("Lavasa", place.name)
        assertNull(place.address)
        assertTrue(place.id.startsWith("place:lavasa:18.51960:73.85410"))
    }

    @Test
    fun `feature with only a street degrades to housenumber street label`() {
        val body = featureCollectionBody(
            featureJson(
                null,
                props = mapOf("housenumber" to "14", "street" to "MG Road")
            )
        )
        val place = PhotonPlaceParser.parseFeatureCollection(body)!!.first()
        assertEquals("14 MG Road", place.name)
        assertNull("Street is the label itself; no duplicated address", place.address)
    }

    @Test
    fun `feature with no name is rejected`() {
        val body = featureCollectionBody(featureJson(null))
        val places = PhotonPlaceParser.parseFeatureCollection(body)
        assertTrue(places!!.isEmpty())
    }

    @Test
    fun `missing coordinates are rejected`() {
        val body = featureCollectionBody(featureJson("NoWhere Hotel", lon = null, lat = null))
        assertTrue(PhotonPlaceParser.parseFeatureCollection(body)!!.isEmpty())
    }

    @Test
    fun `invalid coordinates are rejected without crashing`() {
        val beyondLat = featureCollectionBody(featureJson("Pole", lon = 10.0, lat = 91.0))
        val beyondLng = featureCollectionBody(featureJson("Dateline", lon = 190.0, lat = 10.0))
        val nanLat = featureCollectionBody(featureJson("NaN", lon = 10.0, lat = null))
        assertTrue(PhotonPlaceParser.parseFeatureCollection(beyondLat)!!.isEmpty())
        assertTrue(PhotonPlaceParser.parseFeatureCollection(beyondLng)!!.isEmpty())
        assertTrue(PhotonPlaceParser.parseFeatureCollection(nanLat)!!.isEmpty())
    }

    @Test
    fun `malformed bodies return null not empty`() {
        assertNull(PhotonPlaceParser.parseFeatureCollection("plain string, not json"))
        assertNull(PhotonPlaceParser.parseFeatureCollection("{\"features\": \"oops\"}"))
        assertNull(PhotonPlaceParser.parseFeatureCollection("{\"type\":\"FeatureCollection\"}"))
        assertEquals(emptyList<SelectedPlace>(), PhotonPlaceParser.parseFeatureCollection("{\"type\":\"FeatureCollection\",\"features\":[]}"))
    }

    @Test
    fun `address formatting never duplicates components or the name`() {
        val props = PhotonPlaceParser.parseProperties(
            org.json.JSONObject(
                """{"name":"Shaniwar Wada","city":"Pune","district":"Pune","state":"Maharashtra","country":"India"}"""
            )
        )
        val address = PhotonPlaceParser.formatAddress(props, "Shaniwar Wada")
        assertEquals("Pune, Maharashtra, India", address)
    }

    // ------------------------------------------------------------------
    // Dedup + ranking
    // ------------------------------------------------------------------

    @Test
    fun `duplicates are eliminated by stable id and by name plus coordinates`() {
        val a = place("Shaniwar Wada", 18.5196, 73.8541, id = "osm:W:1")
        val sameId = place("Shaniwar Wada", 18.5197, 73.8542, id = "osm:W:1") // same id (OSR ignored)
        val sameNameAndRoundedCoords = place("Shaniwar Wada", 18.519603, 73.854103, id = "place:other")
        val distinct = place("Lavasa", 18.4088, 73.5076, id = "osm:R:2")

        val ranked = PlaceSearchRanking.rankAndDedup(
            listOf(a, sameId, sameNameAndRoundedCoords, distinct), "wada", null, 8
        )
        assertEquals(2, ranked.size)
        assertTrue(ranked.any { it.id == "osm:W:1" })
        assertTrue(ranked.any { it.id == "osm:R:2" })
    }

    @Test
    fun `exact name match outranks a nearer weaker match`() {
        val exactFar = place("Kokoda Track", -9.0, 147.0)                       // exact, across the world
        val weakNear = place("Cafe Near Kokoda", 18.5201, 73.8551, "near")      // contains, ~20 m
        val weakNear2 = place("Something Kokoda Elsewhere", 18.5202, 73.8552)

        val bias = SearchLocationBias(18.5204, 73.8567, System.currentTimeMillis(), 10f)
        val ranked = PlaceSearchRanking.rankAndDedup(
            listOf(weakNear, weakNear2, exactFar), "kokoda track", bias, 8
        )
        assertEquals("Exact textual match first regardless of distance", exactFar, ranked[0])
    }

    @Test
    fun `relevance tiers are ordered exact prefix contains address other`() {
        val exact = place("Lavasa", 1.0, 1.0)
        val prefix = place("Lavasa City", 1.0, 2.0)
        val contains = place("Greater Lavasa Road", 1.0, 3.0)
        val addressOnly = place("D-Mart", 1.0, 4.0, " Lavasa, Maharashtra")
        val other = place("Unrelated", 1.0, 5.0)

        val ranked = PlaceSearchRanking.rankAndDedup(
            listOf(other, addressOnly, contains, prefix, exact), "lavasa", null, 8
        )
        assertEquals(listOf(exact, prefix, contains, addressOnly, other), ranked)
    }

    @Test
    fun `result limit is enforced`() {
        val many = (1..12).map { place("Place $it", 18.0 + it * 0.001, 73.0) }
        val ranked = PlaceSearchRanking.rankAndDedup(many, "place", null, 8)
        assertEquals(8, ranked.size)
    }

    @Test
    fun `provider ordering is preserved when there is no justified reorder`() {
        // All same tier (contains) and no bias → stable provider order.
        val first = place("Cafe Alpha Kokoda", 1.0, 1.0)
        val second = place("Cafe Beta Kokoda", 1.0, 2.0)
        val third = place("Cafe Gamma Kokoda", 1.0, 3.0)
        val ranked = PlaceSearchRanking.rankAndDedup(listOf(first, second, third), "kokoda", null, 8)
        assertEquals(listOf(first, second, third), ranked)
    }

    // ------------------------------------------------------------------
    // Near-duplicate collapse (live evidence: Photon q="lonavala" returns
    // five identical-name features — station/platform/stop transit cluster
    // spread 0.024–0.267 km, distinct locality ~0.7 km away)
    // ------------------------------------------------------------------

    @Test
    fun `identical-name features inside the collapse radius collapse to the provider leader`() {
        val station = place("Lonavala", 18.7504, 73.4069, id = "osm:N:station")
        val platform = place("Lonavala", 18.7492, 73.4081, id = "osm:N:platform") // 0.183 km
        val stop = place("Lonavala", 18.7488, 73.4088, id = "osm:N:stop") // 0.267 km, 0.024 km from platform
        val town = place("Lonavala", 18.7549, 73.4017, id = "osm:N:town") // ~0.7 km, distinct place

        val ranked = PlaceSearchRanking.rankAndDedup(
            listOf(station, platform, stop, town), "lonavala", null, 8
        )
        assertEquals(2, ranked.size)
        assertEquals("First occurrence is the provider's relevance leader", station, ranked[0])
        assertTrue(ranked.contains(town))
    }

    @Test
    fun `different names at one spot and same name far apart are both kept`() {
        val fuelA = place("Indian Oil Pump", 18.7504, 73.4069, id = "osm:N:1")
        val hotelB = place("Sunrise Hotel", 18.750405, 73.406905, id = "osm:N:2") // ~1 m away, different name
        val sameNameFar = place("Indian Oil Pump", 21.0735, 77.8922, id = "osm:N:3") // ~700 km, same name
        val ranked = PlaceSearchRanking.rankAndDedup(
            listOf(fuelA, hotelB, sameNameFar), "pump", null, 8
        )
        assertEquals(3, ranked.size)
    }

    // ------------------------------------------------------------------
    // Word-initial prefix (Maps-style prefix completion at word boundaries)
    // ------------------------------------------------------------------

    @Test
    fun `relevance tier recognizes word-initial prefixes`() {
        assertEquals(0, PlaceSearchRanking.relevanceTier(place("London", 1.0, 1.0), "london"))
        assertEquals("whole-name prefix still tier 1", 1, PlaceSearchRanking.relevanceTier(place("Lonavala Bazaar", 1.0, 1.0), "lon"))
        assertEquals("word-initial prefix beats mid-word containment", 2, PlaceSearchRanking.relevanceTier(place("East London", 1.0, 1.0), "lon"))
        assertEquals("mid-word substring is lower", 3, PlaceSearchRanking.relevanceTier(place("Medlones", 1.0, 1.0), "lon"))
        assertEquals("word EXACT inside the name stays below a whole-name prefix", 2, PlaceSearchRanking.relevanceTier(place("Greater Lavasa Road", 1.0, 3.0), "lavasa"))
    }

    @Test
    fun `word-initial prefix outranks a mid-word containment match`() {
        // Fail-before: both were tier 2 and provider order (containment first here)
        // would have won. Word-boundary prefixes now rank first like Maps.
        val wordPrefix = place("East London", 1.0, 1.0)
        val midWord = place("Medlones", 1.0, 2.0)
        val ranked = PlaceSearchRanking.rankAndDedup(listOf(midWord, wordPrefix), "lon", null, 8)
        assertEquals(listOf(wordPrefix, midWord), ranked)
    }

    // ------------------------------------------------------------------
    // Location bias validation
    // ------------------------------------------------------------------

    @Test
    fun `valid fresh accurate fix produces bias`() {
        val update = LocationUpdate(
            latLng = LatLng(18.5204, 73.8567),
            speedKmh = 40.0,
            headingDeg = 0f,
            accuracyMeters = 10f,
            timestampMs = 1710000000000L
        )
        val bias = SearchLocationBias.from(update, nowMs = 1710000000000L + 10_000L)
        assertNotNull(bias)
        assertEquals(18.5204, bias!!.latitude, 1e-9)
        assertEquals(73.8567, bias.longitude, 1e-9)
    }

    @Test
    fun `missing stale inaccurate placeholder fixes never produce bias`() {
        assertNull("null fix", SearchLocationBias.from(null))

        val stale = LocationUpdate(LatLng(18.5, 73.8), 40.0, 0f, 10f, 1710000000000L)
        assertNull("stale fix", SearchLocationBias.from(stale, maxAgeMs = 30_000, nowMs = 1710000000000L + 31_000L))

        val inaccurate = LocationUpdate(LatLng(18.5, 73.8), 40.0, 0f, 500f, 1710000000000L)
        assertNull("inaccurate fix", SearchLocationBias.from(inaccurate, nowMs = 1710000000000L))

        val placeholder = LocationUpdate(LatLng(0.0, 0.0), 40.0, 0f, 10f, 1710000000000L)
        assertNull("0,0 placeholder never fabricated as current location", SearchLocationBias.from(placeholder))

        val invalid = LocationUpdate(LatLng(95.0, 210.0), 40.0, 0f, 10f, 1710000000000L)
        assertNull("out of bounds fix", SearchLocationBias.from(invalid))
    }

    // ------------------------------------------------------------------
    // Request URL construction (regression: photon rejects the Nominatim-style
    // `ll` bias parameter with HTTP 400 → "service temporarily unavailable")
    // ------------------------------------------------------------------

    @Test
    fun `biased search URL uses photon lat lon parameters never ll`() {
        val url = com.example.data.place.PhotonPlaceSearchRepository.buildSearchQueryUrl(
            baseUrl = com.example.data.place.PhotonPlaceSearchRepository.DEFAULT_PHOTON_BASE_URL,
            query = "pune",
            limit = 5,
            language = "en",
            bias = SearchLocationBias(
                latitude = 18.5204,
                longitude = 73.8567,
                timestampMs = 0L,
                accuracyMeters = 10f
            )
        )
        assertNotNull(url)
        assertEquals("pune", url!!.queryParameter("q"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals("en", url.queryParameter("lang"))
        assertNotNull("bias must be sent via lat", url.queryParameter("lat"))
        assertNotNull("bias must be sent via lon", url.queryParameter("lon"))
        assertNull("ll is not a Photon parameter (HTTP 400)", url.queryParameter("ll"))
    }

    @Test
    fun `unbiased search URL carries no location parameters`() {
        val url = com.example.data.place.PhotonPlaceSearchRepository.buildSearchQueryUrl(
            baseUrl = com.example.data.place.PhotonPlaceSearchRepository.DEFAULT_PHOTON_BASE_URL,
            query = "pune",
            limit = 5,
            language = "en",
            bias = null
        )
        assertNotNull(url)
        assertNull(url!!.queryParameter("lat"))
        assertNull(url.queryParameter("lon"))
        assertNull(url.queryParameter("ll"))
    }

    @Test
    fun `search URL builder clamps limit and survives malformed base url`() {
        val clamped = com.example.data.place.PhotonPlaceSearchRepository.buildSearchQueryUrl(
            baseUrl = "https://photon.komoot.io/api/",
            query = "a",
            limit = 99,
            language = "en",
            bias = null
        )
        assertEquals("15", clamped!!.queryParameter("limit"))

        val broken = com.example.data.place.PhotonPlaceSearchRepository.buildSearchQueryUrl(
            baseUrl = "not a url",
            query = "a",
            limit = 5,
            language = "en",
            bias = null
        )
        assertNull("malformed base URL must not crash", broken)
    }
}
