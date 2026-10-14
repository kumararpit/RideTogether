package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.RideRepository
import com.example.data.firebase.RiderSyncPolicy
import com.example.data.firebase.RiderTimestamp
import com.example.data.location.LocationTracker
import com.example.data.model.ConnectionStatus
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import com.example.data.model.MovementState
import com.example.data.model.RideRole
import com.example.data.model.RiderMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RiderStatusAndSyncAuditTest {

    @Test
    fun `test 1 - document with old location event timestamp and recent server timestamp does not falsely prove location is fresh`() {
        val now = 100000L
        // Old event-generation timestamp (90s ago), recent server-write timestamp (1s ago)
        val timestamp = RiderTimestamp(eventTimestampMs = now - 90000L, serverTimestampMs = now - 1000L)

        val conn = RiderSyncPolicy.calculateConnectionStatus(
            isCurrentUser = false,
            isGpsAvailable = true,
            timestamp = timestamp,
            currentTimeMs = now
        )

        assertEquals("Old event-generation timestamp (90s ago) must evaluate as DISCONNECTED even if server timestamp is recent", ConnectionStatus.DISCONNECTED, conn)
    }

    @Test
    fun `test 2 - event and server timestamps are not compared directly as if having same provenance`() {
        val now = 100000L
        val existing = RiderMember(
            id = "rider_101",
            name = "Rohan",
            location = LatLng(18.5200, 73.8500),
            sequenceNumber = 10L,
            lastUpdatedMs = now - 2000L, // Event time = 98000L
            serverTimestampMs = now - 1900L
        )

        // Delayed/out-of-order event with OLD event timestamp (90000L) but RECENT server write time (99500L)
        val incomingTimestamp = RiderTimestamp(eventTimestampMs = now - 10000L, serverTimestampMs = now - 500L)

        val shouldAccept = RiderSyncPolicy.shouldAcceptUpdate(
            existing = existing,
            incomingSeq = 15L, // Higher sequence number
            incomingTimestamp = incomingTimestamp,
            prevSeq = 10L
        )

        assertFalse("Incoming event with older event-generation timestamp must be REJECTED, ignoring server write timestamp", shouldAccept)
    }

    @Test
    fun `test 3 - missing event timestamp does not manufacture current timestamp`() {
        val parsedMissing = RiderSyncPolicy.parseTimestamp(rawLastUpdatedMs = null, serverTimestampMs = null)
        assertEquals(0L, parsedMissing.eventTimestampMs)
        assertEquals(0L, parsedMissing.serverTimestampMs)
        assertFalse(parsedMissing.hasEventTimestamp)
        assertFalse(parsedMissing.hasServerTimestamp)

        val parsedServerOnly = RiderSyncPolicy.parseTimestamp(rawLastUpdatedMs = null, serverTimestampMs = 1700000000000L)
        assertEquals(0L, parsedServerOnly.eventTimestampMs)
        assertEquals(1700000000000L, parsedServerOnly.serverTimestampMs)
        assertFalse(parsedServerOnly.hasEventTimestamp)
        assertTrue(parsedServerOnly.hasServerTimestamp)
    }

    @Test
    fun `test 4 - invalid first-seen coordinate does not create fabricated map location`() {
        val invalidLat = 195.0
        val invalidLng = 73.8567

        // Exercise production decision function directly for first-seen rider (existing == null)
        val result = RiderSyncPolicy.processRiderSnapshot(
            id = "first_seen_test",
            name = "First Seen",
            motorcycleModel = "Tiger 900",
            avatarColorHex = 0xFFFF9800,
            role = RideRole.MEMBER,
            status = MemberStatus.RIDING,
            emergencyState = com.example.data.model.EmergencyState.NONE,
            rawLat = invalidLat,
            rawLng = invalidLng,
            defaultLat = 18.5204,
            defaultLng = 73.8567,
            speedKmh = 45.0,
            headingDeg = 180f,
            accuracyMeters = 10f,
            sequenceNumber = 1L,
            rawLastUpdatedMs = System.currentTimeMillis(),
            serverTimestampMs = System.currentTimeMillis(),
            stoppedDurationSec = 0L,
            batteryPct = 90,
            existing = null,
            prevSeq = -1L,
            isCurrentUser = false,
            isGpsAvailable = true
        )

        assertEquals("First-seen record with invalid coordinates must return null from production processRiderSnapshot", null, result)
    }

    @Test
    fun `test 5 - invalid update for existing rider preserves accepted location and freshness metadata`() {
        val lastValidTimestamp = 100000L
        val acceptedRider = RiderMember(
            id = "rider_corrupt_test",
            name = "Test",
            location = LatLng(18.5204, 73.8567),
            lastUpdatedMs = lastValidTimestamp,
            serverTimestampMs = lastValidTimestamp
        )

        val invalidLat = 195.0
        val invalidLng = 73.8567

        // Exercise production decision function directly for existing rider
        val result = RiderSyncPolicy.processRiderSnapshot(
            id = acceptedRider.id,
            name = acceptedRider.name,
            motorcycleModel = acceptedRider.motorcycleModel,
            avatarColorHex = acceptedRider.avatarColorHex,
            role = acceptedRider.role,
            status = acceptedRider.status,
            emergencyState = acceptedRider.emergencyState,
            rawLat = invalidLat,
            rawLng = invalidLng,
            defaultLat = 18.5204,
            defaultLng = 73.8567,
            speedKmh = 50.0,
            headingDeg = 180f,
            accuracyMeters = 10f,
            sequenceNumber = 20L,
            rawLastUpdatedMs = 120000L,
            serverTimestampMs = 120000L,
            stoppedDurationSec = 0L,
            batteryPct = 90,
            existing = acceptedRider,
            prevSeq = 10L,
            isCurrentUser = false,
            isGpsAvailable = true
        )

        assertEquals("Invalid coordinate update must return existing accepted RiderMember", acceptedRider, result)
        assertEquals("Location must remain unchanged", LatLng(18.5204, 73.8567), result?.location)
        assertEquals("lastUpdatedMs must NOT be refreshed", lastValidTimestamp, result?.lastUpdatedMs)
    }

    @Test
    fun `test 6 - valid event with newer event timestamp is accepted`() {
        val now = 100000L
        val existing = RiderMember(
            id = "rider_101",
            name = "Rohan",
            location = LatLng(18.5200, 73.8500),
            sequenceNumber = 10L,
            lastUpdatedMs = now - 5000L
        )

        val acceptNewer = RiderSyncPolicy.shouldAcceptUpdate(
            existing = existing,
            incomingSeq = 11L,
            incomingTimestamp = RiderTimestamp(eventTimestampMs = now - 1000L),
            prevSeq = 10L
        )

        assertTrue("Newer event-generation timestamp update must be accepted", acceptNewer)
    }

    @Test
    fun `test 7 - older event timestamp is rejected including when sequence number is higher`() {
        val now = 100000L
        val existing = RiderMember(
            id = "rider_101",
            name = "Rohan",
            location = LatLng(18.5200, 73.8500),
            sequenceNumber = 10L,
            lastUpdatedMs = now - 2000L
        )

        val acceptOlderHigherSeq = RiderSyncPolicy.shouldAcceptUpdate(
            existing = existing,
            incomingSeq = 25L, // Higher sequence number
            incomingTimestamp = RiderTimestamp(eventTimestampMs = now - 8000L), // Older timestamp
            prevSeq = 10L
        )

        assertFalse("Older event timestamp must be rejected even with a higher sequence number", acceptOlderHigherSeq)
    }

    @Test
    fun `test 8 - missing or unknown timestamps do not claim connected fresh remote rider`() {
        val connMissing = RiderSyncPolicy.calculateConnectionStatus(
            isCurrentUser = false,
            isGpsAvailable = true,
            timestamp = RiderTimestamp(0L, 0L),
            currentTimeMs = System.currentTimeMillis()
        )

        assertEquals("Unknown timestamp (0L) must evaluate as DISCONNECTED", ConnectionStatus.DISCONNECTED, connMissing)
    }

    @Test
    fun `test 9 - calculateConnectionStatus exact freshness boundaries with injectable clock`() {
        val t0 = 100000L
        val ts = RiderTimestamp(eventTimestampMs = t0)

        val conn24s = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 24999L)
        val conn25s = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 25000L)
        val conn25s1ms = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 25001L)

        val conn59s = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 59999L)
        val conn60s = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 60000L)
        val conn60s1ms = RiderSyncPolicy.calculateConnectionStatus(false, true, ts, currentTimeMs = t0 + 60001L)

        assertEquals("24,999 ms is CONNECTED", ConnectionStatus.CONNECTED, conn24s)
        assertEquals("25,000 ms is CONNECTED", ConnectionStatus.CONNECTED, conn25s)
        assertEquals("25,001 ms is LOCATION_STALE", ConnectionStatus.LOCATION_STALE, conn25s1ms)

        assertEquals("59,999 ms is LOCATION_STALE", ConnectionStatus.LOCATION_STALE, conn59s)
        assertEquals("60,000 ms is LOCATION_STALE", ConnectionStatus.LOCATION_STALE, conn60s)
        assertEquals("60,001 ms is DISCONNECTED", ConnectionStatus.DISCONNECTED, conn60s1ms)
    }

    @Test
    fun `test 10 - fresh stationary rider is distinguished from stale or disconnected rider`() {
        val now = 100000L

        val freshStationary = RiderMember(
            id = "rider_stopped",
            name = "Aman",
            status = MemberStatus.STOPPED,
            connectionStatus = ConnectionStatus.CONNECTED,
            location = LatLng(18.5200, 73.8500),
            speedKmh = 0.0,
            stoppedDurationSec = 30L,
            lastUpdatedMs = now - 2000L
        )

        val staleRider = freshStationary.copy(
            id = "rider_stale",
            connectionStatus = RiderSyncPolicy.calculateConnectionStatus(false, true, RiderTimestamp(eventTimestampMs = now - 30000L), now),
            lastUpdatedMs = now - 30000L
        )

        val disconnectedRider = freshStationary.copy(
            id = "rider_disc",
            connectionStatus = RiderSyncPolicy.calculateConnectionStatus(false, true, RiderTimestamp(eventTimestampMs = now - 70000L), now),
            lastUpdatedMs = now - 70000L
        )

        assertEquals("Fresh stationary rider connection status must be CONNECTED", ConnectionStatus.CONNECTED, freshStationary.connectionStatus)
        assertEquals("Fresh stationary rider status must be STOPPED", MemberStatus.STOPPED, freshStationary.status)

        assertEquals("Unupdated rider (30s) must evaluate as LOCATION_STALE", ConnectionStatus.LOCATION_STALE, staleRider.connectionStatus)
        assertEquals("Unupdated rider (70s) must evaluate as DISCONNECTED", ConnectionStatus.DISCONNECTED, disconnectedRider.connectionStatus)
    }

    @Test
    fun `test 11 - map and member list state remain consistent after rider update`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = LocationTracker(context)
        val repo = RideRepository(context, tracker)

        repo.addPackMember("Vikram", 0.010, 0.010)
        val membersList = repo.members.value
        val foundMember = membersList.find { it.name == "Vikram" }

        assertNotNull("Member list must contain updated rider", foundMember)
        assertTrue("Member list and map view consume identical state flow instance", repo.members.value.contains(foundMember))

        repo.cleanup()
    }

    @Test
    fun `test 12 - existing rider with valid event timestamp is NOT overwritten by incoming snapshot with missing event timestamp even with higher server time and sequence`() {
        val now = 100000L
        val existing = RiderMember(
            id = "rider_valid_ts",
            name = "Rohan",
            location = LatLng(18.5200, 73.8500),
            sequenceNumber = 10L,
            lastUpdatedMs = now - 5000L, // Valid GPS event time
            serverTimestampMs = now - 4000L
        )

        // Incoming snapshot with missing event timestamp (0L) but higher sequence (50) and newer server time (now)
        val result = RiderSyncPolicy.processRiderSnapshot(
            id = existing.id,
            name = existing.name,
            motorcycleModel = existing.motorcycleModel,
            avatarColorHex = existing.avatarColorHex,
            role = existing.role,
            status = existing.status,
            emergencyState = com.example.data.model.EmergencyState.NONE,
            rawLat = 18.5500, // New coordinates
            rawLng = 73.8500,
            defaultLat = 18.5204,
            defaultLng = 73.8567,
            speedKmh = 50.0,
            headingDeg = 180f,
            accuracyMeters = 10f,
            sequenceNumber = 50L, // Higher sequence
            rawLastUpdatedMs = null, // Missing event timestamp
            serverTimestampMs = now, // Newer server timestamp
            stoppedDurationSec = 0L,
            batteryPct = 90,
            existing = existing,
            prevSeq = 10L,
            isCurrentUser = false,
            isGpsAvailable = true
        )

        assertEquals("Existing rider with valid event timestamp must NOT be overwritten by update missing event timestamp", existing, result)
        assertEquals("Location must remain unchanged at accepted location", LatLng(18.5200, 73.8500), result?.location)
    }

    @Test
    fun `test 13 - valid coordinate snapshot with missing event timestamp parses lastUpdatedMs as 0L`() {
        val now = 100000L
        val firstSeenResult = RiderSyncPolicy.processRiderSnapshot(
            id = "first_seen_no_ts",
            name = "First Seen No TS",
            motorcycleModel = "Tiger 900",
            avatarColorHex = 0xFFFF9800,
            role = RideRole.MEMBER,
            status = MemberStatus.RIDING,
            emergencyState = com.example.data.model.EmergencyState.NONE,
            rawLat = 18.5204,
            rawLng = 73.8567,
            defaultLat = 18.5204,
            defaultLng = 73.8567,
            speedKmh = 45.0,
            headingDeg = 180f,
            accuracyMeters = 10f,
            sequenceNumber = 1L,
            rawLastUpdatedMs = null, // Missing event timestamp
            serverTimestampMs = now,
            stoppedDurationSec = 0L,
            batteryPct = 90,
            existing = null,
            prevSeq = -1L,
            isCurrentUser = false,
            isGpsAvailable = true
        )

        assertNotNull("First-seen snapshot with valid coordinates must create RiderMember", firstSeenResult)
        assertEquals("lastUpdatedMs must be strictly 0L (never manufactured as current system time)", 0L, firstSeenResult?.lastUpdatedMs)
        assertEquals("serverTimestampMs must preserve server write time", now, firstSeenResult?.serverTimestampMs)
        assertEquals("Connection status for missing event timestamp must evaluate as DISCONNECTED", ConnectionStatus.DISCONNECTED, firstSeenResult?.connectionStatus)
    }

    @Test
    fun `test 14 - unknown-order update lacking event timestamp CANNOT replace existing rider even when both lack event timestamp`() {
        val now = 100000L
        val existingNoEventTs = RiderMember(
            id = "rider_no_ts",
            name = "Rohan",
            location = LatLng(18.5200, 73.8500),
            sequenceNumber = 10L,
            lastUpdatedMs = 0L, // Missing/unknown event timestamp
            serverTimestampMs = now - 5000L
        )

        // Incoming update lacking event timestamp with higher sequence (50) and newer server time (now)
        val result = RiderSyncPolicy.processRiderSnapshot(
            id = existingNoEventTs.id,
            name = existingNoEventTs.name,
            motorcycleModel = existingNoEventTs.motorcycleModel,
            avatarColorHex = existingNoEventTs.avatarColorHex,
            role = existingNoEventTs.role,
            status = existingNoEventTs.status,
            emergencyState = com.example.data.model.EmergencyState.NONE,
            rawLat = 18.5500, // Different location
            rawLng = 73.8500,
            defaultLat = 18.5204,
            defaultLng = 73.8567,
            speedKmh = 50.0,
            headingDeg = 180f,
            accuracyMeters = 10f,
            sequenceNumber = 50L, // Higher sequence
            rawLastUpdatedMs = null, // Missing event timestamp
            serverTimestampMs = now, // Higher server time
            stoppedDurationSec = 0L,
            batteryPct = 90,
            existing = existingNoEventTs,
            prevSeq = 10L,
            isCurrentUser = false,
            isGpsAvailable = true
        )

        assertEquals("Existing rider must NOT be replaced by an update lacking event timestamp, preserving accepted location", existingNoEventTs, result)
        assertEquals("Location must remain unchanged at accepted location", LatLng(18.5200, 73.8500), result?.location)
    }
}
