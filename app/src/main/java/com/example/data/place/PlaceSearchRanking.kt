package com.example.data.place

import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import java.util.Locale

/**
 * Optional, validated location-bias hint for a Photon query.
 *
 * Bias is a relevance hint, never a geographic restriction: Photon may return
 * results outside the bias area and callers must not suppress them. Confirmation
 * rules prevent fabricated positions (e.g. (0,0)) and stale/inaccurate fixes from
 * skewing ranking.
 */
data class SearchLocationBias(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val accuracyMeters: Float
) {
    companion object {
        const val MAX_BIAS_AGE_MS = 30_000L
        const val MAX_BIAS_ACCURACY_METERS = 50f

        /**
         * Builds a bias from a GPS fix, or returns null when the fix is missing,
         * inaccurate, stale, out of bounds, or a placeholder (0,0). Never blocks:
         * a null bias simply means global unbiased search.
         */
        fun from(
            update: LocationUpdate?,
            maxAgeMs: Long = MAX_BIAS_AGE_MS,
            maxAccuracyMeters: Float = MAX_BIAS_ACCURACY_METERS,
            nowMs: Long = System.currentTimeMillis()
        ): SearchLocationBias? {
            if (update == null) return null
            val lat = update.latLng.latitude
            val lon = update.latLng.longitude
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (lat == 0.0 && lon == 0.0) return null
            if (!(update.accuracyMeters.isFinite()) || update.accuracyMeters !in 0f..maxAccuracyMeters) return null
            if (update.timestampMs > 0L && (nowMs - update.timestampMs) > maxAgeMs) return null
            return SearchLocationBias(lat, lon, update.timestampMs, update.accuracyMeters)
        }
    }
}

/**
 * Conservative local ranking of provider results. Relevance tier is chosen first
 * so a strong textual match always outranks any weaker match regardless of its
 * distance; proximity is only a tie-breaker inside a tier. Comparisons are stable
 * (Kotlin's sort is stable), preserving provider order whenever no justified
 * reason to reorder exists.
 */
object PlaceSearchRanking {

    fun normalize(text: String): String =
        text.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    /**
     * Textual relevance ladder (Maps-like, strongest first):
     *  0 whole normalized name equals the query
     *  1 the name starts with the query (covers "lond" → "London Eye")
     *  2 a NON-leading name token starts with the query (covers "lon" → "East
     *    London"; leading tokens are already covered by tier 1 via the name
     *    string itself)
     *  3 the query occurs inside the name mid-word ("lon" → "Medlones")
     *  4 the query only matches the address/locality line
     *  5 no textual match — provider relevance order
     */
    fun relevanceTier(place: SelectedPlace, query: String): Int {
        val q = normalize(query)
        val name = normalize(place.name)
        val nameTokens = name.split(' ')
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            nameTokens.drop(1).any { it.startsWith(q) } -> 2
            name.contains(q) -> 3
            place.address != null && normalize(place.address).contains(q) -> 4
            else -> 5
        }.let { if (q.isEmpty()) TIER_NO_TEXTUAL_MATCH else it }
    }

    /** Tier returned for an empty query: pure provider relevance order. */
    const val TIER_NO_TEXTUAL_MATCH = 5

    /**
     * Collapse radius for identical-name near-duplicates. Live provider evidence
     * (Photon `q="lonavala"`): five features share the name "Lonavala" — the
     * station/platform/stop transit cluster spreads 0.024–0.267 km apart and all
     * render as separate identical rows, while the locality feature 0.49 km away
     * is a genuinely distinct place. 0.3 km collapses the whole observed transit
     * cluster and keeps the locality.
     */
    const val NEAR_DUPLICATE_COLLAPSE_KM = 0.3

    /**
     * Removes duplicates by stable id, by normalized-name+rounded-coordinate
     * pairs, and by identical-name near-duplicates inside
     * [NEAR_DUPLICATE_COLLAPSE_KM] (later copies dropped; the first occurrence
     * is the provider's relevance leader). Then ranks and applies the limit.
     */
    fun rankAndDedup(
        places: List<SelectedPlace>,
        query: String,
        bias: SearchLocationBias?,
        limit: Int
    ): List<SelectedPlace> {
        if (limit <= 0) return emptyList()
        val seenIds = HashSet<String>(places.size * 2)
        val seenCoordKeys = HashSet<String>(places.size * 2)
        val seenNormalizedNames = HashMap<String, LatLng>(places.size * 2)
        val unique = ArrayList<SelectedPlace>(minOf(places.size, limit))
        for (place in places) {
            if (!seenIds.add(place.id)) continue
            val coordKey = "${normalize(place.name)}|${String.format(Locale.US, "%.5f", place.latitude)}," +
                String.format(Locale.US, "%.5f", place.longitude)
            if (!seenCoordKeys.add(coordKey)) continue
            val normalizedName = normalize(place.name)
            val here = LatLng(place.latitude, place.longitude)
            val prior = seenNormalizedNames[normalizedName]
            if (prior != null && here.distanceTo(prior) < NEAR_DUPLICATE_COLLAPSE_KM) continue
            seenNormalizedNames[normalizedName] = here
            unique.add(place)
        }

        val sorted = unique.sortedWith(
            compareBy(
                { relevanceTier(it, query) },
                { distanceMeters(it, bias) }
            )
        )
        return sorted.take(limit)
    }

    private fun distanceMeters(place: SelectedPlace, bias: SearchLocationBias?): Double {
        if (bias == null) return Double.MAX_VALUE
        return LatLng(place.latitude, place.longitude)
            .distanceTo(LatLng(bias.latitude, bias.longitude)) * 1000.0
    }
}
