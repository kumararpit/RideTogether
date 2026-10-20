package com.example.data.amenities

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A single conflated worker that runs amenity viewport fetches serially and is
 * never cancelled by later viewport updates.
 *
 * Device evidence (2026-10-19, ride screen at Marunji/Hinjawadi viewport):
 * the previous design launched each fetch inside the debounced viewport job and
 * cancelled that job on the next camera move; a live Overpass query takes tens of
 * seconds, so in-flight fetches were cancelled with okhttp's
 * `StreamResetException: stream was reset: CANCEL` during ride-screen settling —
 * observed twice — and the local amenity cache stayed empty after weeks of use.
 *
 * With this worker:
 *  - [submit] never cancels anything; viewports are conflated (Channel.CONFLATED)
 *    so only the newest pending viewport is queued while one fetch runs.
 *  - The fetch (slow network) runs inside the worker's own coroutine; the caller
 *    of [submit] stays free and cheap (debounce only).
 *  - Results surface in processing order (newest viewport last), so a stale
 *    viewport can never be delivered after a newer one.
 */
class AmenityViewportWorker<T>(
    private val scope: CoroutineScope,
    private val process: suspend (
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ) -> T,
    private val onResult: suspend (T) -> Unit
) {
    private data class Viewport(
        val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double
    )

    private val viewports = Channel<Viewport>(Channel.CONFLATED)
    private var worker: Job? = null

    /** Starts consuming submitted viewports. Safe to call multiple times; returns the active job. */
    fun start(): Job {
        val existing = worker
        if (existing?.isActive == true) return existing
        return scope.launch {
            for (viewport in viewports) {
                onResult(process(viewport.minLat, viewport.maxLat, viewport.minLon, viewport.maxLon))
            }
        }.also { worker = it }
    }

    /** Queues a viewport for processing; while a fetch runs, this replaces any pending one. */
    fun submit(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        viewports.trySend(Viewport(minLat, maxLat, minLon, maxLon))
    }
}
