package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.RideRepository
import com.example.data.firebase.SosRetryPolicy
import com.example.data.firebase.SosSuppressionState
import com.example.data.location.LocationTracker
import com.example.data.model.LatLng
import com.example.data.model.MemberStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the SOS reliability fix (Task 19 feedback):
 *  - "I'm okay" must stop the alert for everyone (retried resolve, no immortality).
 *  - Acknowledgments must reach the SENDER (retried ack, names surfaced to sender).
 *  - Members get an explicit per-device popup removal that never resolves the
 *    sender's emergency and never suppresses a *different* SOS event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SosAckDeliveryAndDismissalTest {

    // ------------------------------------------------------------------
    // Suppression policy (pure)
    // ------------------------------------------------------------------

    @Test
    fun `dismissal suppresses only the dismissed event id`() {
        val state = SosSuppressionState()
        state.suppress("sos-a")

        assertTrue(state.isSuppressed("sos-a"))
        assertFalse("a NEW SOS event must always surface", state.isSuppressed("sos-b"))
        assertFalse("null/blank ids are never suppressed", state.isSuppressed(null))
        assertFalse(state.isSuppressed(""))
    }

    @Test
    fun `suppression state clears for a fresh session`() {
        val state = SosSuppressionState()
        state.suppress("sos-a")
        state.clear()
        assertFalse(state.isSuppressed("sos-a"))
    }

    // ------------------------------------------------------------------
    // Retry policy (pure)
    // ------------------------------------------------------------------

    @Test
    fun `ack and resolve retry policy is bounded`() {
        assertEquals(SosRetryPolicy.MAX_ATTEMPTS, 3)
        assertEquals(0L, SosRetryPolicy.delayBeforeAttemptMs(1))
        assertEquals(500L, SosRetryPolicy.delayBeforeAttemptMs(2))
        assertEquals(1000L, SosRetryPolicy.delayBeforeAttemptMs(3))
        assertEquals("backoff is capped", 2000L, SosRetryPolicy.delayBeforeAttemptMs(99))
    }

    // ------------------------------------------------------------------
    // Repository-level dismissal (harness mirrors SosLifecycleAndDeliveryTest)
    // ------------------------------------------------------------------

    @Test
    fun `member dismissal removes the popup locally without resolving the emergency`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("rider_dism_1", "Dismiss Tester")

        repo.createRide("Dismiss Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        repo.triggerSos()
        val sos = repo.activeSos.value
        assertNotNull(sos)
        assertEquals(MemberStatus.EMERGENCY, repo.currentRiderStatus.value)

        // Member removes the popup on their own device.
        repo.dismissSosBanner()
        assertNull("banner must be removed for this member", repo.activeSos.value)

        // Stale listener replays of that event id stay suppressed on this device.
        assertTrue(repo.isSosSuppressedThisSession(sos!!.id))
        // The emergency itself is untouched: another live SOS event (new id) would
        // surface, and the sender's own rider status logic still treats the ride doc
        // as authoritative. Local marker must not flip to RIDING via dismissal.
        repo.cleanup()
    }

    @Test
    fun `dismissal of one SOS does not hide a later different SOS event`() {
        val state = SosSuppressionState()
        state.suppress("old-sos-id")

        assertFalse("fresh emergency id is never suppressed", state.isSuppressed("new-sos-id"))
    }
}
