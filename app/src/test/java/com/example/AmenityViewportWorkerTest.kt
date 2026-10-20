package com.example

import com.example.data.amenities.AmenityViewportWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the serial amenity fetch worker:
 * the previous design cancelled the debounced viewport coroutine on every camera
 * move, killing in-flight Overpass queries (okhttp StreamResetException CANCEL,
 * device 2026-10-19) so amenity data never landed in the cache. The worker must
 * instead let an in-flight fetch finish and conflate pending viewports.
 */
class AmenityViewportWorkerTest {

    private data class Outcome(
        val minLat: Double,
        val processedAtMarker: Int,
        val values: List<String>
    )

    @Test
    fun `in-flight fetch survives a submit that arrives while it runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Double>()
        val outcomes = mutableListOf<Outcome>()

        val worker = AmenityViewportWorker<Outcome>(
            scope = this,
            process = { minLat, _, _, _ ->
                started.add(minLat)
                gate.await() // fetch #1 blocked mid-flight
                Outcome(minLat, 1, listOf("amenities@$minLat"))
            },
            onResult = { outcomes.add(it) }
        )
        val job = worker.start()

        worker.submit(1.0, 2.0, 3.0, 4.0)
        runCurrent()
        assertEquals(listOf(1.0), started) // fetch started and is blocked

        // A later viewport update arrives while the fetch is in flight —
        // the old design cancelled the coroutine here (StreamReset CANCEL).
        worker.submit(9.0, 9.0, 9.0, 9.0)
        runCurrent()
        assertEquals(listOf(1.0), started) // second submit did not cancel/start yet

        gate.complete(Unit)
        advanceTimeBy(1)
        runCurrent()
        advanceUntilIdle()
        job.cancel()

        // First fetch COMPLETED; the superseded viewport is then processed.
        assertEquals(
            listOf(Outcome(1.0, 1, listOf("amenities@1.0")), Outcome(9.0, 1, listOf("amenities@9.0"))),
            outcomes
        )
    }

    @Test
    fun `pending viewports are conflated so only the newest runs after the active fetch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Double>()
        val outcomes = mutableListOf<Outcome>()

        val worker = AmenityViewportWorker<Outcome>(
            scope = this,
            process = { minLat, _, _, _ ->
                started.add(minLat)
                gate.await()
                Outcome(minLat, 1, emptyList())
            },
            onResult = { outcomes.add(it) }
        )
        val job = worker.start()

        worker.submit(1.0, 1.0, 1.0, 1.0)
        runCurrent()
        // Three camera moves while the first fetch is running; only the newest matters.
        worker.submit(2.0, 2.0, 2.0, 2.0)
        worker.submit(3.0, 3.0, 3.0, 3.0)
        worker.submit(4.0, 4.0, 4.0, 4.0)

        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(1.0, 4.0), started) // 2.0 and 3.0 conflated away
        assertEquals(listOf(1.0, 4.0), outcomes.map { it.minLat })
    }

    @Test
    fun `delivered results keep processing order so a stale viewport never wins`() = runTest {
        val outcomes = mutableListOf<Double>()
        val worker = AmenityViewportWorker<Double>(
            scope = this,
            process = { minLat, _, _, _ -> minLat },
            onResult = { outcomes.add(it) }
        )
        val job = worker.start()
        worker.submit(1.0, 1.0, 1.0, 1.0)
        runCurrent() // let the worker receive and process #1 before submitting #2
        worker.submit(2.0, 2.0, 2.0, 2.0)
        runCurrent()
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(1.0, 2.0), outcomes) // newest lands last, never overwritten
    }
}
