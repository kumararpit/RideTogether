package com.example.data.firebase

import com.example.data.model.QuickMessage

/**
 * Session-scoped registry of group (quick) messages for the active ride.
 *
 * Purpose (Task 21 root-cause fix for "message intermittently missed"):
 *  - the old listener pushed only the single newest snapshot doc into one state slot,
 *    so a rapid burst could silently overwrite an earlier message before it was shown;
 *  - this registry keeps every NEW message id once, deduplicates the sender's local
 *    echo against the server snapshot (same stable id), and orders deterministically
 *    by `timestampMs` then `id` when timestamps tie or arrive out of order.
 *
 * Pure (no Android/Firestore types) so listener reconciliation is unit-testable
 * without a live Firebase project.
 */
class QuickMessageRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    private val messages = LinkedHashMap<String, QuickMessage>()

    /**
     * Accepts a message and returns `true` only when its id was NEW on this device
     * (echo/retry of an already-known id updates the stored copy but is not "new").
     */
    @Synchronized
    fun accept(msg: QuickMessage): Boolean {
        if (capacity <= 0) return false
        val isNew = !messages.containsKey(msg.id)
        messages.remove(msg.id)
        messages[msg.id] = msg // move-to-end → most recent updated entry last
        while (messages.size > capacity) {
            val oldest = messages.keys.first()
            messages.remove(oldest)
        }
        return isNew
    }

    /** Chronologically ordered (oldest → newest), tie-broken by id for fairness. */
    @Synchronized
    fun snapshot(): List<QuickMessage> =
        messages.values.sortedWith(compareBy({ it.timestampMs }, { it.id }))

    @Synchronized
    fun newest(): QuickMessage? = snapshot().lastOrNull()

    @Synchronized
    fun contains(messageId: String): Boolean = messages.containsKey(messageId)

    @Synchronized
    fun size(): Int = messages.size

    @Synchronized
    fun clear() {
        messages.clear()
    }

    companion object {
        /**
         * Bounded local history — large enough to re-show a rapid burst sequentially,
         * small enough that re-emitting after process recreation replays only recent
         * messages rather than the whole ride.
         */
        const val DEFAULT_CAPACITY = 20
    }
}
