package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ActiveRideReferenceDao
import com.example.data.db.ActiveRideReferenceEntity
import com.example.data.db.RideDatabase
import com.example.data.model.RideStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the active-ride reference DAO (Room v11 additive table).
 * In-memory DB: verifies insert/replace/get/clear without touching existing tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveRideReferenceDaoTest {

    private lateinit var db: RideDatabase
    private val dao get() = db.activeRideReferenceDao()

    private fun reference(
        rideId: String = "ride_123",
        leaderId: String = "leader_1",
        status: RideStatus = RideStatus.ACTIVE
    ) = ActiveRideReferenceEntity(
        rideId = rideId,
        leaderId = leaderId,
        status = status.name
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RideDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert then get returns the stored reference`() = runTest {
        dao.insertOrReplace(reference())

        val stored = dao.getReference()
        assertEquals("ride_123", stored!!.rideId)
        assertEquals("leader_1", stored.leaderId)
        assertEquals(RideStatus.ACTIVE.name, stored.status)
        assertTrue(stored.updatedAtMs > 0L)
    }

    @Test
    fun `getReference returns null when table is empty`() = runTest {
        assertNull(dao.getReference())
    }

    @Test
    fun `insert replace overwrites existing reference by rideId`() = runTest {
        dao.insertOrReplace(reference(rideId = "ride_a", leaderId = "leader_a"))
        dao.insertOrReplace(reference(rideId = "ride_b", leaderId = "leader_b"))

        val stored = dao.getReference()
        assertEquals("ride_b", stored!!.rideId)
        assertEquals("leader_b", stored.leaderId)
    }

    @Test
    fun `deleteReference removes the matching ride`() = runTest {
        dao.insertOrReplace(reference(rideId = "ride_a"))
        dao.insertOrReplace(reference(rideId = "ride_b"))

        dao.deleteReference("ride_a")
        val remaining = dao.getReference()
        assertEquals("ride_b", remaining!!.rideId)
    }

    @Test
    fun `clearAll removes every reference`() = runTest {
        dao.insertOrReplace(reference(rideId = "ride_a"))
        dao.insertOrReplace(reference(rideId = "ride_b"))

        dao.clearAll()
        assertNull(dao.getReference())
    }

    @Test
    fun `terminal status values are stored as-is`() = runTest {
        dao.insertOrReplace(reference(status = RideStatus.COMPLETED))

        val stored = dao.getReference()
        assertEquals(RideStatus.COMPLETED.name, stored!!.status)
    }
}
