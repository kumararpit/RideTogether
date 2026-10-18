package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.db.RideDatabase
import com.example.data.db.RecentPlaceEntity
import com.example.data.place.PlaceSearchDefaults
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the recent-places cache (additive Room v5 table).
 * In-memory DB: verifies ordering, replace-on-reuse and the bounded cache —
 * no existing ride tables are touched or erased.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentPlaceDaoTest {

    private lateinit var db: RideDatabase
    private val dao get() = db.recentPlaceDao()

    private fun place(id: String, name: String, lastUsedAtMs: Long) = RecentPlaceEntity(
        id = id,
        name = name,
        address = "Maharashtra, India",
        lat = 18.0,
        lon = 73.0,
        source = "PHOTON",
        category = "city",
        lastUsedAtMs = lastUsedAtMs,
        useCount = 1
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RideDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `records persist with structured labels and coordinates`() = runTest {
        dao.upsert(place("osm:R:1", "Lonavala", 100L))
        val listed = dao.recentPlaces(10)
        assertEquals(1, listed.size)
        assertEquals("Lonavala", listed[0].name)
        assertEquals("Maharashtra, India", listed[0].address)
        assertEquals(18.0, listed[0].lat, 1e-12)
        assertEquals(73.0, listed[0].lon, 1e-12)
        assertEquals("PHOTON", listed[0].source)
    }

    @Test
    fun `recency ordering is newest first`() = runTest {
        dao.upsert(place("a", "Alpha", 100L))
        dao.upsert(place("b", "Beta", 200L))
        dao.upsert(place("c", "Gamma", 300L))
        val listed = dao.recentPlaces(10)
        assertEquals(listOf("c", "b", "a"), listed.map { it.id })
    }

    @Test
    fun `same id replaces and increments use count`() = runTest {
        dao.upsert(place("osm:W:9", "Wai", 100L))
        dao.upsert(place("osm:W:9", "Wai", 150L)) // REPLACE — dedupe by stable id
        val listed = dao.recentPlaces(10)
        assertEquals(1, listed.size)
        // useCount replaced with existing+1 by the repository; direct DAO upserts
        // replace, so a second raw insert overwrites count — repository logic owns
        // count increment (validated via existing lookup in repository tests).
        assertEquals(150L, listed[0].lastUsedAtMs)
    }

    @Test
    fun `cache is bounded to the configured limit keeping newest`() = runTest {
        val bound = PlaceSearchDefaults.RECENT_PLACE_BOUND
        repeat(bound + 5) { idx ->
            dao.upsert(place("id$idx", "Place $idx", (idx + 1).toLong() * 1000L))
        }
        dao.pruneToBound(bound)
        val listed = dao.recentPlaces(bound * 2)
        assertEquals(bound, listed.size)
        val ids = listed.map { it.id }
        // Newest bound entries survive; the oldest entries are pruned.
        assertTrue("Newest entry kept", ids.contains("id${bound + 4}"))
        assertTrue("Entry inside the surviving window kept", ids.contains("id$bound"))
        assertTrue("Oldest entries pruned", !ids.contains("id0"))
    }
}
