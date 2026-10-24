package com.example.data.map.tile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.PriorityQueue

/**
 * Request deduplication and cancellation manager with priority-ordered loading.
 *
 * Mirrors Google Maps' `DedupingUrlLoader` + `inflight_creation.cc`:
 *
 *   - Concurrent requests for the same TileKey collapse into one in-flight load.
 *   - All pending loads can be cancelled (e.g. on camera move / zoom change).
 *   - Duplicate requests are suppressed (not re-executed).
 *   - Tiles are loaded in priority order (lower number = higher priority).
 *   - At most [maxConcurrent] loads run at once; excess are queued by priority.
 *
 * Priority ordering mirrors Google Maps' `ORDER BY priority` and priority boosts:
 * viewport-centre tiles get low priority numbers and load first, so the map
 * centre never shows a black gap while off-screen tiles are still downloading.
 */
class TileDispatcher(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 8
) {

    /** In-flight loads, keyed by tile. */
    private val pending = ConcurrentHashMap<TileKey, Deferred<ByteArray?>>()

    /** Queued loads waiting for a concurrency slot, ordered by priority. */
    private val waitlist = PriorityQueue<WaitingEntry>()

    /** Monotonic tiebreaker for FIFO ordering within the same priority. */
    private var seq = 0L

    private data class WaitingEntry(
        /** Lower = higher priority (0 = viewport centre). */
        val priority: Int,
        val seq: Long,
        val key: TileKey,
        val load: suspend () -> ByteArray?,
        val result: CompletableDeferred<ByteArray?>
    ) : Comparable<WaitingEntry> {
        override fun compareTo(other: WaitingEntry): Int {
            val p = compareValues(this.priority, other.priority)
            return if (p != 0) p else compareValues(this.seq, other.seq)
        }
    }

    /**
     * Submits a tile load. If [key] is already being loaded or queued, returns the
     * existing Deferred instead of starting a duplicate.
     *
     * @param priority lower numbers load first (0 = most urgent / viewport centre)
     */
    fun submit(key: TileKey, load: suspend () -> ByteArray?, priority: Int = 0): Deferred<ByteArray?> {
        // Dedup: already in flight.
        pending[key]?.let { return it }

        // Dedup: already queued (first submission wins).
        waitlist.asSequence().firstOrNull { entry -> entry.key == key }?.result?.let { return it }

        val result = CompletableDeferred<ByteArray?>()

        if (pending.size < maxConcurrent) {
            start(key, load, result)
        } else {
            waitlist.add(WaitingEntry(priority, seq++, key, load, result))
        }

        return result
    }

    /** True if [key] currently has an in-flight or queued load. */
    fun isPending(key: TileKey): Boolean = pending.containsKey(key) || waitlist.any { entry -> entry.key == key }

    /** Number of in-flight loads. */
    fun pendingCount(): Int = pending.size

    /** Number of queued (not yet started) loads. */
    fun waitingCount(): Int = waitlist.size

    /** Keys of all in-flight loads. Useful for cancelling out-of-range tiles on pan. */
    val pendingKeys: Set<TileKey>
        get() = pending.keys.toSet()

    /** Cancels all in-flight and queued tile loads. Call on camera move / zoom change. */
    fun cancelAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        waitlist.forEach { entry -> entry.result.cancel() }
        waitlist.clear()
    }

    /** Cancels the in-flight or queued load for [key]. */
    fun cancel(key: TileKey) {
        pending.remove(key)?.cancel()
        val toCancel = waitlist.asSequence().filter { entry -> entry.key == key }.toList()
        toCancel.forEach { entry ->
            waitlist.remove(entry)
            entry.result.cancel()
        }
    }

    /** Starts loading [key] immediately (caller must ensure concurrency headroom). */
    private fun start(key: TileKey, load: suspend () -> ByteArray?, result: CompletableDeferred<ByteArray?>) {
        val deferred = scope.async {
            try {
                load()
            } finally {
                pending.remove(key)
                drain()
            }
        }
        pending[key] = deferred

        // Bridge the internal Deferred to the caller's CompletableDeferred.
        scope.launch {
            try {
                result.complete(deferred.await())
            } catch (e: CancellationException) {
                result.cancel(e)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
    }

    /** Starts the highest-priority queued load if a concurrency slot is free. */
    private fun drain() {
        while (pending.size < maxConcurrent) {
            val entry = waitlist.poll() ?: return
            // Skip keys that were cancelled while waiting.
            if (pending.containsKey(entry.key)) continue
            start(entry.key, entry.load, entry.result)
        }
    }
}
