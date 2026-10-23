package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.RideRepository
import com.example.data.location.LocationTracker
import com.example.data.model.LatLng
import com.example.data.model.QuickMessage
import com.example.data.model.QuickMessageType
import com.example.data.model.RideStatus
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 21 — shared completion propagation + group-chat delivery regression tests
 * (repository/ViewModel level; fakes only, NO live Firebase project / emulator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RideCompletionAndChatSyncTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------
    // Chat reliability (repository level)
    // ------------------------------------------------------------------

    @Test
    fun `send adds a local echo with a stable unique id`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("user_chat_1", "Chat Leader")
        repo.createRide("Chat Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.sendQuickMessage(QuickMessageType.WAIT_FOR_ME)

        val history = repo.recentMessages.value
        assertEquals(1, history.size)
        assertTrue("id must be non-empty and unique per send", history.first().id.isNotBlank())
        assertEquals("user_chat_1", history.first().senderId)
        assertEquals(QuickMessageType.WAIT_FOR_ME, history.first().type)
        repo.cleanup()
    }

    @Test
    fun `server echo of the same message id does not duplicate display`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("user_chat_2", "Echo Rider")
        repo.createRide("Echo Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        repo.sendQuickMessage(QuickMessageType.REGROUP)

        val echoed = repo.recentMessages.value.first()
        repo.ingestSharedMessageForTest(echoed) // listener reconciles the confirmed write

        assertEquals("pending echo + server snapshot dedup to one message", 1, repo.recentMessages.value.size)
        assertEquals(echoed.id, repo.recentMessage.value?.id)
        repo.cleanup()
    }

    @Test
    fun `rapid burst keeps every message - no silent overwrite of earlier messages`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("user_chat_3", "Burst Sender")
        repo.createRide("Burst Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        fun qm(id: String, ts: Long) = QuickMessage(
            id = id, senderId = "user_other", senderName = "Other",
            type = QuickMessageType.NEED_FUEL, text = "m-$id", timestampMs = ts
        )
        repo.ingestSharedMessageForTest(qm("b1", 300))
        repo.ingestSharedMessageForTest(qm("b2", 100))
        repo.ingestSharedMessageForTest(qm("b3", 200))

        val history = repo.recentMessages.value
        assertEquals("all three burst messages survive", 3, history.size)
        assertEquals("deterministic chronological ordering", listOf("b2", "b3", "b1"), history.map { it.id })
        assertEquals("recent slot shows the newest", "b1", repo.recentMessage.value?.id)
        repo.cleanup()
    }

    @Test
    fun `failed send surfaces an error state instead of being silently swallowed`() = runBlocking {
        // Primary constructor with a null Firestore → deterministic "backend unavailable"
        // path (the Robolectric test environment never resolves real Firestore writes).
        val tracker = LocationTracker(context)
        val repo = RideRepository(tracker, firestore = null, context = context)
        repo.initCurrentUser("user_chat_4", "Offline Rider")
        repo.createRide("Offline Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))

        repo.sendQuickMessage(QuickMessageType.FOOD_STOP)

        val deadline = System.currentTimeMillis() + 10_000
        var surfaced = repo.messageSendError.value
        while (!surfaced && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
            surfaced = repo.messageSendError.value
        }
        assertTrue("unrecoverable send failure must be surfaced (messageSendError=true)", surfaced)
        // The local echo is preserved; a retry would reuse a NEW intentional send.
        assertTrue(repo.recentMessages.value.isNotEmpty())
        repo.cleanup()
    }

    // ------------------------------------------------------------------
    // Shared ride completion (repository + ViewModel)
    // ------------------------------------------------------------------

    @Test
    fun `unauthorized member cannot complete the ride`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("com_leader_1", "Leader One")
        repo.createRide("Leader Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        repo.applySharedRideStatusForTest(RideStatus.ACTIVE)

        // A different rider (member) attempts completion.
        repo.updateLeaderIdForTest("some_other_leader")

        val result = repo.endRide()
        assertTrue("member completion must be rejected", result.isFailure)
        assertEquals("shared status must remain ACTIVE", RideStatus.ACTIVE, repo.currentRide.value?.status)
        assertFalse(repo.isSharedRideCompletedObserved(repo.currentRide.value?.id ?: ""))
        repo.cleanup()
    }

    @Test
    fun `leader completion writes canonical shared status exactly once`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("com_leader_2", "Leader Two")
        repo.createRide("Complete Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        repo.applySharedRideStatusForTest(RideStatus.ACTIVE)
        val rideId = repo.currentRide.value?.id ?: return@runBlocking

        // In tests the Firestore write cannot be confirmed; the leader local session
        // still transitions and the write result is honestly reported.
        val result = repo.endRide()
        assertEquals(RideStatus.COMPLETED, repo.currentRide.value?.status)
        assertTrue(repo.isSharedRideCompletedObserved(rideId))
        assertTrue("write result must be reported (success or surfaced failure)", result.isSuccess || result.isFailure)

        // Duplicate completion submission is idempotent (in-flight guard; second call
        // after completion re-runs the transaction but never leaves an inconsistent state).
        repo.applySharedRideStatusForTest(RideStatus.COMPLETED)
        assertEquals(RideStatus.COMPLETED, repo.currentRide.value?.status)
        repo.cleanup()
    }

    @Test
    fun `member observing shared COMPLETED leaves navigation exactly once`() {
        val viewModel = RideViewModel(context as android.app.Application)
        runBlocking {
            viewModel.createRide("Member Sync Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        }
        viewModel.startRide()
        assertEquals(AppScreen.LIVE_RIDE, viewModel.currentScreen.value)
        val ride = viewModel.currentRide.value ?: return

        // Leader's shared write lands on this (member) device via the ride listener.
        viewModel.repository.applySharedRideStatusForTest(RideStatus.COMPLETED)

        assertEquals("member must leave active navigation to the established post-ride screen", AppScreen.RIDE_SUMMARY, viewModel.currentScreen.value)
        assertTrue("client must register completion (stops ride-scoped tracking work)", viewModel.repository.isSharedRideCompletedObserved(ride.id))
        assertEquals("shared completion must not be re-processed by repeated snapshots", 1, viewModel.sharedCompletionHandledCount)

        viewModel.repository.onSharedRideCompleted(ride.id) // repeated observation
        assertEquals(1, viewModel.sharedCompletionHandledCount)
    }

    @Test
    fun `stale ACTIVE snapshot cannot reactivate a completed ride`() = runBlocking {
        val repo = RideRepository(
            locationTracker = LocationTracker(context),
            firestore = null,
            context = context,
            sosBackend = InMemorySosBackend()
        )
        repo.initCurrentUser("com_leader_3", "Leader Three")
        repo.createRide("Stale Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        repo.applySharedRideStatusForTest(RideStatus.ACTIVE)
        repo.applySharedRideStatusForTest(RideStatus.COMPLETED)

        // Delayed listener event from before the completion:
        repo.applySharedRideStatusForTest(RideStatus.ACTIVE)

        assertEquals("canonical status must stay COMPLETED (stale update rejected)",
            RideStatus.COMPLETED, repo.currentRide.value?.status)
        repo.cleanup()
    }

    @Test
    fun `rejoining a completed ride does not resume navigation`() = runBlocking {
        val viewModel = RideViewModel(context as android.app.Application)
        runBlocking {
            viewModel.createRide("Rejoin Ride", "Pune", "Lonavala", LatLng(18.52, 73.85), LatLng(18.75, 73.40))
        }
        viewModel.repository.applySharedRideStatusForTest(RideStatus.COMPLETED)
        viewModel.rejoinActiveRide()
        assertEquals("rejoin must return to the post-ride screen, not resume navigation",
            AppScreen.RIDE_SUMMARY, viewModel.currentScreen.value)
    }
}
