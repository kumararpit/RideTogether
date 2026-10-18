package com.example.data.place

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Explicit search state for one field (start or destination). The engine owns
 * three distinct concerns so the UI can render them exactly:
 *  - the editable query text,
 *  - the current suggestions (network results or recent places),
 *  - the selected structured place whose coordinates feed route calculation.
 */
sealed interface PlaceSearchUiState {

    /** Current editable text (mirrored by the text field). */
    val query: String

    /**
     * The selected structured place for this field. Non-null only while the
     * displayed text still refers exactly to that selected place — editing the
     * text invalidates it, which is the core selection-integrity guarantee.
     */
    val selectedPlace: SelectedPlace?

    /** Nothing typed, no active suggestions, nothing selected. */
    data class Idle(
        override val query: String = "",
        override val selectedPlace: SelectedPlace? = null
    ) : PlaceSearchUiState

    /** A network request for [query] is in flight. */
    data class Loading(
        override val query: String,
        override val selectedPlace: SelectedPlace? = null
    ) : PlaceSearchUiState

    /** Suggestions ready. [fromRecents] marks offline/recent reuse explicitly. */
    data class Results(
        override val query: String,
        val suggestions: List<SelectedPlace>,
        val fromRecents: Boolean,
        override val selectedPlace: SelectedPlace? = null
    ) : PlaceSearchUiState

    /** A resolved query found no matching places. */
    data class NoResults(
        override val query: String,
        override val selectedPlace: SelectedPlace? = null
    ) : PlaceSearchUiState

    /** Search service failed; [failure] distinguishes offline / rate-limit / server / parse. */
    data class Failure(
        override val query: String,
        val failure: PlaceSearchFailure,
        override val selectedPlace: SelectedPlace? = null
    ) : PlaceSearchUiState
}

/**
 * Drives debounced, supersede-on-new-query place search for one UI field.
 * Pure Kotlin (StateFlow + injected CoroutineScope) so behavior is unit-testable
 * with virtual time: no Android dependencies, no UI coupling.
 */
class PlaceSearchEngine(
    private val repository: PlaceSearchRepository,
    private val scope: CoroutineScope,
    private val locationBiasProvider: () -> SearchLocationBias? = { null },
    private val debounceMillis: Long = PlaceSearchDefaults.DEBOUNCE_MILLIS,
    private val minQueryLength: Int = PlaceSearchDefaults.MIN_QUERY_LENGTH,
    private val resultLimit: Int = PlaceSearchDefaults.RESULT_LIMIT
) {

    private val _state = MutableStateFlow<PlaceSearchUiState>(PlaceSearchUiState.Idle())
    val state: StateFlow<PlaceSearchUiState> = _state.asStateFlow()

    private var searchSequence = 0
    private var searchJob: Job? = null

    /** The selected place for this field, or null while the text is unconfirmed. */
    val selectedPlace: SelectedPlace?
        get() = _state.value.selectedPlace

    /**
     * User typed. Cancels any in-flight work and either requests recent places
     * (queries below [minQueryLength]) or schedules a debounced network search.
     * The previously selected place stays valid only if the text still equals its
     * exact display name.
     */
    fun onQueryChanged(text: String) {
        searchJob?.cancel()
        val seq = ++searchSequence
        val retainedSelection = retainedSelectionFor(text)

        when {
            text.isBlank() -> {
                _state.value = PlaceSearchUiState.Idle(text, null)
            }
            text.trim().length < minQueryLength -> {
                // No network for short queries; surface recent selections instead.
                _state.value = PlaceSearchUiState.Idle(text, retainedSelection)
                searchJob = scope.launch {
                    val recents = repository.recentPlaces(resultLimit)
                    if (seq != searchSequence) return@launch
                    if (recents.isEmpty()) {
                        _state.value = PlaceSearchUiState.Idle(text, retainedSelectionFor(text))
                    } else {
                        _state.value = PlaceSearchUiState.Results(
                            query = text,
                            suggestions = recents,
                            fromRecents = true,
                            selectedPlace = retainedSelectionFor(text)
                        )
                    }
                }
            }
            else -> {
                _state.value = PlaceSearchUiState.Loading(text, retainedSelection)
                searchJob = scope.launch {
                    delay(debounceMillis)
                    runSearch(text, seq)
                }
            }
        }
    }

    /** Manual retry after a recoverable failure (no automatic retry loops). */
    fun retry() {
        val current = _state.value
        if (current is PlaceSearchUiState.Failure) {
            onQueryChanged(current.query)
        }
    }

    /**
     * The user explicitly chose a place. Its exact coordinates become the field's
     * routing coordinates; the input is populated with the selection's display
     * name and suggestions are dismissed.
     */
    fun onPlaceSelected(place: SelectedPlace) {
        searchJob?.cancel()
        ++searchSequence
        _state.value = PlaceSearchUiState.Idle(place.name, place)
        if (place.source != PlaceSource.CURRENT_LOCATION) {
            scope.launch { repository.recordRecentPlace(place) }
        }
    }

    /** Clears the field entirely (text, suggestions, selection). */
    fun clearQuery() {
        searchJob?.cancel()
        ++searchSequence
        _state.value = PlaceSearchUiState.Idle("", null)
    }

    /**
     * Replaces the full field state in one atomic step (used for the swap
     * start/destination action and legacy initial values).
     */
    fun replaceState(query: String, place: SelectedPlace?) {
        searchJob?.cancel()
        ++searchSequence
        val retained = place?.takeIf { it.name == query }
        _state.value = PlaceSearchUiState.Idle(query, retained)
    }

    private fun retainedSelectionFor(text: String): SelectedPlace? {
        val selected = _state.value.selectedPlace ?: return null
        // Selection remains valid only while the field still displays this place.
        return selected.takeIf { text == selected.name }
    }

    private suspend fun runSearch(text: String, seq: Int) {
        // Location bias: valid-fresh-fix-only; missing GPS must not prevent a
        // global search, and "0,0" is never fabricated from a missing fix.
        val bias = locationBiasProvider()
        val outcome = repository.searchPlaces(text, bias, resultLimit)
        if (seq != searchSequence) return // a newer query superseded this one

        val retained = retainedSelectionFor(text)
        _state.value = when (outcome) {
            is PlaceSearchOutcome.Success ->
                if (outcome.results.isEmpty()) {
                    PlaceSearchUiState.NoResults(text, retained)
                } else {
                    PlaceSearchUiState.Results(text, outcome.results, fromRecents = false, retained)
                }
            is PlaceSearchOutcome.Empty -> PlaceSearchUiState.NoResults(text, retained)
            is PlaceSearchOutcome.Failure -> PlaceSearchUiState.Failure(text, outcome.kind, retained)
        }
    }

    /** Test/monitoring hook: monotonic sequence used for stale-response guarding. */
    fun startedSearchCount(): Int = searchSequence
}
