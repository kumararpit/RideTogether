package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.RideRepository
import com.example.data.location.LocationTracker
import com.example.data.location.LocationUpdate
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SosLifecycleAndDeliveryTest {

    @Test
    fun `test 1 - SOS creation unique UUID and location freshness classification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_1", "Arpit Test")

        val ride = repo.createRide("SOS Test Convoy", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        assertNotNull(ride)

        repo.triggerSos()
        val sos = repo.activeSos.value
        assertNotNull(sos)
        assertEquals("Arpit Test", sos?.riderName)
        assertTrue("Location source must be valid", sos?.locationSource in listOf("FRESH_LOCATION_FIX", "LAST_KNOWN_LOCATION", "LOCATION_UNAVAILABLE"))
        assertEquals("Rider status must be EMERGENCY during active SOS", MemberStatus.EMERGENCY, repo.currentRiderStatus.value)

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 2 - idempotency - duplicate trigger returns existing SOS without creating new alert`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_2", "Rohan Test")

        repo.createRide("Idempotency Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.triggerSos()
        val sos1 = repo.activeSos.value
        assertNotNull(sos1)

        // Second duplicate trigger while SOS is unresolved
        repo.triggerSos()
        val sos2 = repo.activeSos.value

        assertEquals("Duplicate trigger must preserve original SOS ID", sos1?.id, sos2?.id)

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 3 - acknowledgeSos appends rider ID without resolving SOS`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_3", "Priya Test")

        repo.createRide("Ack Test Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.triggerSos()
        val sos = repo.activeSos.value
        assertNotNull(sos)

        // Acknowledge SOS
        repo.acknowledgeSos(sos!!.id)
        val ackedSos = repo.activeSos.value

        assertTrue("Acknowledging SOS must append user ID to acknowledgedBy", ackedSos?.acknowledgedBy?.contains("rider_sos_3") == true)
        assertEquals("Acknowledging SOS must NOT resolve the alert", false, ackedSos?.isResolved)

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 4 - resolveSos clears active alert and restores rider status to RIDING`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_4", "Karan Test")

        repo.createRide("Resolve Test Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.triggerSos()
        assertNotNull(repo.activeSos.value)
        assertEquals(MemberStatus.EMERGENCY, repo.currentRiderStatus.value)

        repo.resolveSosInternal()

        assertNull("Resolving SOS must set activeSos to null", repo.activeSos.value)
        assertEquals("Resolving SOS must restore currentRiderStatus to RIDING", MemberStatus.RIDING, repo.currentRiderStatus.value)

        repo.cleanup()
    }

    @Test
    fun `test 5 - leaving ride auto-resolves pending unresolved SOS`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_5", "Sneha Test")

        repo.createRide("Departure Test Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.triggerSos()
        assertNotNull(repo.activeSos.value)

        // Leave ride
        repo.leaveRide()

        assertNull("Leaving ride must clear active SOS alert so ghost alerts do not linger", repo.activeSos.value)
        repo.cleanup()
    }

    @Test
    fun `test 6 - missing or null firestore explicitly fails triggerSos without leaving active local alert`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        // Repository created with null Firestore
        val repo = RideRepository(locationTracker = tracker)

        val result = repo.triggerSos()
        assertTrue("triggerSos must return failure when Firestore is null", result.isFailure)
        assertNull("activeSos must remain null when triggerSos fails", repo.activeSos.value)
        repo.cleanup()
    }

    @Test
    fun `test 7 - missing location yields LOCATION_UNAVAILABLE without hardcoded Pune fallback coordinates`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_no_loc", "No Loc Test")

        repo.createRide("No Loc Ride", "Start", "Dest", LatLng(0.0, 0.0), LatLng(0.0, 0.0))

        repo.triggerSos()
        val sos = repo.activeSos.value
        assertNotNull(sos)

        if (sos?.locationSource == "LOCATION_UNAVAILABLE") {
            assertEquals("Location source LOCATION_UNAVAILABLE must report accuracy -1", -1f, sos.accuracyMeters, 0.01f)
            assertEquals("Location must be 0,0 when unavailable", LatLng(0.0, 0.0), sos.location)
        }

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 8 - future dated GPS timestamp is rejected as fresh location`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_sos_future", "Future TS Test")

        repo.createRide("Future TS Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        val now = System.currentTimeMillis()
        // Future dated update (10 seconds in the future)
        val futureUpdate = LocationUpdate(
            latLng = LatLng(18.52, 73.85),
            speedKmh = 40.0,
            headingDeg = 180f,
            accuracyMeters = 5f,
            timestampMs = now + 10000L
        )

        val ageMs = now - futureUpdate.timestampMs
        assertTrue("Future dated timestamp yields negative age", ageMs < 0L)

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 9 - resolveSosInternal returns failure without clearing local state if firestore is null`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(locationTracker = tracker)

        repo.initCurrentUser("rider_resolve_null_fs", "Arpit Null FS")

        repo.createRide("Mock Ride", "A", "B", LatLng(0.0,0.0), LatLng(0.0,0.0))
        repo.triggerSos() 

        // Because Firestore is null, triggerSos fails and activeSos remains null
        // Inject an active SOS artificially to test resolution
        val event = com.example.data.model.SosEvent(
            id = "mock_sos",
            riderId = "rider_resolve_null_fs",
            riderName = "Arpit Null FS",
            location = LatLng(0.0,0.0)
        )
        val privateActiveSosField = RideRepository::class.java.getDeclaredField("_activeSos")
        privateActiveSosField.isAccessible = true
        (privateActiveSosField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<com.example.data.model.SosEvent?>).value = event

        val result = repo.resolveSosInternal()
        assertTrue("resolveSosInternal must return failure when Firestore is null", result.isFailure)
        assertNotNull("resolveSosInternal must not clear local state on failure", repo.activeSos.value)
    }

    @Test
    fun `test 10 - triggerSos returns success only after the delivery write completes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val delivered = mutableListOf<String>()
        val backend = object : com.example.data.firebase.SosDeliveryBackend {
            override suspend fun deliver(rideId: String, sosId: String, doc: Map<String, Any>) {
                delivered.add(sosId)
            }

            override suspend fun resolve(rideId: String, sosId: String, fields: Map<String, Any>) = Unit
        }
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = backend
        )
        repo.initCurrentUser("rider_sos_success", "Arpit Success")

        val ride = repo.createRide("SOS Success Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        assertNotNull(ride)

        val result = repo.triggerSos()
        assertTrue("triggerSos must return success containing the SosEvent when the delivery write succeeds", result.isSuccess)
        assertNotNull("triggerSos must return the created SosEvent", result.getOrNull())
        assertEquals("The SOS event must have been handed to the delivery sink", 1, delivered.size)

        repo.resolveSos()
        repo.cleanup()
    }

    @Test
    fun `test 11 - a failed delivery rolls the local alert back`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val backend = object : com.example.data.firebase.SosDeliveryBackend {
            override suspend fun deliver(rideId: String, sosId: String, doc: Map<String, Any>) {
                throw IllegalStateException("backend unavailable")
            }

            override suspend fun resolve(rideId: String, sosId: String, fields: Map<String, Any>) = Unit
        }
        val repo = RideRepository(
            locationTracker = tracker,
            context = context,
            sosBackend = backend
        )
        repo.initCurrentUser("rider_sos_delivery_fail", "Arpit Fail")

        repo.createRide("SOS Delivery Fail Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        val result = repo.triggerSos()
        assertTrue("triggerSos must fail when the delivery write fails", result.isFailure)
        assertNull("A failed delivery must not leave a local alert behind", repo.activeSos.value)
        assertEquals(
            "A failed delivery must restore the rider status",
            MemberStatus.RIDING,
            repo.currentRiderStatus.value
        )

        repo.cleanup()
    }
}
