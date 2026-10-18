package com.example.data.firebase

/**
 * Deterministic retry policy for SOS Firestore writes (ack / resolve).
 *
 * The public SOS flow rides through dead zones: a single failed transaction used to
 * vanish into a log line, which left members acknowledging a banner the sender had
 * already resolved ("I am okay" never propagated). Fixed attempt counts and a small
 * monotonic backoff bound the work: at most [MAX_ATTEMPTS] server attempts per user
 * action, each ~1 s apart — acceptable for an emergency flow, and bounded so the
 * write never blocks the UI thread.
 */
object FirestoreWriteRetryPolicy {
    const val MAX_ATTEMPTS = 3

    /** Backoff before the given 1-based attempt. First attempt has no delay. */
    fun delayBeforeAttemptMs(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val step = (attempt - 1).coerceIn(1, 4)
        return (step * 500L).coerceAtMost(2_000L)
    }
}

/** Compat alias for the SOS flow call sites and tests. */
typealias SosRetryPolicy = FirestoreWriteRetryPolicy

/**
 * Session-scoped suppression of SOS alerts on THIS device only (never touches
 * Firestore). Two suppression sources, both by event id:
 *
 *  1. **Resolve tombstone** — the SOS sender/leader resolved locally ("I'm okay")
 *     before/without a confirmed server write; a stale snapshot of that unresolved
 *     doc must never re-apply the alert here.
 *  2. **Manual member dismissal** — other members can explicitly remove the popup
 *     from their screen (per-device courtesy; does not resolve anyone's emergency).
 *
 * A *different* (new) SOS event id is never suppressed, so a fresh emergency from
 * any rider — including a corrected re-trigger — always surfaces.
 */
class SosSuppressionState {
    private val ids = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun suppress(id: String) {
        if (id.isNotBlank()) ids.add(id)
    }

    fun isSuppressed(id: String?): Boolean = id != null && ids.contains(id)

    fun clear() {
        ids.clear()
    }
}
