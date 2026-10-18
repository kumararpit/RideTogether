package com.example

import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import com.example.data.place.PlaceSearchDefaults
import com.example.data.place.PlaceSearchFailure
import com.example.data.place.PlaceSearchOutcome
import com.example.data.place.PlaceSearchEngine
import com.example.data.place.PlaceSearchRepository
import com.example.data.place.PlaceSearchUiState
import com.example.data.place.PlaceSource
import com.example.data.place.SearchLocationBias
import com.example.data.place.SelectedPlace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic coroutine tests for debounced, supersede-safe place search and
 * selection integrity. Uses a fake repository; no live Photon/OSRM access.
 */
class PlaceSearchEngineTest {

    private companion object {
        val PUNE = SelectedPlace(
            id = "osm:R:10", name = "Pune", address = "Maharashtra, India",
            latitude = 18.5204, longitude = 73.8567, source = PlaceSource.PHOTON
        )
        val LONAVALA = SelectedPlace(
            id = "osm:R:11", name = "Lonavala", address = "Maharashtra, India",
            latitude = 18.7546, longitude = 73.4062, source = PlaceSource.PHOTON
        )
    }

    /** Fake repository with call recording and a fully programmable outcome. */
    private class FakePlaceSearchRepository(
        var recents: List<SelectedPlace> = emptyList(),
        var searchOutcome: suspend (query: String, limit: Int) -> PlaceSearchOutcome = { _, _ -> PlaceSearchOutcome.Empty },
        /** Simulate a slow/late response that ignores cancellation (worst case). */
        var delaySearchMs: Long = 0
    ) : PlaceSearchRepository {
        val searchCalls = mutableListOf<Pair<String, SearchLocationBias?>>()
        val searchLimits = mutableListOf<Int>()
        var searchCallGate: CompletableDeferred<Unit>? = null
        val recordedRecents = mutableListOf<SelectedPlace>()
        var biasCalls = 0

        override suspend fun searchPlaces(query: String, bias: SearchLocationBias?, limit: Int): PlaceSearchOutcome =
            withContext(NonCancellable) {
                // NonCancellable: emulates a realistic late HTTP response that has
                // already left the network layer; the engine's sequence guard must
                // still prevent this stale outcome from overwriting newer state.
                searchCalls.add(query to bias)
                searchLimits.add(limit)
                if (delaySearchMs > 0) kotlinx.coroutines.delay(delaySearchMs)
                searchCallGate?.await()
                searchOutcome(query, limit)
            }

        override suspend fun recentPlaces(limit: Int): List<SelectedPlace> = recents

        override suspend fun recordRecentPlace(place: SelectedPlace) {
            recordedRecents.add(place)
        }
    }

    private val anyBias = SearchLocationBias(18.5204, 73.8567, 1L, 10f)

    // ------------------------------------------------------------------
    // Query handling
    // ------------------------------------------------------------------

    @Test
    fun `blank query produces no network request`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Success(listOf(PUNE)) })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onQueryChanged("")
        searchEngine.onQueryChanged("   ")
        advanceUntilIdle()

        assertTrue(repo.searchCalls.isEmpty())
        assertTrue(searchEngine.state.value is PlaceSearchUiState.Idle)
    }

    @Test
    fun `queries below two characters use recents only and never hit the network`() = runTest {
        val repo = FakePlaceSearchRepository(recents = listOf(PUNE))
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onQueryChanged("Pune ") // 5 chars incl. trailing space — network path
        advanceTimeBy(PlaceSearchDefaults.DEBOUNCE_MILLIS + 1)
        advanceUntilIdle()

        searchEngine.onQueryChanged("S")     // below minimum — recents path only
        advanceUntilIdle()

        // Only the ≥2-char query reached the network; the 1-char one did not.
        assertNull("1-char query never hit the network", repo.searchCalls.firstOrNull { it.first == "S" })
        assertEquals(1, repo.searchCalls.size)
        val last = searchEngine.state.value
        assertTrue("Short query yields recent places", last is PlaceSearchUiState.Results && last.fromRecents)
        assertEquals(listOf(PUNE), (last as PlaceSearchUiState.Results).suggestions)
    }

    @Test
    fun `search is debounced and no request goes out before the debounce window`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Empty })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onQueryChanged("shaniwar wada")
        advanceTimeBy(PlaceSearchDefaults.DEBOUNCE_MILLIS - 1)
        assertEquals("No request before debounce elapses", 0, repo.searchCalls.size)
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals("Request goes out after debounce", 1, repo.searchCalls.size)
    }

    @Test
    fun `a newer query supersedes an older one and old responses can never overwrite it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakePlaceSearchRepository(
            searchOutcome = { q, _ ->
                if (q == "mumbai") {
                    // Simulated very slow first response that ignores cancellation.
                    gate.await()
                    PlaceSearchOutcome.Success(listOf(LONAVALA))
                } else {
                    PlaceSearchOutcome.Success(listOf(PUNE))
                }
            }
        )
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        // Old, slow query:
        searchEngine.onQueryChanged("mumbai")
        advanceTimeBy(PlaceSearchDefaults.DEBOUNCE_MILLIS + 1)
        assertEquals(1, repo.searchCalls.size)

        // User keeps typing (new, fast query):
        searchEngine.onQueryChanged("shaniwar wada pune")
        advanceTimeBy(PlaceSearchDefaults.DEBOUNCE_MILLIS)
        advanceUntilIdle()
        assertEquals("Latest query resolved", PlaceSearchUiState.Results::class, searchEngine.state.value::class)
        assertEquals(PUNE, (searchEngine.state.value as PlaceSearchUiState.Results).suggestions.first())

        // The stale response finally arrives — it must NOT overwrite the latest query.
        gate.complete(Unit)
        advanceUntilIdle()
        val after = searchEngine.state.value
        assertTrue(after is PlaceSearchUiState.Results)
        assertEquals("shaniwar wada pune", (after as PlaceSearchUiState.Results).query)
    }

    @Test
    fun `network failure is distinguishable from empty results and retry re-issues the query`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Failure(PlaceSearchFailure.NETWORK_UNAVAILABLE) })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onQueryChanged("lonavala")
        advanceUntilIdle()
        val failure = searchEngine.state.value
        assertTrue(failure is PlaceSearchUiState.Failure)
        assertEquals("lonavala", (failure as PlaceSearchUiState.Failure).query)
        val callsAfterFailure = repo.searchCalls.size

        searchEngine.retry()
        advanceUntilIdle()
        assertEquals("Retry re-issued the same query", callsAfterFailure + 1, repo.searchCalls.size)
        assertEquals("lonavala", repo.searchCalls.last().first)
    }

    @Test
    fun `empty outcome maps to explicit no-results state`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Empty })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })
        searchEngine.onQueryChanged("zzzunknownzzz")
        advanceUntilIdle()
        assertTrue(
            searchEngine.state.value is PlaceSearchUiState.NoResults &&
                (searchEngine.state.value as PlaceSearchUiState.NoResults).query == "zzzunknownzzz"
        )
    }

    @Test
    fun `result limit and bias are forwarded to the repository`() = runTest {
        val repo = FakePlaceSearchRepository(
            searchOutcome = { _, limit ->
                PlaceSearchOutcome.Success((1..limit).map { idx ->
                    SelectedPlace("osm:R:$idx", "Place $idx", null, 18.0 + idx, 73.0, PlaceSource.PHOTON)
                })
            }
        )
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { anyBias })
        searchEngine.onQueryChanged("place")
        advanceUntilIdle()

        assertEquals(PlaceSearchDefaults.RESULT_LIMIT, repo.searchLimits.first())
        assertNotNull(repo.searchCalls.first().second)
    }

    @Test
    fun `missing GPS still searches globally without bias`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Success(listOf(PUNE)) })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })
        searchEngine.onQueryChanged("goa")
        advanceUntilIdle()
        assertEquals("Search proceeds with no fabricated location", 1, repo.searchCalls.size)
        assertNull("No (0,0) placeholder bias", repo.searchCalls.first().second)
        assertTrue(searchEngine.state.value is PlaceSearchUiState.Results)
    }

    // ------------------------------------------------------------------
    // Selection integrity
    // ------------------------------------------------------------------

    @Test
    fun `selecting a result stores the exact place and coordinates`() = runTest {
        val repo = FakePlaceSearchRepository()
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onPlaceSelected(LONAVALA)
        val state = searchEngine.state.value
        assertTrue(state is PlaceSearchUiState.Idle)
        assertEquals("Lonavala", state.query)
        assertEquals(LONAVALA, state.selectedPlace)
        // Structured coordinates survive selection — never re-geocoded from name.
        assertEquals(73.4062, state.selectedPlace!!.longitude, 1e-12)
        assertEquals(18.7546, state.selectedPlace!!.latitude, 1e-12)
    }

    @Test
    fun `editing the selected query invalidates the selection until a new one is made`() = runTest {
        val repo = FakePlaceSearchRepository(searchOutcome = { _, _ -> PlaceSearchOutcome.Empty })
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.onPlaceSelected(LONAVALA)
        assertEquals(LONAVALA, searchEngine.selectedPlace)

        searchEngine.onQueryChanged("Lonavala Hill")
        val afterEdit = searchEngine.state.value
        assertEquals("Lonavala Hill", afterEdit.query)
        assertNull("Edited text must not keep the old selection", afterEdit.selectedPlace)
        assertNull(searchEngine.selectedPlace)

        // Retyping the exact selection name re-shows it, but a partial edit keeps it cleared.
        searchEngine.onQueryChanged("LONAVALA ")
        assertNull("Different text keeps selection cleared", searchEngine.selectedPlace)
    }

    @Test
    fun `selected places are recorded as recents but location fixes are not`() = runTest {
        val repo = FakePlaceSearchRepository()
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })
        val gps = PUNE.copy(source = PlaceSource.CURRENT_LOCATION, name = "Current GPS Location")

        searchEngine.onPlaceSelected(PUNE)
        searchEngine.onPlaceSelected(gps)
        advanceUntilIdle() // selection persistence is async on the field scope
        assertEquals(listOf(PUNE), repo.recordedRecents)
    }

    @Test
    fun `replaceState swaps selection atomically only when the name still matches`() = runTest {
        val repo = FakePlaceSearchRepository()
        val searchEngine = PlaceSearchEngine(repo, scope = this, locationBiasProvider = { null })

        searchEngine.replaceState("Lonavala", LONAVALA)
        assertEquals(LONAVALA, searchEngine.selectedPlace)

        searchEngine.replaceState("Wai", LONAVALA) // swapped text no longer matches
        assertNull(searchEngine.selectedPlace)
        assertEquals("Wai", searchEngine.state.value.query)
    }
}
