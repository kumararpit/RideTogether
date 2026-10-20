package com.example.data.model

/**
 * Canonical ride lifecycle state machine.
 *
 * The shared Firestore `rides/{rideId}` document field `status` is the **authoritative**
 * lifecycle state for the whole group. This policy decides which transitions are legal
 * so that:
 *  - a stale snapshot (e.g. a delayed `ACTIVE` update after a completion) can never
 *    resurrect a completed ride — the terminal-state guard;
 *  - an authorized `ACTIVE` start or `COMPLETED` finish is always accepted;
 *  - transitions are checked in ONE place (repository ride-listener, `startRide`,
 *    `endRide`) instead of scattered string comparisons.
 *
 * Allowed (current → incoming):
 *  CREATED   → LOBBY, ACTIVE, COMPLETED, CANCELLED
 *  LOBBY     → ACTIVE, COMPLETED, CANCELLED
 *  ACTIVE    → COMPLETED, CANCELLED
 *  X → X     → always allowed (idempotent re-observation, e.g. repeated COMPLETED snapshots)
 *
 * Explicitly REJECTED (logged at the boundary by the caller):
 *  COMPLETED/CANCELLED → anything else — a stale snapshot must not reactivate or mutate a
 *    finished ride. Reopening a completed ride is not a side effect of receiving stale
 *    data; it must go through an explicit product-level rejoin/new-ride flow.
 *  ACTIVE → LOBBY/CREATED — an active ride cannot regress.
 *  anything → CREATED — no transition re-enters creation.
 */
object RideLifecyclePolicy {

    fun canTransition(current: RideStatus, incoming: RideStatus): Boolean = when {
        current == incoming -> true // idempotent re-observation

        isTerminal(current) -> false // terminal states never move anywhere else

        incoming == RideStatus.CREATED -> false

        current == RideStatus.CREATED ->
            incoming == RideStatus.LOBBY || incoming == RideStatus.ACTIVE ||
                incoming == RideStatus.COMPLETED || incoming == RideStatus.CANCELLED

        current == RideStatus.LOBBY ->
            incoming == RideStatus.ACTIVE || incoming == RideStatus.COMPLETED ||
                incoming == RideStatus.CANCELLED

        current == RideStatus.ACTIVE ->
            incoming == RideStatus.COMPLETED || incoming == RideStatus.CANCELLED

        else -> false
    }

    /** Terminal states must stay terminal against any non-identical incoming status. */
    fun isTerminal(status: RideStatus): Boolean =
        status == RideStatus.COMPLETED || status == RideStatus.CANCELLED
}
