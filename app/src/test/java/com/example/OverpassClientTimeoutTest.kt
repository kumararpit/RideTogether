package com.example

import com.example.data.amenities.OverpassApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Overpass client timeout configuration against the demonstrated
 * 2026-10-19 defect: a 15 s read timeout while the public Overpass instance
 * legitimately needs 22–40 s for multi-cell amenity queries — every such query
 * failed spuriously, and the device's amenity cache stayed empty for weeks.
 *
 * The server-side query timeout opened by AmenityRepository is [timeout:25];
 * the client read budget must exceed it.
 */
class OverpassClientTimeoutTest {

    /** The server-side Overpass QL timeout used by fetchAndCacheCells. */
    private val serverQueryTimeoutMs = 25_000L

    @Test
    fun `read timeout exceeds the server-side Overpass query timeout`() {
        val readTimeoutMs = OverpassApiClient.okHttpClient.readTimeoutMillis.toLong()
        assertTrue(
            "read timeout $readTimeoutMs ms must exceed server QL timeout $serverQueryTimeoutMs ms",
            readTimeoutMs > serverQueryTimeoutMs
        )
    }

    @Test
    fun `read timeout is configured to the documented 60 s budget`() {
        assertEquals(60_000L, OverpassApiClient.okHttpClient.readTimeoutMillis.toLong())
        assertEquals(60, OverpassApiClient.READ_TIMEOUT_SECONDS)
    }

    @Test
    fun `connect timeout stays at the documented 15 s`() {
        assertEquals(15_000L, OverpassApiClient.okHttpClient.connectTimeoutMillis.toLong())
        assertEquals(15, OverpassApiClient.CONNECT_TIMEOUT_SECONDS)
    }
}
