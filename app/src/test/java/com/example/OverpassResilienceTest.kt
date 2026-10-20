package com.example

import com.example.data.amenities.OverpassResilience
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException

/**
 * Phase 5 regression tests: only transient failures retry (bounded API load),
 * permanent failures fail fast, offline callers never burn a retry.
 */
class OverpassResilienceTest {

    @Test
    fun `transient classification covers the measured server failure classes`() {
        assertTrue(OverpassResilience.isTransientFailure(IOException("ConnectException: refused")))
        // Verified live classes (2026-10-19): HTTP 414 / 406 are permanent; 429 is
        // not seen from overpass but remains transient-by-classification.
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            assertTrue(OverpassResilience.isTransientFailure(fakeHttpException(code)))
        }
        for (code in listOf(400, 406, 410, 414, 418, 501)) {
            assertFalse(OverpassResilience.isTransientFailure(fakeHttpException(code)))
        }
    }

    @Test
    fun `retryDelayMs honours the bounded jitter fraction`() {
        for (i in 1..50) {
            val delay = OverpassResilience.retryDelayMs(
                kotlin.random.Random(742L + i)
            )
            assertTrue("delay $delay outside jitter band", delay in 2250..3750)
        }
    }

    @Test
    fun `transient failure is retried exactly once with jittered cooldown`() = runTest {
        var calls = 0
        val result = OverpassResilience.queryWithOneRetry<String> {
            calls++
            if (calls == 1) throw IOException("stream was reset: CANCEL")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `permanent 4xx fails fast without retry`() = runTest {
        var calls = 0
        var thrown: Throwable? = null
        try {
            OverpassResilience.queryWithOneRetry<String> {
                calls++
                throw fakeHttpException(414) // measured HTTP 414 oversized GET/URI
            }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is retrofit2.HttpException)
        assertEquals(414, (thrown as retrofit2.HttpException).code())
        assertEquals(1, calls) // no wasted retry on a permanent request error
    }

    @Test
    fun `offline callers never retry the dead path`() = runTest {
        var calls = 0
        var thrown: Throwable? = null
        try {
            OverpassResilience.queryWithOneRetry<String>(
                isProbablyOffline = true
            ) {
                calls++
                throw IOException("network unreachable")
            }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is IOException)
        assertEquals(1, calls)
    }

    @Test
    fun `cancellation is never retried`() = runTest {
        var calls = 0
        var thrown: Throwable? = null
        try {
            OverpassResilience.queryWithOneRetry<String> {
                calls++
                throw CancellationException("srvc teardown")
            }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertEquals(1, calls)
    }

    private fun fakeHttpException(code: Int): retrofit2.HttpException {
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").build()
        val response = Response.error<Unit>(
            code,
            okhttp3.ResponseBody.create(null, "resilience probe")
        )
        return retrofit2.HttpException(response)
    }
}
