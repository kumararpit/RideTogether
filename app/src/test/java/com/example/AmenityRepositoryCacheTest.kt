package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.amenities.AmenityEntity
import com.example.data.amenities.AmenityRepository
import com.example.data.amenities.AmenityQueryCacheEntity
import com.example.data.amenities.AmenityType
import com.example.data.amenities.OverpassApiService
import com.example.data.amenities.OverpassQueryBuilder
import com.example.data.amenities.OverpassResponse
import com.example.data.db.RideDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

/**
 * Phase 4/7 cache-correctness tests for the amenity pipeline, against an
 * in-memory Room database:
 *  - cached POIs render without any network (cache-first display)
 *  - a failed refresh NEVER poisons the cache
 *  - empty-success responses mark cells fresh without writing rows
 *  - upserts are idempotent and subsequent refreshes stay cache-only
 *  - versioned cell keys and one-time pre-v2 cleanup behave as documented
 */
@RunWith(RobolectricTestRunner::class)
class AmenityRepositoryCacheTest {

    private lateinit var db: RideDatabase
    private lateinit var repo: AmenityRepository
    private lateinit var fakeApiHolder: FakeApi

    private val fuel = AmenityEntity(1L, AmenityType.FUEL, 18.5968, 73.7300, "Lost Petrol", "{}")
    private val cafe = AmenityEntity(2L, AmenityType.CAFE, 18.5969, 73.7301, "Roadside Cafe", "{}")

    // The parked-location viewport (~18.60N 73.73E) — maps to 4 zoom-14 cells (live-measured).
    private val viewport = arrayOf(18.5940, 18.5998, 73.7270, 73.7330)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RideDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Default instance for pure cell-geometry reads; behavior tests build their
        // own controlled repositories against the same in-memory DB.
        fakeApiHolder = FakeApi {
            throw java.io.IOException("no network expected for cell-geometry reads")
        }
        repo = AmenityRepository(db, fakeApiHolder)
    }

    @After
    fun teardown() {
        db.close()
    }

    private class FakeApi(
        private val behaviour: (String) -> OverpassResponse
    ) : OverpassApiService {
        var queryCount = 0
        val queries = mutableListOf<String>()
        override suspend fun query(query: String): OverpassResponse {
            queryCount++
            queries.add(query)
            return behaviour(query)
        }
    }

    private fun box() = AmenityRepository.SafeBox(viewport[0], viewport[1], viewport[2], viewport[3])

    private fun cellKeys(): List<String> =
        repo.cellsForBBox(box()).map { it.key }

    @Test
    fun `cached POIs appear with zero network queries`() = runBlocking {
        db.amenityDao().insertAmenities(listOf(fuel))
        val fake = FakeApi { throw AssertionError("no network call expected") }
        val silentRepo = AmenityRepository(db, fake)
        val cached = silentRepo.getAmenitiesCached(viewport[0], viewport[1], viewport[2], viewport[3])
        assertEquals(1, cached.size)
        assertEquals("Lost Petrol", cached[0].name)
        assertEquals(0, fake.queryCount)
    }

    @Test
    fun `failed fetch never poisons the cache`() = runBlocking {
        db.amenityDao().insertAmenities(listOf(fuel, cafe))
        val failing = FakeApi { throw java.io.IOException("connect refused (measured 2026-10-19)") }
        val refresher = AmenityRepository(db, failing)

        val result = refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])

        // Both rows still served; nothing replaced by an empty result.
        assertEquals(2, result.size)
        assertEquals(0, db.amenityDao().getQueryTimestamps(cellKeys()).size)
        assertTrue(failing.queryCount >= 1)
    }

    @Test
    fun `permanent 4xx failure also preserves the cache`() = runBlocking {
        db.amenityDao().insertAmenities(listOf(cafe))
        val failing = FakeApi {
            throw retrofit2.HttpException(
                Response.error<Unit>(414, okhttp3.ResponseBody.create(null, "URL too large"))
            )
        }
        val refresher = AmenityRepository(db, failing)
        val result = refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])
        assertEquals(1, result.size)
        assertEquals("Roadside Cafe", result[0].name)
        assertEquals(0, db.amenityDao().getQueryTimestamps(cellKeys()).size)
    }

    @Test
    fun `successful empty response marks cells fresh without adding rows`() = runBlocking {
        val empty = FakeApi { OverpassResponse(elements = emptyList()) }
        val refresher = AmenityRepository(db, empty)

        val result = refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])

        assertEquals(0, result.size)
        val marks = db.amenityDao().getQueryTimestamps(cellKeys())
        assertEquals(cellKeys().size, marks.size) // all viewport cells marked
        assertEquals(0, db.amenityDao().getAmenitiesInBox(viewport[0], viewport[1], viewport[2], viewport[3]).size)
    }

    @Test
    fun `refresh upserts data and marks, and repeats stay cache-only`() = runBlocking {
        val response = OverpassResponse(
            elements = listOf(
                com.example.data.amenities.OverpassElement(
                    type = "node", id = 1L, lat = fuel.lat, lon = fuel.lon,
                    center = null,
                    tags = mapOf("amenity" to "fuel", "name" to "Upserted Station")
                )
            )
        )
        val fake = FakeApi { response }
        val refresher = AmenityRepository(db, fake)

        val first = refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])
        val second = refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])

        assertEquals(1, first.size)
        assertEquals("Upserted Station", first[0].name)
        // Second refresh: fresh marks mean no network query at all.
        assertEquals(1, fake.queryCount)
        assertEquals(1, second.size)
    }

    @Test
    fun `cell keys are query-versioned`() = runBlocking {
        val refresher = AmenityRepository(db, FakeApi { OverpassResponse(elements = emptyList()) })
        refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3])
        val marks = db.amenityDao().getQueryTimestamps(cellKeys())
        assertTrue(marks.isNotEmpty())
        marks.forEach { mark ->
            assertTrue(
                "key ${mark.cellKey} must embed the query version",
                mark.cellKey.contains("_v${OverpassQueryBuilder.QUERY_VERSION}_")
            )
        }
    }

    @Test
    fun `pre-v2 marks are cleaned without touching versioned ones`() = runBlocking {
        val dao = db.amenityDao()
        dao.insertQueryCaches(
            listOf(
                AmenityQueryCacheEntity("14_100_200", 42L), // legacy format (pre-versioning)
                AmenityQueryCacheEntity("14_v2_100_200", 43L) // versioned
            )
        )
        val refresher = AmenityRepository(db, FakeApi { OverpassResponse(elements = emptyList()) })
        refresher.refreshAmenities(viewport[0], viewport[1], viewport[2], viewport[3]) // first refresh triggers the one-time cleanup

        assertEquals(0, dao.getQueryTimestamps(listOf("14_100_200")).size)
        assertEquals(43L, dao.getQueryTimestamps(listOf("14_v2_100_200")).single().timestampMs)
    }

    @Test
    fun `viewport maps to expected zoom-14 cells with sane boxes`() = runBlocking {
        // The parked viewport (≈18.60N 73.73E, ~600 m across) sits inside one z14 cell.
        val narrow = repo.cellsForBBox(box())
        assertEquals(1, narrow.size)
        assertTrue(narrow[0].key.startsWith("14_v2_"))
        assertTrue(narrow[0].box.south < narrow[0].box.north && narrow[0].box.west < narrow[0].box.east)

        // A wide viewport (the live device one was ~2.5 km ⇒ 4 cells) spans multiples.
        val wide = repo.cellsForBBox(AmenityRepository.SafeBox(18.58, 18.62, 73.71, 73.75))
        assertTrue("wide viewport should span several cells, got ${wide.size}", wide.size in 2..12)
        assertEquals(wide.map { it.key }.distinct().size, wide.size) // keys are unique
        assertTrue(wide.all { it.box.south < it.box.north && it.box.west < it.box.east })
        val query = OverpassQueryBuilder.buildQuery(wide.map { it.box })
        assertTrue(query.contains("out center;"))
    }
}
