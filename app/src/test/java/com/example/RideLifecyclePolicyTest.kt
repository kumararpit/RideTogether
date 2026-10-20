package com.example

import com.example.data.model.RideLifecyclePolicy
import com.example.data.model.RideStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 21 — canonical shared ride lifecycle state machine rules.
 * The shared `rides/{rideId}.status` field is authoritative; these rules decide which
 * transitions any source (listener snapshot, leader action, test injection) may apply.
 */
class RideLifecyclePolicyTest {

    @Test
    fun `legal forward transitions are allowed`() {
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.CREATED, RideStatus.LOBBY))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.CREATED, RideStatus.ACTIVE))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.LOBBY, RideStatus.ACTIVE))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.LOBBY, RideStatus.COMPLETED))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.LOBBY, RideStatus.CANCELLED))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.ACTIVE, RideStatus.COMPLETED))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.ACTIVE, RideStatus.CANCELLED))
    }

    @Test
    fun `idempotent re-observation of the same status is allowed`() {
        for (status in RideStatus.values()) {
            assertTrue(
                "X -> X must be legal for $status",
                RideLifecyclePolicy.canTransition(status, status)
            )
        }
    }

    @Test
    fun `stale ACTIVE snapshot cannot reactivate a COMPLETED ride`() {
        assertFalse("COMPLETED -> ACTIVE is the exact reported regression", RideLifecyclePolicy.canTransition(RideStatus.COMPLETED, RideStatus.ACTIVE))
        assertFalse(RideLifecyclePolicy.canTransition(RideStatus.COMPLETED, RideStatus.LOBBY))
        assertFalse(RideLifecyclePolicy.canTransition(RideStatus.COMPLETED, RideStatus.CREATED))
    }

    @Test
    fun `terminal states never move to any other state`() {
        for (incoming in RideStatus.values()) {
            // Excludes X→X: that is idempotent re-observation, asserted below.
            if (incoming == RideStatus.COMPLETED) continue
            assertFalse(
                "COMPLETED must stay terminal (received $incoming)",
                RideLifecyclePolicy.canTransition(RideStatus.COMPLETED, incoming)
            )
            if (incoming == RideStatus.CANCELLED) continue
            assertFalse(
                "CANCELLED must stay terminal (received $incoming)",
                RideLifecyclePolicy.canTransition(RideStatus.CANCELLED, incoming)
            )
        }
        // Idempotence carries the repeated observation:
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.COMPLETED, RideStatus.COMPLETED))
        assertTrue(RideLifecyclePolicy.canTransition(RideStatus.CANCELLED, RideStatus.CANCELLED))
    }

    @Test
    fun `an active ride cannot regress into pre-ride states`() {
        assertFalse(RideLifecyclePolicy.canTransition(RideStatus.ACTIVE, RideStatus.LOBBY))
        assertFalse(RideLifecyclePolicy.canTransition(RideStatus.LOBBY, RideStatus.CREATED))
        assertFalse(RideLifecyclePolicy.canTransition(RideStatus.ACTIVE, RideStatus.CREATED))
    }

    @Test
    fun `terminal classification is stable`() {
        assertTrue(RideLifecyclePolicy.isTerminal(RideStatus.COMPLETED))
        assertTrue(RideLifecyclePolicy.isTerminal(RideStatus.CANCELLED))
        assertFalse(RideLifecyclePolicy.isTerminal(RideStatus.ACTIVE))
        assertFalse(RideLifecyclePolicy.isTerminal(RideStatus.LOBBY))
        assertFalse(RideLifecyclePolicy.isTerminal(RideStatus.CREATED))
        assertEquals(5, RideStatus.values().size)
    }
}
