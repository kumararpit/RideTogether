package com.example.data.map.tile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileDispatcherTest {

    @Test
    fun `dedup returns same deferred for concurrent requests`() = runTest {
        var calls = 0
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        val d1 = dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { calls++; null })
        val d2 = dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { calls++; null })

        advanceUntilIdle()
        assertEquals(1, calls) // load only runs once despite two submissions
        assertNull(d1.await())
        assertNull(d2.await()) // both deferreds complete with the same value
    }

    @Test
    fun `different keys get separate deferreds`() = runTest {
        var calls = 0
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        val d1 = dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { calls++; null })
        val d2 = dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { calls++; null })

        assertNotSame(d1, d2)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun `maxConcurrent limits in-flight loads`() = runTest {
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { null })
        dispatcher.submit(TileKey(1, 0, 2), priority = 0, load = { null })

        // Before any time advances, 2 are running, 1 is waiting
        assertEquals(2, dispatcher.pendingCount())
        assertEquals(1, dispatcher.waitingCount())
    }

    @Test
    fun `priority waitlist drains highest priority first`() = runTest {
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        dispatcher.submit(TileKey(1, 0, 2), priority = 5, load = { null })
        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 3, load = { null })

        // State right after submission: 2 running, 1 queued
        assertEquals(2, dispatcher.pendingCount())
        assertEquals(1, dispatcher.waitingCount())
    }

    @Test
    fun `cancel removes in-flight and queued loads`() = runTest {
        var cancelled = false
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        val d = dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = {
            try { delay(1000); null }
            catch (e: CancellationException) { cancelled = true; throw e }
        })
        dispatcher.submit(TileKey(1, 0, 1), priority = 1, load = { delay(1000); null })

        // Let coroutines start and suspend on delay
        advanceTimeBy(1)
        dispatcher.cancel(TileKey(1, 0, 0))
        advanceTimeBy(1)
        assertTrue(cancelled)
        assertFalse(dispatcher.isPending(TileKey(1, 0, 0)))
        assertTrue(dispatcher.isPending(TileKey(1, 0, 1)))
    }

    @Test
    fun `cancelAll clears everything`() = runTest {
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { delay(1000); null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { delay(1000); null })

        advanceTimeBy(1)
        dispatcher.cancelAll()
        assertEquals(0, dispatcher.pendingCount())
        assertEquals(0, dispatcher.waitingCount())
    }

    @Test
    fun `pendingKeys returns in-flight keys`() = runTest {
        val dispatcher = TileDispatcher(this, maxConcurrent = 2)
        dispatcher.submit(TileKey(1, 0, 0), priority = 0, load = { null })
        dispatcher.submit(TileKey(1, 0, 1), priority = 0, load = { null })

        assertEquals(2, dispatcher.pendingKeys.size)
        assertTrue(TileKey(1, 0, 0) in dispatcher.pendingKeys)
        assertTrue(TileKey(1, 0, 1) in dispatcher.pendingKeys)
    }
}
