package com.example.data.map.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TileStatsTest {

    private lateinit var stats: TileStats

    @Before
    fun setUp() {
        stats = TileStats()
    }

    @Test
    fun `hitRate starts at 1 when no requests`() {
        assertEquals(1f, stats.hitRate, 0.001f)
    }

    @Test
    fun `records memory and disk hits separately`() {
        stats.recordHit(memory = true)
        stats.recordHit(memory = false)
        stats.recordHit(memory = true)

        assertTrue(stats.toString().contains("hits=3"))
        assertTrue(stats.toString().contains("memoryHits=2"))
        assertTrue(stats.toString().contains("diskHits=1"))
    }

    @Test
    fun `memoryHitRate is correct`() {
        stats.recordHit(memory = true)
        stats.recordHit(memory = false)
        stats.recordHit(memory = true)

        assertEquals(2f / 3f, stats.memoryHitRate, 0.001f)
    }

    @Test
    fun `records empty tile hits`() {
        stats.recordEmptyTileHit()
        stats.recordEmptyTileHit()

        assertTrue(stats.toString().contains("emptyTileHits=2"))
    }

    @Test
    fun `records stale texture reloads`() {
        stats.recordStaleTextureReload()
        stats.recordStaleTextureReload()
        stats.recordStaleTextureReload()

        assertTrue(stats.toString().contains("staleTextureReloads=3"))
    }

    @Test
    fun `records network failures`() {
        stats.recordNetworkLoad()
        stats.recordNetworkFailure()

        assertTrue(stats.toString().contains("networkLoads=1"))
        assertTrue(stats.toString().contains("networkFailures=1"))
    }

    @Test
    fun `records evictions`() {
        stats.recordEviction()
        stats.recordEviction()

        assertTrue(stats.toString().contains("evictions=2"))
    }

    @Test
    fun `toString includes all counters`() {
        stats.recordHit(memory = true)
        stats.recordMiss()
        stats.recordEmptyTileHit()
        stats.recordStaleTextureReload()
        stats.recordEviction()

        val s = stats.toString()
        assertTrue(s.contains("TileStats"))
        assertTrue(s.contains("hits"))
        assertTrue(s.contains("misses"))
        assertTrue(s.contains("emptyTileHits"))
        assertTrue(s.contains("staleTextureReloads"))
        assertTrue(s.contains("evictions"))
    }
}
