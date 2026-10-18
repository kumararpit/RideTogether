package com.example.data.place

/**
 * Typed place-search result: callers must distinguish an empty result set from
 * a failure instead of treating every problem as "no results".
 */
sealed class PlaceSearchOutcome {
    /** A valid response with at least one ranked result. */
    data class Success(val results: List<SelectedPlace>, val fromRecents: Boolean = false) : PlaceSearchOutcome()

    /** A valid response with zero matching features. */
    object Empty : PlaceSearchOutcome()

    /** A recoverable failure; [kind] drives the UI state and message. */
    data class Failure(val kind: PlaceSearchFailure) : PlaceSearchOutcome()
}

enum class PlaceSearchFailure {
    /** No connectivity / DNS / timeout while contacting Photon. */
    NETWORK_UNAVAILABLE,

    /** HTTP 429 or explicit rate limiting. */
    RATE_LIMITED,

    /** Any other unsuccessful HTTP status. */
    SERVER_ERROR,

    /** Unparseable or structurally invalid GeoJSON payload. */
    MALFORMED_RESPONSE
}

/** Contract for place search and recent-place persistence. */
interface PlaceSearchRepository {
    suspend fun searchPlaces(
        query: String,
        bias: SearchLocationBias? = null,
        limit: Int = PlaceSearchDefaults.RESULT_LIMIT
    ): PlaceSearchOutcome

    /** Most recently used places, newest first (for reuse and offline quick-pick). */
    suspend fun recentPlaces(limit: Int = PlaceSearchDefaults.RECENT_PLACE_BOUND): List<SelectedPlace>

    /** Records a user-confirmed place selection for future reuse. */
    suspend fun recordRecentPlace(place: SelectedPlace)
}

/** Shared defaults documented as named constants (no magic numbers in logic). */
object PlaceSearchDefaults {
    /** Debounce for free-text autocomplete typing (spec: ~300 ms). */
    const val DEBOUNCE_MILLIS = 300L

    /** Minimum character count before network autocomplete is issued. */
    const val MIN_QUERY_LENGTH = 2

    /** Number of suggestions requested from and presented by Photon. */
    const val RESULT_LIMIT = 8

    /** Hard cap on stored recent places (bounded cache). */
    const val RECENT_PLACE_BOUND = 50
}
